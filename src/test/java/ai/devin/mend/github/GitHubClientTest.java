package ai.devin.mend.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ai.devin.mend.config.MendProperties;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The GitHub edge is where menD's assumptions meet someone else's API, so the assertions are about
 * the awkward cases: a repository it cannot see, a workflow it may not dispatch, a label that is
 * already there, and CI that has not finished yet.
 */
class GitHubClientTest {

    private static final String REPO = "acme/superset";

    private MockRestServiceServer server;
    private GitHubClient github;

    @BeforeEach
    void setUp() {
        MendProperties props = new MendProperties();
        props.getGithub().setApiUrl("https://api.github.test");
        props.getGithub().setToken("t0ken");
        props.getGithub().setRepo(REPO);

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        github = new GitHubClient(builder, props, new GitHubCredentials(RestClient.builder(), props));
    }

    @Test
    void aTokenIsEnoughToBeConfigured() {
        assertThat(github.isConfigured()).isTrue();
        assertThat(github.defaultRepo()).isEqualTo(REPO);
        assertThat(github.installationPermissions()).isEmpty();
    }

    @Test
    void theOwnerAndNameAreSeparatePathSegmentsRatherThanOneEncodedSlug() {
        expect("https://api.github.test/repos/acme/superset/issues/7", HttpMethod.GET)
                .andRespond(withSuccess(
                        """
                        {"number":7,"title":"chore: bump nth-check","body":"b","state":"open",
                         "html_url":"https://github.com/acme/superset/issues/7","labels":[{"name":"menD:fix"}]}
                        """,
                        MediaType.APPLICATION_JSON));

        Optional<GitHubDtos.Issue> issue = github.getIssue(REPO, 7);

        assertThat(issue).isPresent();
        assertThat(issue.get().labelNames()).containsExactly("menD:fix");
        server.verify();
    }

    @Test
    void pullRequestsAreNotIssuesEvenThoughGitHubReturnsThemOnTheIssuesRoute() {
        expect(
                        "https://api.github.test/repos/acme/superset/issues?labels=menD:fix&state=open&per_page=100",
                        HttpMethod.GET)
                .andRespond(withSuccess(
                        """
                        [{"number":7,"title":"an issue","state":"open"},
                         {"number":8,"title":"a pull request","state":"open",
                          "pull_request":{"html_url":"https://github.com/acme/superset/pull/8"}}]
                        """,
                        MediaType.APPLICATION_JSON));

        List<GitHubDtos.Issue> issues = github.listIssuesWithLabel(REPO, "menD:fix");

        assertThat(issues).extracting(GitHubDtos.Issue::number).containsExactly(7);
        server.verify();
    }

    @Test
    void aRepositoryMenDCannotSeeIsEmptyRatherThanAnError() {
        expect("https://api.github.test/repos/acme/secret", HttpMethod.GET)
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThat(github.getRepo("acme/secret")).isEmpty();
        server.verify();
    }

    @Test
    void aMissingIssueIsEmptyRatherThanAnError() {
        expect("https://api.github.test/repos/acme/superset/issues/404", HttpMethod.GET)
                .andRespond(withResourceNotFound());

        assertThat(github.getIssue(REPO, 404)).isEmpty();
        server.verify();
    }

    @Test
    void labelsArePostedAsAListAndAMissingLabelRemovalIsNotAFailure() {
        expect("https://api.github.test/repos/acme/superset/issues/7/labels", HttpMethod.POST)
                .andExpect(jsonPath("$.labels[0]").value("menD:in-progress"))
                .andRespond(withSuccess());
        expect("https://api.github.test/repos/acme/superset/issues/7/labels/menD%3Afix", HttpMethod.DELETE)
                .andRespond(withResourceNotFound());

        github.addLabels(REPO, 7, List.of("menD:in-progress"));
        github.removeLabel(REPO, 7, "menD:fix");

        server.verify();
    }

    @Test
    void anExistingLabelIsNotAnError() {
        expect("https://api.github.test/repos/acme/superset/labels", HttpMethod.POST)
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));

        github.ensureLabel(REPO, "menD:fix", "5b3df5", "menD works this issue");

        server.verify();
    }

    @Test
    void aWorkflowThatCannotBeDispatchedIsReportedRatherThanThrown() {
        expect(
                        "https://api.github.test/repos/acme/superset/actions/workflows/mend-verify.yml/dispatches",
                        HttpMethod.POST)
                .andRespond(withResourceNotFound());

        assertThat(github.dispatchWorkflow(REPO, "mend-verify.yml", "main", Map.of("pr", "7")))
                .isFalse();
        server.verify();
    }

    @Test
    void aDispatchedWorkflowCarriesTheRefAndInputs() {
        expect(
                        "https://api.github.test/repos/acme/superset/actions/workflows/mend-verify.yml/dispatches",
                        HttpMethod.POST)
                .andExpect(jsonPath("$.ref").value("master"))
                .andExpect(jsonPath("$.inputs.pr").value("7"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        assertThat(github.dispatchWorkflow(REPO, "mend-verify.yml", "master", Map.of("pr", "7")))
                .isTrue();
        server.verify();
    }

    @Test
    void checkRunsAreReadFromTheHeadCommitOfThePullRequest() {
        stubPullRequest();
        stubCheckRuns(
                """
                {"total_count":1,"check_runs":[
                  {"name":"menD / contract","status":"completed","conclusion":"success",
                   "html_url":"https://github.com/acme/superset/runs/1"}]}
                """);

        assertThat(github.checkRuns(REPO, 7))
                .singleElement()
                .satisfies(run -> assertThat(run.name()).isEqualTo("menD / contract"));
        server.verify();
    }

    @Test
    void aPullRequestNumberIsReadOffItsUrl() {
        assertThat(GitHubClient.pullNumberFromUrl("https://github.com/acme/superset/pull/42"))
                .isEqualTo(42);
        assertThat(GitHubClient.pullNumberFromUrl("https://github.com/acme/superset/pull/"))
                .isNull();
        assertThat(GitHubClient.pullNumberFromUrl("not-a-url")).isNull();
        assertThat(GitHubClient.pullNumberFromUrl(null)).isNull();
        assertThat(GitHubClient.pullNumberFromUrl("  ")).isNull();
    }

    @Test
    void branchHeadShaIsEmptyWhenTheBranchIsGone() {
        expect("https://api.github.test/repos/acme/superset/commits/master", HttpMethod.GET)
                .andRespond(withResourceNotFound());

        assertThat(github.branchHeadSha(REPO, "master")).isEmpty();
        server.verify();
    }

    @Test
    void installationRepositoriesAreEmptyOnAToken() {
        expect("https://api.github.test/installation/repositories?per_page=100", HttpMethod.GET)
                .andRespond(withResourceNotFound());

        assertThat(github.installationRepos()).isEmpty();
        server.verify();
    }

    @Test
    void reviewsAndReviewCommentsAndFilesTolerateAnEmptyBody() {
        expect("https://api.github.test/repos/acme/superset/pulls/7/reviews", HttpMethod.GET)
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        expect("https://api.github.test/repos/acme/superset/pulls/7/comments", HttpMethod.GET)
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        expect("https://api.github.test/repos/acme/superset/pulls/7/files", HttpMethod.GET)
                .andRespond(withSuccess(
                        """
                        [{"filename":"superset-frontend/package-lock.json","status":"modified",
                          "additions":4,"deletions":4,"changes":8}]
                        """,
                        MediaType.APPLICATION_JSON));

        assertThat(github.listReviews(REPO, 7)).isEmpty();
        assertThat(github.listReviewComments(REPO, 7)).isEmpty();
        assertThat(github.listPullRequestFiles(REPO, 7))
                .singleElement()
                .satisfies(file -> assertThat(file.filename()).endsWith("package-lock.json"));
        server.verify();
    }

    @Test
    void commentsCanBeTurnedOffWithoutTouchingGitHub() {
        MendProperties props = new MendProperties();
        props.getGithub().setApiUrl("https://api.github.test");
        props.getGithub().setToken("t0ken");
        props.getGithub().setCommentsEnabled(false);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer quiet = MockRestServiceServer.bindTo(builder).build();
        GitHubClient silent = new GitHubClient(builder, props, new GitHubCredentials(RestClient.builder(), props));

        silent.comment(REPO, 7, "this should never leave the process");

        quiet.verify(); // no request was expected, and none was made
    }

    private org.springframework.test.web.client.ResponseActions expect(String uri, HttpMethod httpMethod) {
        return server.expect(requestTo(uri)).andExpect(method(httpMethod));
    }

    private void stubPullRequest() {
        expect("https://api.github.test/repos/acme/superset/pulls/7", HttpMethod.GET)
                .andRespond(withSuccess(
                        """
                        {"number":7,"html_url":"https://github.com/acme/superset/pull/7","state":"open",
                         "merged":false,"head":{"sha":"deadbeef","ref":"devin/fix"}}
                        """,
                        MediaType.APPLICATION_JSON));
    }

    private void stubCheckRuns(String body) {
        expect("https://api.github.test/repos/acme/superset/commits/deadbeef/check-runs", HttpMethod.GET)
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }
}
