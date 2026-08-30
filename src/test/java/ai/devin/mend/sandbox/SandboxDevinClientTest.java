package ai.devin.mend.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import ai.devin.mend.config.MendProperties;
import ai.devin.mend.devin.DevinDtos;
import ai.devin.mend.github.GitHubDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class SandboxDevinClientTest {

    private SandboxHub hub;
    private SandboxDevinClient client;

    @BeforeEach
    void setUp() {
        hub = new SandboxHub();
        client = new SandboxDevinClient(RestClient.builder(), new ObjectMapper(), new MendProperties(), hub);
    }

    @Test
    void aSessionIsStillWorkingOnTheFirstPoll() {
        GitHubDtos.Issue issue = hub.fileIssue("o/r", SandboxScenario.CLEAN_FIX, "menD:fix");
        String id = criteriaSession(issue.number()).sessionId();

        DevinDtos.SessionDetails first = client.getSession(id).orElseThrow();

        assertThat(first.isWorking()).isTrue();
        assertThat(first.hasStructuredOutput()).isFalse();
    }

    @Test
    void scopingAcceptsABoundedIssueWithVerificationCommands() {
        GitHubDtos.Issue issue = hub.fileIssue("o/r", SandboxScenario.CLEAN_FIX, "menD:fix");
        String id = criteriaSession(issue.number()).sessionId();

        DevinDtos.SessionDetails done = answer(id);

        assertThat(done.structuredOutput().get("is_candidate").asBoolean()).isTrue();
        assertThat(done.structuredOutput().get("confidence").asDouble()).isGreaterThan(0.7);
        assertThat(done.structuredOutput().get("verification_commands")).isNotEmpty();
    }

    @Test
    void scopingDeclinesAnIssueThatNeedsAHumanDecision() {
        GitHubDtos.Issue issue = hub.fileIssue("o/r", SandboxScenario.NOT_A_CANDIDATE, "menD:fix");
        String id = criteriaSession(issue.number()).sessionId();

        DevinDtos.SessionDetails done = answer(id);

        assertThat(done.structuredOutput().get("is_candidate").asBoolean()).isFalse();
        assertThat(done.structuredOutput().get("blocking_unknowns")).isNotEmpty();
    }

    @Test
    void remediationOpensAPullRequestAndReportsItsUrl() {
        GitHubDtos.Issue issue = hub.fileIssue("o/r", SandboxScenario.CLEAN_FIX, "menD:fix");
        String id = remediationSession(issue.number()).sessionId();

        DevinDtos.SessionDetails done = answer(id);

        String prUrl = done.structuredOutput().get("pr_url").asText();
        assertThat(prUrl).startsWith("https://github.com/o/r/pull/");
        assertThat(hub.pull(Integer.parseInt(prUrl.substring(prUrl.lastIndexOf('/') + 1)))).isPresent();
    }

    @Test
    void feedbackSendsTheSessionBackToWorkAndTheReworkClearsCi() {
        GitHubDtos.Issue issue = hub.fileIssue("o/r", SandboxScenario.REVIEW_THEN_FIX, "menD:fix");
        String id = remediationSession(issue.number()).sessionId();
        String prUrl = answer(id).structuredOutput().get("pr_url").asText();
        int pull = Integer.parseInt(prUrl.substring(prUrl.lastIndexOf('/') + 1));
        assertThat(hub.checkRuns(pull)).isEmpty();

        client.sendMessage(id, "the reviewer wants a spec");

        assertThat(client.getSession(id).orElseThrow().isWorking()).isTrue();
        answer(id);
        assertThat(hub.checkRuns(pull)).hasSize(1);
    }

    @Test
    void theVerifierReportsNothingForTheUnverifiableScenario() {
        GitHubDtos.Issue issue = hub.fileIssue("o/r", SandboxScenario.UNVERIFIED, "menD:fix");
        String id = client.createSession(
                        "prompt",
                        "menD verification — o/r#" + issue.number(),
                        List.of("mend", "mend-verify", "o/r"),
                        3,
                        null,
                        "o/r")
                .sessionId();

        DevinDtos.SessionDetails done = answer(id);

        assertThat(done.isFinished()).isTrue();
        assertThat(done.hasStructuredOutput()).isFalse();
    }

    @Test
    void anUnknownSessionIsNotInvented() {
        assertThat(client.getSession("sandbox-nope-1")).isEmpty();
    }

    private DevinDtos.SessionDetails criteriaSession(int issueNumber) {
        return client.createSession(
                "prompt",
                "menD scoping — o/r#" + issueNumber,
                List.of("mend", "criteria", "o/r"),
                3,
                null,
                "o/r");
    }

    private DevinDtos.SessionDetails remediationSession(int issueNumber) {
        return client.createSession(
                "prompt",
                "menD remediation — o/r#" + issueNumber,
                List.of("mend", "remediation", "o/r"),
                10,
                null,
                "o/r");
    }

    /** Polls until the scripted session answers, the way the reconciler would. */
    private DevinDtos.SessionDetails answer(String sessionId) {
        DevinDtos.SessionDetails details = client.getSession(sessionId).orElseThrow();
        for (int i = 0; i < 5 && !details.hasStructuredOutput() && !details.isFinished(); i++) {
            details = client.getSession(sessionId).orElseThrow();
        }
        return details;
    }
}
