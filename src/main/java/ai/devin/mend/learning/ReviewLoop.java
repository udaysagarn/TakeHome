package ai.devin.mend.learning;

import ai.devin.mend.config.MendProperties;
import ai.devin.mend.devin.DevinApiClient;
import ai.devin.mend.devin.DevinDtos;
import ai.devin.mend.domain.IssueState;
import ai.devin.mend.domain.Learning;
import ai.devin.mend.domain.RemediationTask;
import ai.devin.mend.domain.Retrospective;
import ai.devin.mend.domain.TaskRepository;
import ai.devin.mend.engine.Notifier;
import ai.devin.mend.engine.PromptBuilder;
import ai.devin.mend.engine.TaskService;
import ai.devin.mend.github.GitHubClient;
import ai.devin.mend.github.GitHubDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Watches menD's own pull requests for what humans say about them.
 *
 * <p>A rejection is not the end of a task: reviewer feedback is handed straight back to the session
 * that wrote the code, and the task returns to the pipeline. Once a task is finally settled, a cheap
 * read-only retrospective turns the review history into durable lessons, which
 * {@link LearningService} feeds into later sessions for that repository — and, when a lesson is
 * broader than one codebase, surfaces as a recommendation to promote it further.
 */
@Component
public class ReviewLoop {

    private static final Logger log = LoggerFactory.getLogger(ReviewLoop.class);
    private static final String ACTOR = "review-loop";

    /** States where a pull request exists and human feedback is still worth acting on. */
    private static final Set<IssueState> WATCHED = EnumSet.of(
            IssueState.PR_OPEN,
            IssueState.VERIFYING,
            IssueState.CHANGES_REQUESTED,
            IssueState.SUCCEEDED,
            IssueState.UNVERIFIED,
            IssueState.NEEDS_HUMAN);

    private final TaskRepository tasks;
    private final TaskService taskService;
    private final GitHubClient github;
    private final DevinApiClient devin;
    private final PromptBuilder prompts;
    private final Notifier notifier;
    private final LearningService learnings;
    private final ObjectMapper mapper;
    private final MendProperties props;

    public ReviewLoop(
            TaskRepository tasks,
            TaskService taskService,
            GitHubClient github,
            DevinApiClient devin,
            PromptBuilder prompts,
            Notifier notifier,
            LearningService learnings,
            ObjectMapper mapper,
            MendProperties props) {
        this.tasks = tasks;
        this.taskService = taskService;
        this.github = github;
        this.devin = devin;
        this.prompts = prompts;
        this.notifier = notifier;
        this.learnings = learnings;
        this.mapper = mapper;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${mend.learning.review-poll-interval:PT2M}")
    public void tick() {
        if (!props.getEngine().isEnabled() || !github.isConfigured()) {
            return;
        }
        for (RemediationTask task : tasks.findByStateIn(List.copyOf(WATCHED))) {
            if (task.getPrUrl() == null) {
                continue;
            }
            try {
                collectFeedback(task);
                retrospect(tasks.findById(task.getId()).orElse(task));
            } catch (RuntimeException e) {
                log.warn("review loop failed for {}: {}", task.key(), e.getMessage());
            }
        }
    }

    /**
     * Webhook entry point. The event is only a hint that something happened: menD re-reads the reviews
     * from the API, so a lost or duplicated delivery changes nothing.
     */
    public String onPullRequestEvent(String repo, String prUrl, boolean closedUnmerged) {
        Optional<RemediationTask> maybe = tasks.findByRepoAndPrUrl(repo, prUrl);
        if (maybe.isEmpty()) {
            return "ignored: no menD task owns " + prUrl;
        }
        RemediationTask task = maybe.get();
        if (closedUnmerged) {
            if (!task.getState().canTransitionTo(IssueState.NEEDS_HUMAN)) {
                return "ignored: %s is already %s".formatted(task.key(), task.getState());
            }
            taskService.transition(task, IssueState.NEEDS_HUMAN, "the pull request was closed unmerged", ACTOR);
            notifier.escalated(task, "A human closed " + prUrl + " without merging it. menD will not reopen it.");
            return "escalated " + task.key();
        }
        collectFeedback(task);
        return "read reviews on " + task.key();
    }

    /**
     * Pulls human reviews and inline comments newer than the last round menD acted on. Bot chatter —
     * menD's own comments included — is ignored, so the loop cannot feed itself.
     */
    void collectFeedback(RemediationTask task) {
        Integer pull = GitHubClient.pullNumberFromUrl(task.getPrUrl());
        if (pull == null) {
            return;
        }
        Instant since = task.getLastReviewAt();
        List<GitHubDtos.Review> reviews = github.listReviews(task.getRepo(), pull).stream()
                .filter(r -> r.user() == null || !r.user().isBot())
                .filter(r -> isNewer(r.submittedAt(), since))
                .filter(r -> r.isRejection() || hasBody(r.body()))
                .toList();
        List<GitHubDtos.ReviewComment> comments = github.listReviewComments(task.getRepo(), pull).stream()
                .filter(c -> c.user() == null || !c.user().isBot())
                .filter(c -> isNewer(c.createdAt(), since))
                .filter(c -> hasBody(c.body()))
                .toList();
        if (reviews.isEmpty() && comments.isEmpty()) {
            return;
        }

        String feedback = render(reviews, comments);
        Instant newest = newest(reviews, comments);
        task.setFeedbackJson(feedback);
        task.setLastReviewAt(newest);
        task = taskService.save(task);
        learnings.recordFeedbackDespite(task.getRepo());

        boolean rejected = reviews.stream().anyMatch(GitHubDtos.Review::isRejection);
        if (!rejected || !task.getState().canTransitionTo(IssueState.CHANGES_REQUESTED)) {
            log.info("recorded {} review signal(s) on {} without reopening it", reviews.size() + comments.size(), task.key());
            return;
        }
        taskService.transition(task, IssueState.CHANGES_REQUESTED, "a reviewer asked for changes", ACTOR);
    }

    /**
     * Hands the reviewer's words back to the session that wrote the code. Called by the orchestrator
     * under the task's lease, so only the owning worker ever replies.
     */
    public void respondToFeedback(RemediationTask task) {
        int max = props.getLearning().getMaxReviewRounds();
        if (task.getReviewRounds() >= max) {
            taskService.transition(
                    task,
                    IssueState.NEEDS_HUMAN,
                    "reviewer still unsatisfied after %d rounds".formatted(max),
                    ACTOR);
            notifier.escalated(task, "The reviewer asked for changes %d times; menD is not going to keep guessing."
                    .formatted(max));
            return;
        }
        int round = task.getReviewRounds() + 1;
        task.setReviewRounds(round);
        task = taskService.save(task);
        devin.sendMessage(
                task.getSessionId(),
                prompts.reviewFeedbackMessage(task.getPrUrl(), round, max, task.getFeedbackJson()));
        notifier.changesRequested(task, round, max, task.getFeedbackJson());
        taskService.transition(task, IssueState.RUNNING, "reviewer feedback handed back to the session", ACTOR);
    }

    /** Runs, then reads, the retrospective for a settled task that actually drew feedback. */
    void retrospect(RemediationTask task) {
        if (!props.getLearning().isRetrospectiveEnabled()
                || task.isLearningsExtracted()
                || !task.getState().isTerminal()
                || task.getFeedbackJson() == null
                || !devin.isConfigured()) {
            return;
        }
        if (task.getRetrospectiveSessionId() == null) {
            DevinDtos.SessionDetails session = devin.createSession(
                    prompts.retrospectivePrompt(
                            task.getRepo(),
                            task.getIssueNumber(),
                            task.getPrUrl(),
                            describeOutcome(task),
                            task.getFeedbackJson()),
                    "menD retrospective %s".formatted(task.key()),
                    List.of("mend", "mend-retrospective", task.getRepo()),
                    props.getLearning().getRetrospectiveAcuLimit(),
                    Retrospective.JSON_SCHEMA,
                    task.getRepo());
            task.setRetrospectiveSessionId(session.sessionId());
            taskService.save(task);
            return;
        }
        Optional<DevinDtos.SessionDetails> maybe = devin.getSession(task.getRetrospectiveSessionId());
        if (maybe.isEmpty()) {
            task.setLearningsExtracted(true);
            taskService.save(task);
            return;
        }
        DevinDtos.SessionDetails session = maybe.get();
        if (!session.hasStructuredOutput()) {
            if (session.isFinished() || session.isExpired()) {
                task.setLearningsExtracted(true);
                taskService.save(task);
            }
            return;
        }
        Retrospective retrospective;
        try {
            retrospective = mapper.convertValue(session.structuredOutput(), Retrospective.class);
        } catch (IllegalArgumentException e) {
            log.warn("retrospective for {} did not match the schema: {}", task.key(), e.getMessage());
            task.setLearningsExtracted(true);
            taskService.save(task);
            return;
        }
        List<Learning> stored =
                learnings.absorb(retrospective, task.getRepo(), task.getIssueNumber(), task.getPrUrl());
        task.setLearningsExtracted(true);
        taskService.save(task);
        notifier.learned(task, stored);
        log.info("retrospective on {} produced {} new lesson(s)", task.key(), stored.size());
    }

    private String describeOutcome(RemediationTask task) {
        List<String> lines = new ArrayList<>();
        lines.add("final state: " + task.getState());
        lines.add("attempts: " + task.getAttempts() + ", review rounds: " + task.getReviewRounds());
        if (task.getVerificationTier() != null) {
            lines.add("verified by tier: " + task.getVerificationTier() + " (" + task.getCiStatus() + ")");
        }
        if (task.getLastError() != null) {
            lines.add("last error: " + task.getLastError());
        }
        if (task.getOutcomeJson() != null) {
            lines.add("what the session reported: " + task.getOutcomeJson());
        }
        return String.join("\n", lines);
    }

    private static String render(List<GitHubDtos.Review> reviews, List<GitHubDtos.ReviewComment> comments) {
        String reviewText = reviews.stream()
                .map(r -> "%s by @%s: %s"
                        .formatted(
                                r.state(),
                                r.user() == null ? "reviewer" : r.user().login(),
                                r.body() == null ? "(no body)" : r.body().strip()))
                .collect(Collectors.joining("\n\n"));
        String commentText = comments.stream()
                .map(c -> "%s%s by @%s: %s"
                        .formatted(
                                c.path() == null ? "inline comment" : c.path(),
                                c.line() == null ? "" : ":" + c.line(),
                                c.user() == null ? "reviewer" : c.user().login(),
                                c.body().strip()))
                .collect(Collectors.joining("\n\n"));
        return (reviewText + "\n\n" + commentText).strip();
    }

    private static Instant newest(List<GitHubDtos.Review> reviews, List<GitHubDtos.ReviewComment> comments) {
        Instant newest = Instant.EPOCH;
        for (GitHubDtos.Review review : reviews) {
            if (review.submittedAt() != null && review.submittedAt().isAfter(newest)) {
                newest = review.submittedAt();
            }
        }
        for (GitHubDtos.ReviewComment comment : comments) {
            if (comment.createdAt() != null && comment.createdAt().isAfter(newest)) {
                newest = comment.createdAt();
            }
        }
        return newest == Instant.EPOCH ? Instant.now() : newest;
    }

    private static boolean isNewer(Instant when, Instant since) {
        return since == null || when == null || when.isAfter(since);
    }

    private static boolean hasBody(String body) {
        return body != null && !body.isBlank();
    }
}
