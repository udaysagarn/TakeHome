package ai.devin.mend.sandbox;

import ai.devin.mend.config.MendProperties;
import ai.devin.mend.github.GitHubClient;
import ai.devin.mend.github.GitHubCredentials;
import ai.devin.mend.github.GitHubDtos;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Serves the real {@link GitHubClient} surface from {@link SandboxHub} instead of the network. */
@Component
@Profile("sandbox")
public class SandboxGitHubClient extends GitHubClient {

    private final SandboxHub hub;
    private final MendProperties props;

    public SandboxGitHubClient(
            RestClient.Builder builder, MendProperties props, GitHubCredentials credentials, SandboxHub hub) {
        super(builder, props, credentials);
        this.hub = hub;
        this.props = props;
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public List<GitHubDtos.Issue> listIssuesWithLabel(String repo, String label) {
        return hub.issuesWithLabel(repo, label);
    }

    @Override
    public Optional<GitHubDtos.Issue> getIssue(String repo, int number) {
        return hub.issue(repo, number);
    }

    @Override
    public Optional<GitHubDtos.Repo> getRepo(String repo) {
        return Optional.of(new GitHubDtos.Repo(
                1L,
                repo.substring(repo.indexOf('/') + 1),
                repo,
                "https://github.com/" + repo,
                "master",
                false,
                false,
                "public",
                true,
                "TypeScript"));
    }

    @Override
    public List<GitHubDtos.Repo> installationRepos() {
        return List.of(getRepo(props.getGithub().getRepo()).orElseThrow());
    }

    @Override
    public Map<String, String> installationPermissions() {
        return Map.of("issues", "write", "pull_requests", "write", "contents", "read", "checks", "read");
    }

    @Override
    public Optional<String> branchHeadSha(String repo, String branch) {
        return Optional.of("0f1e2d3c4b5a");
    }

    @Override
    public void comment(String repo, int issueNumber, String body) {
        hub.comment(repo, issueNumber, body);
    }

    @Override
    public void addLabels(String repo, int issueNumber, List<String> labels) {
        hub.addLabels(repo, issueNumber, labels);
    }

    @Override
    public void removeLabel(String repo, int issueNumber, String label) {
        hub.removeLabel(repo, issueNumber, label);
    }

    @Override
    public void ensureLabel(String repo, String label, String color, String description) {
        // labels exist by virtue of being applied in the sandbox
    }

    @Override
    public Optional<GitHubDtos.PullRequest> getPullRequest(String repo, int number) {
        return hub.pull(number);
    }

    @Override
    public List<GitHubDtos.Review> listReviews(String repo, int pullNumber) {
        return hub.reviews(pullNumber);
    }

    @Override
    public List<GitHubDtos.ReviewComment> listReviewComments(String repo, int pullNumber) {
        return List.of();
    }

    @Override
    public List<GitHubDtos.PrFile> listPullRequestFiles(String repo, int pullNumber) {
        return List.of(new GitHubDtos.PrFile("package-lock.json", "modified", 12, 12, 24));
    }

    /** No contract workflow is merged into the simulated repository, so this tier cannot answer. */
    @Override
    public boolean dispatchWorkflow(String repo, String workflowFile, String ref, Map<String, String> inputs) {
        return false;
    }

    @Override
    public List<GitHubDtos.CheckRun> checkRuns(String repo, int pullNumber) {
        return hub.checkRuns(pullNumber);
    }

    @Override
    public GitHubDtos.CiVerdict ciVerdict(String repo, int pullNumber) {
        List<GitHubDtos.CheckRun> runs = hub.checkRuns(pullNumber);
        if (runs.isEmpty()) {
            return GitHubDtos.CiVerdict.NONE;
        }
        return runs.stream().allMatch(r -> "success".equals(r.conclusion()))
                ? GitHubDtos.CiVerdict.PASSED
                : GitHubDtos.CiVerdict.FAILED;
    }
}
