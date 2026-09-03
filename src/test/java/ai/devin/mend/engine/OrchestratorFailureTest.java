package ai.devin.mend.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.devin.mend.devin.DevinApiClient;
import ai.devin.mend.devin.DevinDtos;
import ai.devin.mend.domain.IssueState;
import ai.devin.mend.domain.RemediationTask;
import ai.devin.mend.domain.TaskEventRepository;
import ai.devin.mend.domain.TaskRepository;
import ai.devin.mend.github.GitHubClient;
import ai.devin.mend.github.GitHubDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

/**
 * The unhappy half of the flow: sessions that stall, sessions that die, issues that disappear
 * and attempt budgets that run out. These are the paths that decide whether menD escalates honestly
 * or quietly loses a task.
 */
@SpringBootTest
@TestPropertySource(
        properties = {
            "mend.engine.enabled=false",
            "mend.github.polling-enabled=false",
            "mend.github.repo=acme/superset",
            "mend.engine.max-attempts=2",
            "mend.engine.max-nudges=2",
            "mend.engine.nudge-after=PT0S",
            "mend.engine.session-timeout=PT0S",
            "spring.datasource.url=jdbc:h2:mem:orchestratorfailure;DB_CLOSE_DELAY=-1"
        })
class OrchestratorFailureTest {

    private static final String REPO = "acme/superset";

    private static final String BODY =
            """
            `npm audit` reports a high severity advisory for nth-check reachable from
            superset-frontend/package-lock.json. Bump the transitive dependency so the advisory clears
            without changing application behaviour.
            """;

    @Autowired
    private Orchestrator orchestrator;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private TaskEventRepository events;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private EngineControl control;

    @MockBean
    private DevinApiClient devin;

    @MockBean
    private GitHubClient github;

    @BeforeEach
    void setUp() {
        tasks.deleteAll();
        events.deleteAll();
        control.resume("test");
        when(github.defaultRepo()).thenReturn(REPO);
        when(github.isConfigured()).thenReturn(true);
    }

    @Test
    void anIssueThatDisappearsBeforeTriageIsCancelledRatherThanRetriedForever() {
        RemediationTask task = discover(31);
        when(github.getIssue(REPO, 31)).thenReturn(Optional.empty());

        orchestrator.advance(reload(task));

        assertThat(reload(task).getState()).isEqualTo(IssueState.CANCELLED);
    }

    @Test
    void aScopingSessionTheApiCannotReadBackEscalatesToAHuman() {
        RemediationTask task = toCriteriaPending(32);
        when(devin.getSession("devin-scope-32")).thenReturn(Optional.empty());

        orchestrator.advance(reload(task));

        RemediationTask escalated = reload(task);
        assertThat(escalated.getState()).isEqualTo(IssueState.NEEDS_HUMAN);
        assertThat(escalated.getLastError()).contains("could not be read back");
    }

    @Test
    void aScopingSessionThatExpiresExcludesTheIssueRatherThanSpendingMore() {
        RemediationTask task = toCriteriaPending(33);
        stubExpiredSession("devin-scope-33");

        orchestrator.advance(reload(task));

        assertThat(reload(task).getState()).isEqualTo(IssueState.NOT_A_CANDIDATE);
        assertThat(reload(task).getExclusionReason()).contains("expired");
    }

    @Test
    void aScopingSessionThatOverrunsItsTimeBudgetEscalates() {
        RemediationTask task = toCriteriaPending(34);
        stubSession("devin-scope-34", "working", null, null);

        orchestrator.advance(reload(task));

        assertThat(reload(task).getState()).isEqualTo(IssueState.NEEDS_HUMAN);
        assertThat(reload(task).getLastError()).contains("time budget");
    }

    @Test
    void aBlockedSessionIsNudgedAndThenEscalatedWhenTheNudgesRunOut() {
        RemediationTask task = toDispatched(35);
        stubSession("devin-fix-35", "waiting_for_user", null, null);

        orchestrator.advance(reload(task));
        assertThat(reload(task).getState()).isEqualTo(IssueState.BLOCKED);
        assertThat(reload(task).getNudges()).isEqualTo(1);

        orchestrator.advance(reload(task));
        assertThat(reload(task).getNudges()).isEqualTo(2);

        orchestrator.advance(reload(task));
        RemediationTask escalated = reload(task);
        assertThat(escalated.getState()).isEqualTo(IssueState.NEEDS_HUMAN);
        assertThat(escalated.getLastError()).contains("nudges");
        verify(devin, times(2)).sendMessage(eq("devin-fix-35"), anyString());
    }

    @Test
    void aSessionThatFinishesWithoutAPullRequestRetriesOnceAndThenStops() {
        RemediationTask task = toDispatched(36);
        stubSession("devin-fix-36", "finished", null, blockedOutcome("the reproduction never failed"));
        when(devin.createSession(anyString(), anyString(), anyList(), any(), any(), anyString()))
                .thenReturn(session("devin-fix-36-retry", "working", null, null));

        orchestrator.advance(reload(task));

        RemediationTask retried = reload(task);
        assertThat(retried.getState()).isEqualTo(IssueState.DISPATCHED);
        assertThat(retried.getSessionId()).isEqualTo("devin-fix-36-retry");
        assertThat(retried.getAttempts()).isEqualTo(2);
        assertThat(retried.getLastError()).contains("the reproduction never failed");

        stubSession("devin-fix-36-retry", "finished", null, blockedOutcome("still cannot reproduce"));
        orchestrator.advance(reload(task));

        assertThat(reload(task).getState()).isEqualTo(IssueState.FAILED);
    }

    @Test
    void aRetryTheConcurrencyCapDeferredIsPickedUpAgainRatherThanLost() {
        RemediationTask task = toDispatched(37);
        occupyEverySessionSlot();
        stubSession("devin-fix-37", "finished", null, blockedOutcome("the branch would not build"));
        clearInvocations(devin);

        orchestrator.advance(reload(task));
        assertThat(reload(task).getState()).isEqualTo(IssueState.FAILED);
        verify(devin, never()).createSession(anyString(), anyString(), anyList(), any(), any(), anyString());

        freeEverySessionSlot();
        when(devin.createSession(anyString(), anyString(), anyList(), any(), any(), anyString()))
                .thenReturn(session("devin-fix-37-retry", "working", null, null));
        orchestrator.advance(reload(task));

        RemediationTask retried = reload(task);
        assertThat(retried.getState()).isEqualTo(IssueState.DISPATCHED);
        assertThat(retried.getSessionId()).isEqualTo("devin-fix-37-retry");
    }

    @Test
    void aFailedTaskWithNoAttemptBudgetLeftIsNotRedispatched() {
        RemediationTask task = toDispatched(38);
        task.setAttempts(2);
        task.setState(IssueState.FAILED);
        tasks.saveAndFlush(task);
        clearInvocations(devin);

        orchestrator.advance(reload(task));

        assertThat(reload(task).getState()).isEqualTo(IssueState.FAILED);
        verify(devin, never()).createSession(anyString(), anyString(), anyList(), any(), any(), anyString());
    }

    @Test
    void anIssueThatDisappearsBeforeDispatchIsCancelled() {
        RemediationTask task = toReady(39);
        when(github.getIssue(REPO, 39)).thenReturn(Optional.empty());
        clearInvocations(devin);

        orchestrator.advance(reload(task));

        assertThat(reload(task).getState()).isEqualTo(IssueState.CANCELLED);
        verify(devin, never()).createSession(anyString(), anyString(), anyList(), any(), any(), anyString());
    }

    @Test
    void aPullRequestUrlMenDCannotParseEscalatesRatherThanClaimingAnything() {
        RemediationTask task = discover(40);
        task.setState(IssueState.VERIFYING);
        task.setPrUrl("https://github.com/acme/superset/pulls");
        tasks.saveAndFlush(task);

        orchestrator.advance(reload(task));

        assertThat(reload(task).getState()).isEqualTo(IssueState.NEEDS_HUMAN);
        assertThat(reload(task).getLastError()).contains("could not be parsed");
    }

    @Test
    void greenCiStillDoesNotSucceedWhenTheSessionItselfSaysACriterionIsUnmet() {
        RemediationTask task = discover(41);
        task.setState(IssueState.VERIFYING);
        task.setPrUrl("https://github.com/acme/superset/pull/41");
        task.setSessionId("devin-fix-41");
        task.setCriteriaJson(criteriaJson(0.92, List.of("npm audit reports no high advisory"), List.of("npm audit"))
                .toString());
        task.setOutcomeJson(outcomeJson(false).toString());
        task.setAttempts(2);
        tasks.saveAndFlush(task);
        when(github.checkRuns(REPO, 41))
                .thenReturn(List.of(new GitHubDtos.CheckRun(
                        "frontend-build", "completed", "success", "https://github.com/acme/superset/runs/1")));

        orchestrator.advance(reload(task));

        assertThat(reload(task).getState()).isEqualTo(IssueState.FAILED);
        assertThat(reload(task).getLastError()).contains("every acceptance criterion");
    }

    @Test
    void greenCiWithNoOutcomeAtAllSettlesUnverifiedRatherThanClaimingSuccess() {
        RemediationTask task = verifying(46, null);
        when(github.checkRuns(REPO, 46))
                .thenReturn(List.of(new GitHubDtos.CheckRun(
                        "frontend-build", "completed", "success", "https://github.com/acme/superset/runs/3")));

        orchestrator.advance(reload(task));

        assertThat(reload(task).getState()).isEqualTo(IssueState.UNVERIFIED);
        assertThat(lastTransitionReason(task)).contains("nothing asserted the acceptance criteria");
    }

    @Test
    void aScopingVerdictLongerThanTheColumnIsTrimmedToItsWidthRatherThanFailingTheInsert() {
        RemediationTask task = toCriteriaPending(50);
        ObjectNode criteria =
                (ObjectNode) criteriaJson(0.92, List.of("npm audit reports no high advisory"), List.of("npm audit"));
        criteria.set("blocking_unknowns", mapper.valueToTree(List.of("x".repeat(RemediationTask.REASON_LENGTH + 500))));
        stubSession("devin-scope-50", "finished", null, criteria);

        orchestrator.advance(reload(task));

        assertThat(reload(task).getState()).isEqualTo(IssueState.NOT_A_CANDIDATE);
        assertThat(reload(task).getExclusionReason()).hasSize(RemediationTask.REASON_LENGTH);
    }

    @Test
    void greenCiWithAnUnreadableOutcomeSettlesUnverifiedRatherThanClaimingSuccess() {
        RemediationTask task = verifying(47, "{\"remediated\": tru");
        when(github.checkRuns(REPO, 47))
                .thenReturn(List.of(new GitHubDtos.CheckRun(
                        "frontend-build", "completed", "success", "https://github.com/acme/superset/runs/4")));

        orchestrator.advance(reload(task));

        assertThat(reload(task).getState()).isEqualTo(IssueState.UNVERIFIED);
        assertThat(lastTransitionReason(task)).contains("nothing asserted the acceptance criteria");
    }

    @Test
    void noVerifierAndNoOutcomeSaysSoOnTheUnverifiedTransition() {
        RemediationTask task = verifying(48, null);
        task.setCriteriaJson(null);
        tasks.saveAndFlush(task);
        when(github.checkRuns(REPO, 48)).thenReturn(List.of());

        orchestrator.advance(reload(task));

        assertThat(reload(task).getState()).isEqualTo(IssueState.UNVERIFIED);
        assertThat(lastTransitionReason(task))
                .contains("no independent verifier")
                .contains("nothing asserted the acceptance criteria");
    }

    @Test
    void anExhaustedAttemptBudgetOnRedCiEscalatesInsteadOfNudgingAgain() {
        RemediationTask task = discover(42);
        task.setState(IssueState.VERIFYING);
        task.setPrUrl("https://github.com/acme/superset/pull/42");
        task.setSessionId("devin-fix-42");
        task.setCriteriaJson(criteriaJson(0.92, List.of("npm audit reports no high advisory"), List.of("npm audit"))
                .toString());
        task.setAttempts(2);
        tasks.saveAndFlush(task);
        when(github.checkRuns(REPO, 42))
                .thenReturn(List.of(new GitHubDtos.CheckRun(
                        "frontend-build", "completed", "failure", "https://github.com/acme/superset/runs/2")));

        orchestrator.advance(reload(task));

        assertThat(reload(task).getState()).isEqualTo(IssueState.NEEDS_HUMAN);
        verify(devin, never()).sendMessage(eq("devin-fix-42"), anyString());
    }

    @Test
    void reLabellingATaskMenDAlreadyExcludedDoesNotRestartIt() {
        RemediationTask task = discover(43);
        task.setState(IssueState.NOT_A_CANDIDATE);
        tasks.saveAndFlush(task);

        RemediationTask again = orchestrator.onTriggerLabel(REPO, issue(43, "chore: bump nth-check", BODY, List.of()));

        assertThat(again.getId()).isEqualTo(task.getId());
        assertThat(again.getState()).isEqualTo(IssueState.NOT_A_CANDIDATE);
        assertThat(tasks.count()).isEqualTo(1);
    }

    @Test
    void anUnexpectedFailureIsRecordedOnTheTaskRatherThanKillingTheTick() {
        RemediationTask task = toCriteriaPending(44);
        when(devin.getSession("devin-scope-44")).thenThrow(new IllegalStateException("Devin API returned 503"));

        orchestrator.advance(reload(task));

        RemediationTask after = reload(task);
        assertThat(after.getState()).isEqualTo(IssueState.CRITERIA_PENDING);
        assertThat(after.getLastError()).contains("503");
    }

    @Test
    void aStalledRemediationSessionIsFailedOnceItsTimeBudgetIsSpent() {
        RemediationTask task = toDispatched(45);
        stubSession("devin-fix-45", "working", null, null);
        when(devin.createSession(anyString(), anyString(), anyList(), any(), any(), anyString()))
                .thenReturn(session("devin-fix-45-retry", "working", null, null));

        orchestrator.advance(reload(task));

        assertThat(reload(task).getLastError()).contains("time budget");
        assertThat(events.findByTaskIdOrderByOccurredAtAsc(task.getId()))
                .extracting(event -> event.getToState().name())
                .contains("FAILED");
    }

    // ------------------------------------------------------------- stubbing

    private void occupyEverySessionSlot() {
        for (int i = 0; i < 4; i++) {
            RemediationTask busy = new RemediationTask(
                    REPO, 900 + i, "busy", "https://github.com/acme/superset/issues/" + (900 + i), "");
            busy.setState(IssueState.RUNNING);
            tasks.saveAndFlush(busy);
        }
    }

    private void freeEverySessionSlot() {
        tasks.findAll().stream()
                .filter(candidate -> candidate.getIssueNumber() >= 900)
                .forEach(candidate -> {
                    candidate.setState(IssueState.CANCELLED);
                    tasks.saveAndFlush(candidate);
                });
    }

    private RemediationTask discover(int number) {
        stubIssue(number, "chore: bump nth-check", BODY, List.of());
        return orchestrator.onTriggerLabel(REPO, issue(number, "chore: bump nth-check", BODY, List.of()));
    }

    private RemediationTask toCriteriaPending(int number) {
        RemediationTask task = discover(number);
        when(devin.createSession(anyString(), anyString(), anyList(), any(), any(), anyString()))
                .thenReturn(session("devin-scope-" + number, "working", null, null));
        orchestrator.advance(reload(task));
        assertThat(reload(task).getState()).isEqualTo(IssueState.CRITERIA_PENDING);
        return reload(task);
    }

    private RemediationTask toReady(int number) {
        RemediationTask task = toCriteriaPending(number);
        stubSession(
                "devin-scope-" + number,
                "finished",
                null,
                criteriaJson(0.92, List.of("npm audit reports no high advisory"), List.of("npm audit")));
        orchestrator.advance(reload(task));
        assertThat(reload(task).getState()).isEqualTo(IssueState.READY);
        return reload(task);
    }

    @Test
    void aSessionThatDiesWhilePausedIsNotHandedAFreshOneUntilTheOperatorResumes() {
        RemediationTask task = toDispatched(46);
        stubSession("devin-fix-46", "finished", null, blockedOutcome("the branch would not build"));
        control.pause("operator", "the demo is over");
        clearInvocations(devin);

        orchestrator.advance(reload(task));

        assertThat(reload(task).getState()).isEqualTo(IssueState.FAILED);
        verify(devin, never()).createSession(anyString(), anyString(), anyList(), any(), any(), anyString());

        control.resume("operator");
        when(devin.createSession(anyString(), anyString(), anyList(), any(), any(), anyString()))
                .thenReturn(session("devin-fix-46-retry", "working", null, null));
        orchestrator.advance(reload(task));

        RemediationTask retried = reload(task);
        assertThat(retried.getState()).isEqualTo(IssueState.DISPATCHED);
        assertThat(retried.getSessionId()).isEqualTo("devin-fix-46-retry");
    }

    @Test
    void noVerifierSessionIsStartedWhilePausedAndTheTaskWaitsInVerifyingRatherThanSettlingUnverified() {
        RemediationTask task = verifying(47, outcomeJson(true).toString());
        when(github.checkRuns(REPO, 47)).thenReturn(List.of());
        when(devin.isConfigured()).thenReturn(true);
        control.pause("operator", "the demo is over");
        clearInvocations(devin);

        orchestrator.advance(reload(task));

        assertThat(reload(task).getState()).isEqualTo(IssueState.VERIFYING);
        assertThat(reload(task).getVerifierSessionId()).isNull();
        verify(devin, never()).createSession(anyString(), anyString(), anyList(), any(), any(), anyString());

        control.resume("operator");
        when(devin.createSession(anyString(), anyString(), anyList(), any(), any(), anyString()))
                .thenReturn(session("devin-verify-47", "working", null, null));
        orchestrator.advance(reload(task));

        assertThat(reload(task).getVerifierSessionId()).isEqualTo("devin-verify-47");
    }

    private RemediationTask toDispatched(int number) {
        RemediationTask task = toReady(number);
        when(devin.createSession(anyString(), anyString(), anyList(), any(), any(), anyString()))
                .thenReturn(session("devin-fix-" + number, "working", null, null));
        orchestrator.advance(reload(task));
        assertThat(reload(task).getState()).isEqualTo(IssueState.DISPATCHED);
        // the dispatch timestamp is what the session timeout is measured from
        RemediationTask dispatched = reload(task);
        dispatched.setDispatchedAt(Instant.now().minusSeconds(60));
        return tasks.saveAndFlush(dispatched);
    }

    /** A task parked in {@code VERIFYING} with an open pull request and the given stored outcome. */
    private RemediationTask verifying(int number, String outcomeJson) {
        RemediationTask task = discover(number);
        task.setState(IssueState.VERIFYING);
        task.setPrUrl("https://github.com/acme/superset/pull/" + number);
        task.setSessionId("devin-fix-" + number);
        task.setCriteriaJson(criteriaJson(0.92, List.of("npm audit reports no high advisory"), List.of("npm audit"))
                .toString());
        task.setOutcomeJson(outcomeJson);
        task.setAttempts(1);
        return tasks.saveAndFlush(task);
    }

    private String lastTransitionReason(RemediationTask task) {
        List<ai.devin.mend.domain.TaskEvent> recorded = events.findByTaskIdOrderByOccurredAtAsc(task.getId());
        return recorded.get(recorded.size() - 1).getReason();
    }

    private RemediationTask reload(RemediationTask task) {
        return tasks.findById(task.getId()).orElseThrow();
    }

    private GitHubDtos.Issue issue(int number, String title, String body, List<String> labels) {
        return new GitHubDtos.Issue(
                number,
                title,
                body,
                "open",
                "https://github.com/acme/superset/issues/" + number,
                labels.stream().map(GitHubDtos.Label::new).toList(),
                null,
                null,
                null);
    }

    private void stubIssue(int number, String title, String body, List<String> labels) {
        when(github.getIssue(REPO, number)).thenReturn(Optional.of(issue(number, title, body, labels)));
    }

    private void stubSession(String sessionId, String statusDetail, String prUrl, JsonNode structuredOutput) {
        when(devin.getSession(sessionId))
                .thenReturn(Optional.of(session(sessionId, statusDetail, prUrl, structuredOutput)));
    }

    /** The Devin API reports an expired session as {@code status=error}, not as a status detail. */
    private void stubExpiredSession(String sessionId) {
        when(devin.getSession(sessionId))
                .thenReturn(Optional.of(new DevinDtos.SessionDetails(
                        sessionId,
                        "https://app.devin.ai/sessions/" + sessionId,
                        "error",
                        "expired",
                        "title",
                        List.of(),
                        null,
                        List.of(),
                        0.0,
                        null,
                        null)));
    }

    private DevinDtos.SessionDetails session(
            String sessionId, String statusDetail, String prUrl, JsonNode structuredOutput) {
        return new DevinDtos.SessionDetails(
                sessionId,
                "https://app.devin.ai/sessions/" + sessionId,
                "running",
                statusDetail,
                "title",
                List.of(),
                structuredOutput,
                prUrl == null ? List.of() : List.of(new DevinDtos.PullRequestInfo(prUrl, "open")),
                0.0,
                null,
                null);
    }

    private JsonNode criteriaJson(double confidence, List<String> acceptance, List<String> commands) {
        var node = mapper.createObjectNode();
        node.put("is_candidate", true);
        node.put("confidence", confidence);
        node.put("problem_restatement", "bump the vulnerable transitive dependency");
        node.set("acceptance_criteria", mapper.valueToTree(acceptance));
        node.set("verification_commands", mapper.valueToTree(commands));
        node.set("files_in_scope", mapper.valueToTree(List.of("superset-frontend/package-lock.json")));
        node.put("test_plan", "no test change: lockfile pin, proven by the existing npm audit check");
        node.put("risk", "low");
        node.set("blocking_unknowns", mapper.valueToTree(List.of()));
        node.put("rationale", "isolated lockfile change");
        return node;
    }

    private JsonNode outcomeJson(boolean satisfied) {
        var node = mapper.createObjectNode();
        node.put("remediated", true);
        node.put("pr_url", "");
        node.put("summary", "bumped the dependency");
        node.set("files_changed", mapper.valueToTree(List.of("superset-frontend/package-lock.json")));
        var result = mapper.createObjectNode();
        result.put("criterion", "npm audit reports no high advisory");
        result.put("satisfied", satisfied);
        result.put("evidence", "npm audit --audit-level=high exits 0");
        node.set("criteria_results", mapper.createArrayNode().add(result));
        node.set("tests_changed", mapper.valueToTree(List.of()));
        node.put("test_evidence", "lockfile pin; npm audit is the existing check that proves it");
        node.set("commands_run", mapper.valueToTree(List.of("npm audit --audit-level=high")));
        node.put("confidence", 0.9);
        node.put("blocked_reason", "");
        return node;
    }

    private JsonNode blockedOutcome(String reason) {
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) outcomeJson(true);
        node.put("remediated", false);
        node.put("blocked_reason", reason);
        return node;
    }
}
