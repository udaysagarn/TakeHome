package ai.devin.mend.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import ai.devin.mend.github.GitHubDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SandboxHubTest {

    private SandboxHub hub;

    @BeforeEach
    void setUp() {
        hub = new SandboxHub();
    }

    @Test
    void filedIssueCarriesTheTriggerLabelAndIsReadableBack() {
        GitHubDtos.Issue issue = hub.fileIssue("o/r", SandboxScenario.CLEAN_FIX, "menD:fix");

        assertThat(hub.issuesWithLabel("o/r", "menD:fix")).extracting(GitHubDtos.Issue::number).contains(issue.number());
        assertThat(hub.issue("o/r", issue.number())).isPresent();
        assertThat(hub.issuesWithLabel("other/repo", "menD:fix")).isEmpty();
    }

    @Test
    void labelWritesAreVisibleOnTheNextRead() {
        GitHubDtos.Issue issue = hub.fileIssue("o/r", SandboxScenario.CLEAN_FIX, "menD:fix");

        hub.addLabels("o/r", issue.number(), java.util.List.of("menD:in-progress"));
        hub.removeLabel("o/r", issue.number(), "menD:fix");

        assertThat(hub.issue("o/r", issue.number()).orElseThrow().labels())
                .extracting(GitHubDtos.Label::name)
                .containsExactly("menD:in-progress");
    }

    @Test
    void aCleanFixGetsAPassingCheckSoRepoCiCanProveIt() {
        GitHubDtos.Issue issue = hub.fileIssue("o/r", SandboxScenario.CLEAN_FIX, "menD:fix");

        GitHubDtos.PullRequest pull = hub.openPullRequest("o/r", issue.number());

        assertThat(hub.checkRuns(pull.number())).extracting(GitHubDtos.CheckRun::conclusion).containsExactly("success");
        assertThat(hub.reviews(pull.number())).isEmpty();
    }

    @Test
    void theUnverifiableScenarioNeverProducesACheck() {
        GitHubDtos.Issue issue = hub.fileIssue("o/r", SandboxScenario.UNVERIFIED, "menD:fix");

        GitHubDtos.PullRequest pull = hub.openPullRequest("o/r", issue.number());

        assertThat(hub.checkRuns(pull.number())).isEmpty();
    }

    @Test
    void theReviewScenarioIsRejectedBeforeCiAndOnlyPassesAfterRework() {
        GitHubDtos.Issue issue = hub.fileIssue("o/r", SandboxScenario.REVIEW_THEN_FIX, "menD:fix");

        GitHubDtos.PullRequest pull = hub.openPullRequest("o/r", issue.number());
        assertThat(hub.reviews(pull.number())).extracting(GitHubDtos.Review::state).containsExactly("CHANGES_REQUESTED");
        assertThat(hub.checkRuns(pull.number())).isEmpty();

        hub.completeRework("o/r", pull.number());

        assertThat(hub.checkRuns(pull.number())).hasSize(1);
    }

    @Test
    void openingTheSamePullRequestTwiceReturnsTheSameOne() {
        GitHubDtos.Issue issue = hub.fileIssue("o/r", SandboxScenario.CLEAN_FIX, "menD:fix");

        assertThat(hub.openPullRequest("o/r", issue.number()).number())
                .isEqualTo(hub.openPullRequest("o/r", issue.number()).number());
    }

    @Test
    void aReviewOnAPullRequestThatDoesNotExistIsRefused() {
        assertThat(hub.requestChanges(4242, "someone", "please")).isEmpty();
    }
}
