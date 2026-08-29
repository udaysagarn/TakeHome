package ai.devin.mend.engine;

import ai.devin.mend.config.MendProperties;
import ai.devin.mend.devin.DevinApiClient;
import ai.devin.mend.devin.DevinDtos;
import ai.devin.mend.domain.IssueState;
import ai.devin.mend.domain.RemediationOutcome;
import ai.devin.mend.domain.RemediationTask;
import ai.devin.mend.domain.SuccessCriteria;
import ai.devin.mend.domain.TaskRepository;
import ai.devin.mend.github.GitHubClient;
import ai.devin.mend.github.GitHubDtos;
import ai.devin.mend.metrics.MendMetrics;
import ai.devin.mend.triage.PreFilter;
import ai.devin.mend.triage.SuccessCriteriaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final TaskRepository tasks;
    private final TaskService taskService;
    private final DevinApiClient devin;
    private final GitHubClient github;
    private final PreFilter preFilter;
    private final SuccessCriteriaService criteriaService;
    private final PromptBuilder prompts;
    private final Notifier notifier;
    private final MendMetrics metrics;
    private final ObjectMapper mapper;
    private final MendProperties props;

    public Orchestrator(
            TaskRepository tasks,
            TaskService taskService,
            DevinApiClient devin,
            GitHubClient github,
            PreFilter preFilter,
            SuccessCriteriaService criteriaService,
            PromptBuilder prompts,
            Notifier notifier,
            MendMetrics metrics,
            ObjectMapper mapper,
            MendProperties props) {
        this.tasks = tasks;
        this.taskService = taskService;
        this.devin = devin;
        this.github = github;
        this.preFilter = preFilter;
        this.criteriaService = criteriaService;
        this.prompts = prompts;
        this.notifier = notifier;
        this.metrics = metrics;
        this.mapper = mapper;
        this.props = props;
    }

    /** Entry point for every trigger: webhook, poller or manual dashboard action. */
    public RemediationTask onTriggerLabel(GitHubDtos.Issue issue) {
        String repo = github.repo();
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
        try {
            switch (task.getState()) {
                case DISCOVERED -> triage(task);
                case CRITERIA_PENDING -> pollCriteriaSession(task);
                case READY -> dispatch(task);
                case DISPATCHED, RUNNING, BLOCKED -> pollRemediationSession(task);
                case PR_OPEN -> taskService.transition(task, IssueState.VERIFYING, "waiting for CI", ACTOR);
                case VERIFYING -> verify(task);
                default -> {}
            }
        } catch (RuntimeException e) {
            log.error("advance failed for {} in state {}", task.key(), task.getState(), e);
            recordError(task.getId(), e);
        }
    }

    // ---------------------------------------------------------------- triage

    private void triage(RemediationTask task) {
        GitHubDtos.Issue issue = github.getIssue(task.getIssueNumber()).orElse(null);
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
                prompts.scopingPrompt(task.getRepo(), issue.number(), issue.title(), issue.body()),
                "menD scoping — %s#%d".formatted(task.getRepo(), issue.number()),
                List.of("mend", "criteria", task.getRepo()),
                props.getDevin().getCriteriaAcuLimit(),
                SuccessCriteria.JSON_SCHEMA);
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
        task.setLastPolledAt(Instant.now());
        task = taskService.save(task);

        if (session.isExpired()) {
            reject(task, List.of("The scoping session expired before it could establish criteria."), session.sessionId());
            return;
        }
        if (!session.isFinished() && session.structuredOutput() == null) {
            if (olderThan(task.getCriteriaStartedAt(), props.getEngine().getSessionTimeout())) {
                escalate(task, "The scoping session exceeded its time budget.");
            }
            return;
        }
        if (session.structuredOutput() == null) {
            reject(task, List.of("The scoping session finished without returning structured criteria."), session.sessionId());
            return;
        }

        SuccessCriteria criteria = criteriaService.parseStructuredOutput(session.structuredOutput());
        List<String> failures = criteriaService.gate(criteria);
        if (failures.isEmpty()) {
            accept(task, criteria, IssueState.CRITERIA_PENDING, "criteria established by scoping session");
        } else {
            task.setCriteriaJson(criteriaService.toJson(criteria));
            task.setConfidence(criteria.confidence());
            task = taskService.save(task);
            reject(task, failures, task.getCriteriaSessionUrl());
        }
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
        long active = tasks.countByStateIn(EnumSet.of(IssueState.DISPATCHED, IssueState.RUNNING, IssueState.BLOCKED));
        if (active >= props.getEngine().getMaxConcurrentSessions()) {
            log.debug("dispatch of {} deferred: {} sessions already active", task.key(), active);
            return;
        }
        GitHubDtos.Issue issue = github.getIssue(task.getIssueNumber()).orElse(null);
        if (issue == null) {
            task = taskService.transition(task, IssueState.CANCELLED, "issue no longer accessible", ACTOR);
            return;
        }
        SuccessCriteria criteria = criteriaService.fromJson(task.getCriteriaJson());
        int acu = props.getDevin().getRemediationAcuLimit();

        DevinDtos.SessionDetails session = devin.createSession(
                prompts.remediationPrompt(task.getRepo(), issue.number(), issue.title(), issue.body(), criteria),
                "menD remediation — %s#%d".formatted(task.getRepo(), issue.number()),
                List.of("mend", "remediation", task.getRepo(), "criteria:" + task.getCriteriaHash()),
                acu,
                RemediationOutcome.JSON_SCHEMA);

        task.setSessionId(session.sessionId());
        task.setSessionUrl(session.url());
        task.setAttempts(task.getAttempts() + 1);
        task.setAcuBudget(acu * task.getAttempts());
        task.setNudges(0);
        task = taskService.save(task);
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
        task.setLastPolledAt(Instant.now());
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
        task.setLastNudgedAt(Instant.now());
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
        GitHubDtos.CiVerdict verdict = github.ciVerdict(pullNumber);
        task.setCiStatus(verdict.name());
        task = taskService.save(task);

        switch (verdict) {
            case PASSED, NONE -> {
                RemediationOutcome outcome = readOutcomeJson(task.getOutcomeJson());
                if (outcome != null && !outcome.allCriteriaSatisfied()) {
                    failOrRetry(task, "The session did not assert every acceptance criterion as satisfied.");
                    return;
                }
                task = taskService.transition(
                        task,
                        IssueState.SUCCEEDED,
                        verdict == GitHubDtos.CiVerdict.PASSED ? "CI green" : "no CI configured; criteria asserted",
                        ACTOR);
                notifier.succeeded(task, outcome);
            }
            case FAILED -> {
                if (task.getAttempts() >= props.getEngine().getMaxAttempts()) {
                    escalate(task, "CI is red and the attempt budget (%d) is exhausted."
                            .formatted(props.getEngine().getMaxAttempts()));
                    return;
                }
                task.setAttempts(task.getAttempts() + 1);
                task = taskService.save(task);
                devin.sendMessage(
                        task.getSessionId(),
                        prompts.ciFailureNudge(task.getPrUrl(), "The check runs on the head commit are failing."));
                task = taskService.transition(task, IssueState.RUNNING, "CI red; session asked to fix it", ACTOR);
            }
            case PENDING -> log.debug("CI still running for {}", task.key());
        }
    }

    // --------------------------------------------------------------- helpers

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
        if (session.structuredOutput() == null || session.structuredOutput().isNull()) {
            return null;
        }
        try {
            return mapper.convertValue(session.structuredOutput(), RemediationOutcome.class);
        } catch (IllegalArgumentException e) {
            log.warn("session {} returned structured output that does not match the schema: {}",
                    session.sessionId(), e.getMessage());
            return null;
        }
    }

    private RemediationOutcome readOutcomeJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, RemediationOutcome.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean olderThan(Instant instant, Duration duration) {
        return instant != null && Instant.now().isAfter(instant.plus(duration));
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 2000 ? text : text.substring(0, 2000);
    }
}
