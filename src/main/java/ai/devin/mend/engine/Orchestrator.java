package ai.devin.mend.engine;

import ai.devin.mend.config.MendProperties;
import ai.devin.mend.devin.DevinApiClient;
import ai.devin.mend.devin.DevinDtos;
import ai.devin.mend.domain.IssueState;
import ai.devin.mend.domain.RemediationOutcome;
import ai.devin.mend.domain.RemediationTask;
import ai.devin.mend.domain.SuccessCriteria;
import ai.devin.mend.domain.TaskRepository;
import ai.devin.mend.domain.Verification;
import ai.devin.mend.github.GitHubClient;
import ai.devin.mend.github.GitHubDtos;
import ai.devin.mend.learning.LearningService;
import ai.devin.mend.learning.ReviewLoop;
import ai.devin.mend.metrics.MendMetrics;
import ai.devin.mend.registry.ContextService;
import ai.devin.mend.triage.PreFilter;
import ai.devin.mend.triage.SuccessCriteriaService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * The control plane. Owns the per-issue lifecycle described in {@link IssueState}: it decides whether
 * an issue is automatable, creates and supervises the Devin session that fixes it, and closes the loop
 * on CI.
 */
@Service
public class Orchestrator {

    private static final Logger log = LoggerFactory.getLogger(Orchestrator.class);
    private static final String ACTOR = "orchestrator";

    /** MDC keys carried by every log line written while a task is being advanced. */
    public static final String MDC_TASK_KEY = "taskKey";

    public static final String MDC_REPO = "repo";

    private final TaskRepository tasks;
    private final TaskService taskService;
    private final DevinApiClient devin;
    private final GitHubClient github;
    private final PreFilter preFilter;
    private final SuccessCriteriaService criteriaService;
    private final PromptBuilder prompts;
    private final Notifier notifier;
    private final Verifier verifier;
    private final LearningService learnings;
    private final ReviewLoop reviewLoop;
    private final ContextService context;
    private final MendMetrics metrics;
    private final ObjectMapper mapper;
    private final EngineControl control;
    private final MendProperties props;
    private final Clock clock;

    public Orchestrator(
            TaskRepository tasks,
            TaskService taskService,
            DevinApiClient devin,
            GitHubClient github,
            PreFilter preFilter,
            SuccessCriteriaService criteriaService,
            PromptBuilder prompts,
            Notifier notifier,
            Verifier verifier,
            LearningService learnings,
            ReviewLoop reviewLoop,
            ContextService context,
            MendMetrics metrics,
            ObjectMapper mapper,
            EngineControl control,
            MendProperties props,
            Clock clock) {
        this.tasks = tasks;
        this.taskService = taskService;
        this.devin = devin;
        this.github = github;
        this.preFilter = preFilter;
        this.criteriaService = criteriaService;
        this.prompts = prompts;
        this.notifier = notifier;
        this.verifier = verifier;
        this.learnings = learnings;
        this.reviewLoop = reviewLoop;
        this.context = context;
        this.metrics = metrics;
        this.mapper = mapper;
        this.control = control;
        this.props = props;
        this.clock = clock;
    }

    /** Entry point for every trigger: webhook, poller or manual dashboard action. */
    public RemediationTask onTriggerLabel(String repo, GitHubDtos.Issue issue) {
        Optional<RemediationTask> existing = tasks.findByRepoAndIssueNumber(repo, issue.number());
        if (existing.isPresent()) {
            RemediationTask task = existing.get();
            if (task.getState() == IssueState.NOT_A_CANDIDATE || task.getState() == IssueState.CANCELLED) {
                log.info("re-labelled {} in state {}; leaving terminal state untouched", task.key(), task.getState());
            }
            return task;
        }
        RemediationTask task = new RemediationTask(
                repo, issue.number(), issue.title(), issue.htmlUrl(), String.join(",", issue.labelNames()));
        task = taskService.save(task);
        log.info("discovered task={} title=\"{}\"", task.key(), issue.title());
        return task;
    }

    /** Advances a single task by at most one meaningful step. Safe to call repeatedly. */
    public void advance(RemediationTask task) {
        MDC.put(MDC_TASK_KEY, task.key());
        MDC.put(MDC_REPO, task.getRepo());
        try {
            switch (task.getState()) {
                case DISCOVERED -> triage(task);
                case CRITERIA_PENDING -> pollCriteriaSession(task);
                case READY -> dispatch(task);
                case DISPATCHED, RUNNING, BLOCKED -> pollRemediationSession(task);
                case PR_OPEN -> taskService.transition(task, IssueState.VERIFYING, "waiting for CI", ACTOR);
                case VERIFYING -> verify(task);
                case CHANGES_REQUESTED -> reviewLoop.respondToFeedback(task);
                case FAILED -> retryIfBudgetRemains(task);
                default -> {}
            }
        } catch (RuntimeException e) {
            log.error("advance failed for {} in state {}", task.key(), task.getState(), e);
            recordError(task.getId(), e);
        } finally {
            MDC.remove(MDC_TASK_KEY);
            MDC.remove(MDC_REPO);
        }
    }

    // ---------------------------------------------------------------- triage

    private void triage(RemediationTask task) {
        GitHubDtos.Issue issue =
                github.getIssue(task.getRepo(), task.getIssueNumber()).orElse(null);
        if (issue == null) {
            task = taskService.transition(task, IssueState.CANCELLED, "issue no longer accessible", ACTOR);
            return;
        }
        task.setIssueTitle(issue.title());
        task.setIssueLabels(String.join(",", issue.labelNames()));
        task = taskService.save(task);

        Optional<String> rejection = preFilter.reject(issue.title(), issue.body(), issue.labelNames());
        if (rejection.isPresent()) {
            reject(task, List.of(rejection.get()), null);
            return;
        }

        Optional<SuccessCriteria> embedded = criteriaService.embeddedCriteria(issue.body());
        if (embedded.isPresent()) {
            List<String> failures = criteriaService.gate(embedded.get());
            if (failures.isEmpty()) {
                accept(task, embedded.get(), IssueState.DISCOVERED, "criteria supplied in the issue body");
            } else {
                reject(task, failures, null);
            }
            return;
        }

        DevinDtos.SessionDetails session = devin.createSession(
                prompts.scopingPrompt(
                        task.getRepo(),
                        issue.number(),
                        issue.title(),
                        issue.body(),
                        context.profileFor(task.getRepo()),
                        learnings.lessonsFor(task.getRepo())),
                "menD scoping — %s#%d".formatted(task.getRepo(), issue.number()),
                List.of("mend", "criteria", task.getRepo()),
                props.getDevin().getCriteriaAcuLimit(),
                SuccessCriteria.JSON_SCHEMA,
                task.getRepo());
        task.setCriteriaSessionId(session.sessionId());
        task.setCriteriaSessionUrl(session.url());
        task = taskService.save(task);
        metrics.recordAcuBudget(props.getDevin().getCriteriaAcuLimit(), "criteria");
        task = taskService.transition(task, IssueState.CRITERIA_PENDING, "scoping session " + session.sessionId(), ACTOR);
    }

    private void pollCriteriaSession(RemediationTask task) {
        Optional<DevinDtos.SessionDetails> maybe = devin.getSession(task.getCriteriaSessionId());
        if (maybe.isEmpty()) {
            escalate(task, "The scoping session could not be read back from the Devin API.");
            return;
        }
        DevinDtos.SessionDetails session = maybe.get();
        task.setLastPolledAt(clock.instant());
        task = taskService.save(task);

        if (session.isExpired()) {
            reject(task, List.of("The scoping session expired before it could establish criteria."), session.sessionId());
            return;
        }
        if (!session.isFinished() && !session.hasStructuredOutput()) {
            if (olderThan(task.getCriteriaStartedAt(), props.getEngine().getSessionTimeout())) {
                escalate(task, "The scoping session exceeded its time budget.");
            }
            return;
        }
        if (!session.hasStructuredOutput()) {
            reject(task, List.of("The scoping session finished without returning structured criteria."), session.sessionId());
            return;
        }

        SuccessCriteria criteria = criteriaService.parseStructuredOutput(session.structuredOutput());
        List<String> failures = criteriaService.gate(criteria);
        if (failures.isEmpty()) {
            accept(task, criteria, IssueState.CRITERIA_PENDING, "criteria established by scoping session");
            return;
        }
        if (criteria != null) {
            task.setCriteriaJson(criteriaService.toJson(criteria));
            task.setConfidence(criteria.confidence());
            task = taskService.save(task);
        }
        reject(task, failures, task.getCriteriaSessionUrl());
    }

    private void accept(RemediationTask task, SuccessCriteria criteria, IssueState from, String reason) {
        task.setCriteriaJson(criteriaService.toJson(criteria));
        task.setCriteriaHash(criteriaService.hash(criteria));
        task.setConfidence(criteria.confidence());
        task = taskService.save(task);
        task = taskService.transition(task, IssueState.READY, reason, ACTOR);
        notifier.criteriaAccepted(task, criteria);
    }

    private void reject(RemediationTask task, List<String> failures, String sessionRef) {
        task.setExclusionReason(abbreviate(String.join(" | ", failures)));
        task = taskService.save(task);
        task = taskService.transition(task, IssueState.NOT_A_CANDIDATE, task.getExclusionReason(), ACTOR);
        notifier.notACandidate(task, failures, task.getCriteriaSessionUrl() != null ? task.getCriteriaSessionUrl() : sessionRef);
    }

    // ----------------------------------------------------------- remediation

    private void dispatch(RemediationTask task) {
        if (control.paused()) {
            log.debug("dispatch of {} held: menD is paused", task.key());
            return;
        }
        long active = tasks.countByStateIn(EnumSet.of(IssueState.DISPATCHED, IssueState.RUNNING, IssueState.BLOCKED));
        if (active >= props.getEngine().getMaxConcurrentSessions()) {
            log.debug("dispatch of {} deferred: {} sessions already active", task.key(), active);
            return;
        }
        GitHubDtos.Issue issue =
                github.getIssue(task.getRepo(), task.getIssueNumber()).orElse(null);
        if (issue == null) {
            task = taskService.transition(task, IssueState.CANCELLED, "issue no longer accessible", ACTOR);
            return;
        }
        SuccessCriteria criteria = criteriaService.fromJson(task.getCriteriaJson());
        int acu = props.getDevin().getRemediationAcuLimit();

        DevinDtos.SessionDetails session = devin.createSession(
                prompts.remediationPrompt(
                        task.getRepo(),
                        issue.number(),
                        issue.title(),
                        issue.body(),
                        criteria,
                        context.profileFor(task.getRepo()),
                        learnings.lessonsFor(task.getRepo())),
                "menD remediation — %s#%d".formatted(task.getRepo(), issue.number()),
                List.of("mend", "remediation", task.getRepo(), "criteria:" + task.getCriteriaHash()),
                acu,
                RemediationOutcome.JSON_SCHEMA,
                task.getRepo());

        task.setSessionId(session.sessionId());
        task.setSessionUrl(session.url());
        task.setAttempts(task.getAttempts() + 1);
        task.setAcuBudget(acu * task.getAttempts());
        task.setNudges(0);
        task = taskService.save(task);
        learnings.markApplied(task.getRepo());
        metrics.recordAcuBudget(acu, "remediation");
        task = taskService.transition(task, IssueState.DISPATCHED, "session " + session.sessionId(), ACTOR);
        notifier.dispatched(task);
    }

    private void pollRemediationSession(RemediationTask task) {
        Optional<DevinDtos.SessionDetails> maybe = devin.getSession(task.getSessionId());
        if (maybe.isEmpty()) {
            failOrRetry(task, "The remediation session could not be read back from the Devin API.");
            return;
        }
        DevinDtos.SessionDetails session = maybe.get();
        task.setLastPolledAt(clock.instant());
        RemediationOutcome outcome = readOutcome(session);
        if (outcome != null) {
            task.setOutcomeJson(session.structuredOutput().toString());
        }
        task = taskService.save(task);

        String prUrl = session.pullRequestUrl() != null
                ? session.pullRequestUrl()
                : (outcome != null && outcome.prUrl() != null && !outcome.prUrl().isBlank() ? outcome.prUrl() : null);
        if (prUrl != null) {
            task.setPrUrl(prUrl);
            task = taskService.save(task);
            task = taskService.transition(task, IssueState.PR_OPEN, "pull request " + prUrl, ACTOR);
            notifier.prOpened(task);
            return;
        }

        if (session.isExpired()) {
            failOrRetry(task, "The remediation session expired without opening a pull request.");
            return;
        }
        if (session.isFinished()) {
            String why = outcome != null && outcome.blockedReason() != null && !outcome.blockedReason().isBlank()
                    ? outcome.blockedReason()
                    : "The session finished without opening a pull request.";
            failOrRetry(task, why);
            return;
        }
        if (session.isBlocked()) {
            handleBlocked(task);
            return;
        }
        if (task.getState() == IssueState.DISPATCHED || task.getState() == IssueState.BLOCKED) {
            task = taskService.transition(task, IssueState.RUNNING, "session is working", ACTOR);
        }
        if (olderThan(task.getDispatchedAt(), props.getEngine().getSessionTimeout())) {
            failOrRetry(task, "The remediation session exceeded its time budget.");
        }
    }

    private void handleBlocked(RemediationTask task) {
        if (task.getState() != IssueState.BLOCKED) {
            task = taskService.transition(task, IssueState.BLOCKED, "session reported blocked", ACTOR);
        }
        if (!olderThan(
                task.getLastNudgedAt() != null ? task.getLastNudgedAt() : task.getDispatchedAt(),
                props.getEngine().getNudgeAfter())) {
            return;
        }
        if (task.getNudges() >= props.getEngine().getMaxNudges()) {
            escalate(task, "The session stayed blocked after %d nudges.".formatted(task.getNudges()));
            return;
        }
        task.setNudges(task.getNudges() + 1);
        task.setLastNudgedAt(clock.instant());
        task = taskService.save(task);
        devin.sendMessage(task.getSessionId(), prompts.stallNudge(task.getNudges(), props.getEngine().getMaxNudges()));
        log.info("nudged blocked session for {} ({}/{})", task.key(), task.getNudges(), props.getEngine().getMaxNudges());
    }

    // ---------------------------------------------------------- verification

    private void verify(RemediationTask task) {
        Integer pullNumber = GitHubClient.pullNumberFromUrl(task.getPrUrl());
        if (pullNumber == null) {
            escalate(task, "The pull request URL reported by the session could not be parsed: " + task.getPrUrl());
            return;
        }
        Verification verification = verifier.verify(task, readCriteria(task), pullNumber);
        task.setCiStatus(verification.verdict().name());
        task.setVerificationTier(verification.tier());
        task.setVerificationJson(writeJson(verification));
        task = taskService.save(task);

        switch (verification.verdict()) {
            case PASSED -> {
                RemediationOutcome outcome = readOutcomeJson(task.getOutcomeJson());
                if (outcome == null) {
                    settleUnverified(task, verification, null, "verified by " + verification.provenance()
                            + ", but nothing asserted the acceptance criteria: the session returned no readable outcome");
                    return;
                }
                if (!outcome.allCriteriaSatisfied()) {
                    failOrRetry(task, "The session did not assert every acceptance criterion as satisfied.");
                    return;
                }
                task.setLastError(null);
                task = taskService.save(task);
                task = taskService.transition(
                        task, IssueState.SUCCEEDED, "verified by " + verification.provenance(), ACTOR);
                notifier.verification(task, verification);
                notifier.succeeded(task, outcome);
            }
            case FAILED -> {
                if (task.getAttempts() >= props.getEngine().getMaxAttempts()) {
                    escalate(task, "Verification is red and the attempt budget (%d) is exhausted."
                            .formatted(props.getEngine().getMaxAttempts()));
                    return;
                }
                task.setAttempts(task.getAttempts() + 1);
                task = taskService.save(task);
                notifier.verification(task, verification);
                devin.sendMessage(task.getSessionId(), prompts.ciFailureNudge(task.getPrUrl(), verification.summary()));
                task = taskService.transition(task, IssueState.RUNNING, "verification red; session asked to fix it", ACTOR);
            }
            case UNAVAILABLE -> {
                RemediationOutcome outcome = readOutcomeJson(task.getOutcomeJson());
                if (outcome != null && !outcome.allCriteriaSatisfied()) {
                    failOrRetry(task, "The session did not assert every acceptance criterion as satisfied.");
                    return;
                }
                String reason = outcome == null
                        ? verification.summary()
                                + ", and nothing asserted the acceptance criteria: the session returned no readable outcome"
                        : verification.summary();
                settleUnverified(task, verification, outcome, reason);
            }
            case PENDING -> log.debug("verification still pending for {} at tier {}", task.key(), verification.tier());
        }
    }

    /**
     * The honest terminal state: a pull request exists, but either nothing independent proved it or
     * nothing asserted the criteria contract. Not a failure of the attempt, so it is not retried.
     */
    private void settleUnverified(
            RemediationTask task, Verification verification, RemediationOutcome outcome, String reason) {
        task.setLastError(null);
        task = taskService.save(task);
        task = taskService.transition(task, IssueState.UNVERIFIED, reason, ACTOR);
        notifier.unverified(task, verification, outcome);
    }

    private String writeJson(Verification verification) {
        try {
            return mapper.writeValueAsString(verification);
        } catch (JsonProcessingException e) {
            log.warn("could not serialise the verification record", e);
            return null;
        }
    }

    // --------------------------------------------------------------- helpers

    /**
     * A failed attempt is retried by the reconciler as well as inline, because the inline retry can
     * be refused by the concurrency cap — without this the task would sit in a terminal state that
     * nothing drives again.
     */
    private void retryIfBudgetRemains(RemediationTask task) {
        if (task.getAttempts() < props.getEngine().getMaxAttempts()) {
            dispatch(task);
        }
    }

    /** A contract menD cannot read back is treated as absent, which verifies to UNVERIFIED. */
    private SuccessCriteria readCriteria(RemediationTask task) {
        if (task.getCriteriaJson() == null || task.getCriteriaJson().isBlank()) {
            return null;
        }
        try {
            return criteriaService.fromJson(task.getCriteriaJson());
        } catch (RuntimeException e) {
            log.warn("criteria for {} could not be read back: {}", task.key(), e.getMessage());
            return null;
        }
    }

    private void failOrRetry(RemediationTask task, String reason) {
        task.setLastError(abbreviate(reason));
        task = taskService.save(task);
        if (task.getAttempts() < props.getEngine().getMaxAttempts()) {
            task = taskService.transition(task, IssueState.FAILED, reason, ACTOR);
            notifier.failed(task, reason + " Retrying with a fresh session.");
            dispatch(task);
            return;
        }
        task = taskService.transition(task, IssueState.FAILED, reason, ACTOR);
        notifier.failed(task, reason);
    }

    private void escalate(RemediationTask task, String reason) {
        task.setLastError(abbreviate(reason));
        task = taskService.save(task);
        task = taskService.transition(task, IssueState.NEEDS_HUMAN, reason, ACTOR);
        notifier.escalated(task, reason);
    }

    private void recordError(Long taskId, RuntimeException error) {
        tasks.findById(taskId).ifPresent(fresh -> {
            fresh.setLastError(abbreviate(error.getMessage()));
            taskService.save(fresh);
        });
    }

    private RemediationOutcome readOutcome(DevinDtos.SessionDetails session) {
        if (!session.hasStructuredOutput()) {
            return null;
        }
        return readOutcome(session.structuredOutput(), "session " + session.sessionId());
    }

    private RemediationOutcome readOutcomeJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return readOutcome(mapper.readTree(json), "the stored outcome");
        } catch (JsonProcessingException e) {
            log.warn("the stored remediation outcome is not JSON: {}", e.getMessage());
            return null;
        }
    }

    /** An outcome that does not match the schema is treated as absent rather than trusted in part. */
    private RemediationOutcome readOutcome(JsonNode node, String origin) {
        try {
            return mapper.treeToValue(node, RemediationOutcome.class);
        } catch (JsonProcessingException e) {
            log.warn("{} returned an outcome that does not match the schema: {}", origin, e.getMessage());
            return null;
        }
    }

    private boolean olderThan(Instant instant, Duration duration) {
        return instant != null && clock.instant().isAfter(instant.plus(duration));
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= RemediationTask.REASON_LENGTH ? text : text.substring(0, RemediationTask.REASON_LENGTH);
    }
}
