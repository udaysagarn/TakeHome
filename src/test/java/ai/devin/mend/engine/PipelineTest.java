package ai.devin.mend.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.devin.mend.devin.DevinApiClient;
import ai.devin.mend.devin.DevinDtos;
import ai.devin.mend.domain.IssueState;
import ai.devin.mend.domain.RemediationTask;
import ai.devin.mend.domain.TaskEventRepository;
import ai.devin.mend.domain.TaskRepository;
import ai.devin.mend.domain.Verification;
import ai.devin.mend.github.GitHubClient;
import ai.devin.mend.github.GitHubDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

/**
 * Exercises the pipeline end to end against a mocked Devin API and a mocked GitHub, which is the only
 * way to assert the interesting property: an issue reaches a remediation session only after the
 * criteria gate has passed.
 */
@SpringBootTest
@TestPropertySource(
        properties = {
            "mend.engine.enabled=false",
            "mend.github.polling-enabled=false",
            "mend.github.repo=acme/superset",
            "spring.datasource.url=jdbc:h2:mem:pipeline;DB_CLOSE_DELAY=-1"
        })
class PipelineTest {

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

    @MockBean
    private DevinApiClient devin;

    @MockBean
    private GitHubClient github;

    @BeforeEach
    void setUp() {
        tasks.deleteAll();
        events.deleteAll();
        when(github.defaultRepo()).thenReturn(REPO);
        when(github.isConfigured()).thenReturn(true);
    }

    @Test
    void anIssueWithoutAVerifiableDefinitionOfDoneNeverReachesADevinSession() {
        stubIssue(1, "Test Bug", "Some bug", List.of());

        RemediationTask task = orchestrator.onTriggerLabel(REPO, issue(1, "Test Bug", "Some bug", List.of()));
        orchestrator.advance(reload(task));

        assertThat(reload(task).getState()).isEqualTo(IssueState.NOT_A_CANDIDATE);
        assertThat(reload(task).getExclusionReason()).isNotBlank();
        verify(devin, never()).createSession(anyString(), anyString(), anyList(), anyInt(), anyString(), anyString());
        verify(github).addLabels(eq(REPO), eq(1), anyList());
    }

    @Test
    void aScopingSessionThatRefusesTheIssueExcludesIt() {
        stubIssue(2, "Redesign the chart picker", BODY, List.of());
        stubCreateSession("devin-scope");
        stubSession("devin-scope", "finished", null, criteriaJson(false, 0.9, List.of(), List.of()));

        RemediationTask task =
                orchestrator.onTriggerLabel(REPO, issue(2, "Redesign the chart picker", BODY, List.of()));
        orchestrator.advance(reload(task));
        assertThat(reload(task).getState()).isEqualTo(IssueState.CRITERIA_PENDING);

        orchestrator.advance(reload(task));
        assertThat(reload(task).getState()).isEqualTo(IssueState.NOT_A_CANDIDATE);
        verify(devin).createSession(anyString(), anyString(), anyList(), anyInt(), anyString(), anyString());
    }

    @Test
    void aVerifiableIssueRunsThroughToSucceededOnceCiIsGreen() {
        stubIssue(3, "chore: bump nth-check", BODY, List.of());
        stubCreateSession("devin-scope");
        stubSession(
                "devin-scope",
                "finished",
                null,
                criteriaJson(true, 0.92, List.of("npm audit reports no high advisory"),
                        List.of("npm audit --audit-level=high")));

        RemediationTask task = orchestrator.onTriggerLabel(REPO, issue(3, "chore: bump nth-check", BODY, List.of()));
        orchestrator.advance(reload(task)); // DISCOVERED -> CRITERIA_PENDING
        orchestrator.advance(reload(task)); // criteria accepted -> READY
        assertThat(reload(task).getState()).isEqualTo(IssueState.READY);
        assertThat(reload(task).getCriteriaHash()).isNotBlank();

        stubCreateSession("devin-fix");
        orchestrator.advance(reload(task)); // READY -> DISPATCHED
        assertThat(reload(task).getState()).isEqualTo(IssueState.DISPATCHED);

        stubSession("devin-fix", "finished", "https://github.com/acme/superset/pull/9", outcomeJson(true));
        orchestrator.advance(reload(task)); // -> PR_OPEN
        assertThat(reload(task).getState()).isEqualTo(IssueState.PR_OPEN);
        assertThat(reload(task).getPrUrl()).endsWith("/pull/9");

        orchestrator.advance(reload(task)); // -> VERIFYING
        when(github.ciVerdict(REPO, 9)).thenReturn(GitHubDtos.CiVerdict.PASSED);
        orchestrator.advance(reload(task)); // -> SUCCEEDED

        RemediationTask done = reload(task);
        assertThat(done.getState()).isEqualTo(IssueState.SUCCEEDED);
        assertThat(done.getCompletedAt()).isNotNull();
        assertThat(events.findByTaskIdOrderByOccurredAtAsc(done.getId()))
                .extracting(e -> e.getToState().name())
                .containsExactly("CRITERIA_PENDING", "READY", "DISPATCHED", "PR_OPEN", "VERIFYING", "SUCCEEDED");
    }

    @Test
    void redCiSendsTheSessionBackToWorkRatherThanClaimingSuccess() {
        stubIssue(4, "chore: bump nth-check", BODY, List.of());
        stubCreateSession("devin-scope");
        stubSession(
                "devin-scope",
                "finished",
                null,
                criteriaJson(true, 0.92, List.of("audit clean"), List.of("npm audit")));
        RemediationTask task = orchestrator.onTriggerLabel(REPO, issue(4, "chore: bump nth-check", BODY, List.of()));
        orchestrator.advance(reload(task));
        orchestrator.advance(reload(task));
        stubCreateSession("devin-fix");
        orchestrator.advance(reload(task));
        stubSession("devin-fix", "finished", "https://github.com/acme/superset/pull/10", outcomeJson(true));
        orchestrator.advance(reload(task));
        orchestrator.advance(reload(task)); // -> VERIFYING

        when(github.ciVerdict(REPO, 10)).thenReturn(GitHubDtos.CiVerdict.FAILED);
        orchestrator.advance(reload(task));

        assertThat(reload(task).getState()).isEqualTo(IssueState.RUNNING);
        verify(devin).sendMessage(eq("devin-fix"), anyString());
    }

    @Test
    void aPullRequestNothingCanProveLandsUnverifiedRatherThanSucceeded() {
        RemediationTask task = runToVerifying(11, 11);

        when(github.checkRuns(REPO, 11)).thenReturn(List.of());
        when(github.ciVerdict(REPO, 11)).thenReturn(GitHubDtos.CiVerdict.NONE);
        when(devin.isConfigured()).thenReturn(false);
        orchestrator.advance(reload(task));

        RemediationTask done = reload(task);
        assertThat(done.getState()).isEqualTo(IssueState.UNVERIFIED);
        assertThat(done.getVerificationTier()).isEqualTo(Verification.Tier.NONE);
        assertThat(done.getCompletedAt()).isNotNull();
    }

    @Test
    void aSeparateVerifierSessionCanProveTheFixWhenTheRepositoryHasNoCi() {
        RemediationTask task = runToVerifying(12, 12);

        when(github.checkRuns(REPO, 12)).thenReturn(List.of());
        when(github.ciVerdict(REPO, 12)).thenReturn(GitHubDtos.CiVerdict.NONE);
        when(devin.isConfigured()).thenReturn(true);
        stubCreateSession("devin-verify");
        orchestrator.advance(reload(task));

        assertThat(reload(task).getState()).isEqualTo(IssueState.VERIFYING);
        assertThat(reload(task).getVerifierSessionId()).isEqualTo("devin-verify");

        stubSession("devin-verify", "finished", null, verifierJson(0));
        orchestrator.advance(reload(task));

        RemediationTask done = reload(task);
        assertThat(done.getState()).isEqualTo(IssueState.SUCCEEDED);
        assertThat(done.getVerificationTier()).isEqualTo(Verification.Tier.VERIFIER_SESSION);
        assertThat(done.getVerificationJson()).contains("npm audit");
    }

    @Test
    void aVerifierSessionThatSeesARedCommandDoesNotCountAsSuccess() {
        RemediationTask task = runToVerifying(13, 13);

        when(github.checkRuns(REPO, 13)).thenReturn(List.of());
        when(github.ciVerdict(REPO, 13)).thenReturn(GitHubDtos.CiVerdict.NONE);
        when(devin.isConfigured()).thenReturn(true);
        stubCreateSession("devin-verify");
        orchestrator.advance(reload(task));
        stubSession("devin-verify", "finished", null, verifierJson(1));
        orchestrator.advance(reload(task));

        assertThat(reload(task).getState()).isEqualTo(IssueState.RUNNING);
    }

    @Test
    void theRepositorysOwnChecksOutrankEveryOtherTier() {
        RemediationTask task = runToVerifying(14, 14);

        when(github.checkRuns(REPO, 14))
                .thenReturn(List.of(new GitHubDtos.CheckRun(
                        "frontend-build", "completed", "success", "https://github.com/acme/superset/runs/1")));
        orchestrator.advance(reload(task));

        RemediationTask done = reload(task);
        assertThat(done.getState()).isEqualTo(IssueState.SUCCEEDED);
        assertThat(done.getVerificationTier()).isEqualTo(Verification.Tier.REPO_CI);
        verify(devin, never()).createSession(anyString(), anyString(), anyList(), any(), eq(Verification.JSON_SCHEMA), anyString());
    }

    // ------------------------------------------------------------- stubbing

    /** Drives an issue through the gate and a remediation session until it is waiting on a verdict. */
    private RemediationTask runToVerifying(int issueNumber, int pullNumber) {
        stubIssue(issueNumber, "chore: bump nth-check", BODY, List.of());
        stubCreateSession("devin-scope-" + issueNumber);
        stubSession(
                "devin-scope-" + issueNumber,
                "finished",
                null,
                criteriaJson(true, 0.92, List.of("npm audit reports no high advisory"),
                        List.of("npm audit --audit-level=high")));
        RemediationTask task = orchestrator.onTriggerLabel(
                REPO, issue(issueNumber, "chore: bump nth-check", BODY, List.of()));
        orchestrator.advance(reload(task));
        orchestrator.advance(reload(task));
        stubCreateSession("devin-fix-" + issueNumber);
        orchestrator.advance(reload(task));
        stubSession(
                "devin-fix-" + issueNumber,
                "finished",
                "https://github.com/acme/superset/pull/" + pullNumber,
                outcomeJson(true));
        orchestrator.advance(reload(task));
        orchestrator.advance(reload(task));
        assertThat(reload(task).getState()).isEqualTo(IssueState.VERIFYING);
        return task;
    }

    private JsonNode verifierJson(int exitCode) {
        var node = mapper.createObjectNode();
        node.put("all_passed", exitCode == 0);
        node.put("summary", "ran the agreed command at the pull request head");
        var command = mapper.createObjectNode();
        command.put("command", "npm audit --audit-level=high");
        command.put("exit_code", exitCode);
        command.put("output", "found 0 high severity vulnerabilities");
        node.set("commands", mapper.createArrayNode().add(command));
        return node;
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

    private void stubCreateSession(String sessionId) {
        when(devin.createSession(anyString(), anyString(), anyList(), any(), any(), anyString()))
                .thenReturn(session(sessionId, "working", null, null));
    }

    private void stubSession(String sessionId, String statusDetail, String prUrl, JsonNode structuredOutput) {
        when(devin.getSession(sessionId)).thenReturn(Optional.of(session(sessionId, statusDetail, prUrl, structuredOutput)));
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

    private JsonNode criteriaJson(
            boolean candidate, double confidence, List<String> acceptance, List<String> commands) {
        var node = mapper.createObjectNode();
        node.put("is_candidate", candidate);
        node.put("confidence", confidence);
        node.put("problem_restatement", "bump the vulnerable transitive dependency");
        node.set("acceptance_criteria", mapper.valueToTree(acceptance));
        node.set("verification_commands", mapper.valueToTree(commands));
        node.set("files_in_scope", mapper.valueToTree(List.of("superset-frontend/package-lock.json")));
        node.put("test_plan", "no test change: lockfile pin, proven by the existing npm audit check");
        node.put("risk", "low");
        node.set("blocking_unknowns", mapper.valueToTree(List.of()));
        node.put("rationale", candidate ? "isolated lockfile change" : "requires a product decision");
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
}
