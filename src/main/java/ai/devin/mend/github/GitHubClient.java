package ai.devin.mend.github;

import ai.devin.mend.config.MendProperties;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** Reads issues and writes the human-visible outputs: comments, labels, and CI verdicts. */
@Component
public class GitHubClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);

    private final RestClient http;
    private final MendProperties props;
    private final GitHubCredentials credentials;

    public GitHubClient(RestClient.Builder builder, MendProperties props, GitHubCredentials credentials) {
        this.props = props;
        this.credentials = credentials;
        this.http = builder
                .baseUrl(props.getGithub().getApiUrl())
                .requestInitializer(request ->
                        request.getHeaders().setBearerAuth(credentials.bearerToken()))
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public boolean isConfigured() {
        return credentials.isConfigured();
    }

    /** The repository menD watches when the registry is empty. */
    public String defaultRepo() {
        return props.getGithub().getRepo();
    }

    /**
     * {@code owner} and {@code name} are expanded as separate template variables: a single
     * {@code {repo}} variable holding {@code owner/name} is percent-encoded to {@code owner%2Fname},
     * which GitHub answers with 404.
     */
    private static String owner(String repo) {
        return repo.substring(0, repo.indexOf('/'));
    }

    private static String name(String repo) {
        return repo.substring(repo.indexOf('/') + 1);
    }

    /** Open issues carrying the trigger label. Pull requests are filtered out. */
    public List<GitHubDtos.Issue> listIssuesWithLabel(String repo, String label) {
        GitHubDtos.Issue[] issues = http.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/repos/{owner}/{name}/issues")
                        .queryParam("labels", label)
                        .queryParam("state", "open")
                        .queryParam("per_page", 100)
                        .build(owner(repo), name(repo)))
                .retrieve()
                .body(GitHubDtos.Issue[].class);
        return issues == null
                ? List.of()
                : java.util.Arrays.stream(issues).filter(i -> !i.isPullRequest()).toList();
    }

    public Optional<GitHubDtos.Issue> getIssue(String repo, int number) {
        try {
            return Optional.ofNullable(http.get()
                    .uri("/repos/{owner}/{name}/issues/{n}", owner(repo), name(repo), number)
                    .retrieve()
                    .body(GitHubDtos.Issue.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    /** Repository metadata; empty when it does not exist or menD's identity cannot see it. */
    public Optional<GitHubDtos.Repo> getRepo(String repo) {
        try {
            return Optional.ofNullable(http.get()
                    .uri("/repos/{owner}/{name}", owner(repo), name(repo))
                    .retrieve()
                    .body(GitHubDtos.Repo.class));
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Forbidden e) {
            return Optional.empty();
        }
    }

    /** Repositories the current App installation can see; empty when running on a token. */
    public List<GitHubDtos.Repo> installationRepos() {
        try {
            GitHubDtos.InstallationRepos body = http.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/installation/repositories")
                            .queryParam("per_page", 100)
                            .build())
                    .retrieve()
                    .body(GitHubDtos.InstallationRepos.class);
            return body == null || body.repositories() == null ? List.of() : body.repositories();
        } catch (HttpClientErrorException e) {
            log.debug("installation repositories unavailable: {}", e.getStatusCode());
            return List.of();
        }
    }

    /** Permissions GitHub reports for the credentials in use; empty on a personal access token. */
    public Map<String, String> installationPermissions() {
        return credentials.installationPermissions();
    }

    /** Head commit of a branch, used to stamp a codebase profile with the sha it describes. */
    public Optional<String> branchHeadSha(String repo, String branch) {
        try {
            GitHubDtos.Ref ref = http.get()
                    .uri("/repos/{owner}/{name}/commits/{branch}", owner(repo), name(repo), branch)
                    .retrieve()
                    .body(GitHubDtos.Ref.class);
            return Optional.ofNullable(ref).map(GitHubDtos.Ref::sha);
        } catch (HttpClientErrorException e) {
            return Optional.empty();
        }
    }

    public void comment(String repo, int issueNumber, String body) {
        if (!props.getGithub().isCommentsEnabled()) {
            log.info("comments disabled; would have commented on {}#{}: {}", repo, issueNumber, abbreviate(body));
            return;
        }
        http.post()
                .uri("/repos/{owner}/{name}/issues/{n}/comments", owner(repo), name(repo), issueNumber)
                .body(Map.of("body", body))
                .retrieve()
                .toBodilessEntity();
    }

    public void addLabels(String repo, int issueNumber, List<String> labels) {
        http.post()
                .uri("/repos/{owner}/{name}/issues/{n}/labels", owner(repo), name(repo), issueNumber)
                .body(Map.of("labels", labels))
                .retrieve()
                .toBodilessEntity();
    }

    public void removeLabel(String repo, int issueNumber, String label) {
        try {
            http.delete()
                    .uri(
                            "/repos/{owner}/{name}/issues/{n}/labels/{label}",
                            owner(repo),
                            name(repo),
                            issueNumber,
                            label)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound e) {
            log.debug("label {} not present on {}#{}", label, repo, issueNumber);
        }
    }

    /** Ensures a label exists so the flow never fails on a fresh repository. */
    public void ensureLabel(String repo, String label, String color, String description) {
        try {
            http.post()
                    .uri("/repos/{owner}/{name}/labels", owner(repo), name(repo))
                    .body(Map.of("name", label, "color", color, "description", description))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            log.debug("label {} already exists or could not be created: {}", label, e.getStatusCode());
        }
    }

    public Optional<GitHubDtos.PullRequest> getPullRequest(String repo, int number) {
        try {
            return Optional.ofNullable(http.get()
                    .uri("/repos/{owner}/{name}/pulls/{n}", owner(repo), name(repo), number)
                    .retrieve()
                    .body(GitHubDtos.PullRequest.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    /** Review verdicts on a pull request, oldest first. */
    public List<GitHubDtos.Review> listReviews(String repo, int pullNumber) {
        GitHubDtos.Review[] reviews = http.get()
                .uri("/repos/{owner}/{name}/pulls/{n}/reviews", owner(repo), name(repo), pullNumber)
                .retrieve()
                .body(GitHubDtos.Review[].class);
        return reviews == null ? List.of() : List.of(reviews);
    }

    /** Inline review comments left on a pull request's diff. */
    public List<GitHubDtos.ReviewComment> listReviewComments(String repo, int pullNumber) {
        GitHubDtos.ReviewComment[] comments = http.get()
                .uri("/repos/{owner}/{name}/pulls/{n}/comments", owner(repo), name(repo), pullNumber)
                .retrieve()
                .body(GitHubDtos.ReviewComment[].class);
        return comments == null ? List.of() : List.of(comments);
    }

    /** Files a pull request touches, used to check the diff stayed inside the agreed scope. */
    public List<GitHubDtos.PrFile> listPullRequestFiles(String repo, int pullNumber) {
        GitHubDtos.PrFile[] files = http.get()
                .uri("/repos/{owner}/{name}/pulls/{n}/files", owner(repo), name(repo), pullNumber)
                .retrieve()
                .body(GitHubDtos.PrFile[].class);
        return files == null ? List.of() : List.of(files);
    }

    /** Starts a {@code workflow_dispatch} run; false when the workflow or permission is absent. */
    public boolean dispatchWorkflow(String repo, String workflowFile, String ref, Map<String, String> inputs) {
        try {
            http.post()
                    .uri(
                            "/repos/{owner}/{name}/actions/workflows/{workflow}/dispatches",
                            owner(repo),
                            name(repo),
                            workflowFile)
                    .body(Map.of("ref", ref, "inputs", inputs))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (HttpClientErrorException e) {
            log.info("workflow {} not dispatchable on {}: {}", workflowFile, repo, e.getStatusCode());
            return false;
        }
    }

    /** Check runs reported against the head commit of a pull request, newest run per check. */
    public List<GitHubDtos.CheckRun> checkRuns(String repo, int pullNumber) {
        Optional<GitHubDtos.PullRequest> pr = getPullRequest(repo, pullNumber);
        if (pr.isEmpty() || pr.get().head() == null) {
            return List.of();
        }
        GitHubDtos.CheckRuns runs = http.get()
                .uri("/repos/{owner}/{name}/commits/{sha}/check-runs", owner(repo), name(repo), pr.get()
                        .head()
                        .sha())
                .retrieve()
                .body(GitHubDtos.CheckRuns.class);
        return runs == null || runs.checkRuns() == null ? List.of() : runs.checkRuns();
    }

    /**
     * Aggregate CI verdict for the head commit of a pull request, combining check runs and legacy
     * commit statuses.
     */
    public GitHubDtos.CiVerdict ciVerdict(String repo, int pullNumber) {
        Optional<GitHubDtos.PullRequest> pr = getPullRequest(repo, pullNumber);
        if (pr.isEmpty() || pr.get().head() == null) {
            return GitHubDtos.CiVerdict.NONE;
        }
        String sha = pr.get().head().sha();
        GitHubDtos.CheckRuns runs = http.get()
                .uri("/repos/{owner}/{name}/commits/{sha}/check-runs", owner(repo), name(repo), sha)
                .retrieve()
                .body(GitHubDtos.CheckRuns.class);
        GitHubDtos.CombinedStatus status = http.get()
                .uri("/repos/{owner}/{name}/commits/{sha}/status", owner(repo), name(repo), sha)
                .retrieve()
                .body(GitHubDtos.CombinedStatus.class);

        boolean anyPending = false;
        boolean anyFailed = false;
        boolean any = false;
        if (runs != null && runs.checkRuns() != null) {
            for (GitHubDtos.CheckRun run : runs.checkRuns()) {
                any = true;
                if (!"completed".equals(run.status())) {
                    anyPending = true;
                } else if (!List.of("success", "neutral", "skipped").contains(String.valueOf(run.conclusion()))) {
                    anyFailed = true;
                }
            }
        }
        if (status != null && status.totalCount() > 0) {
            any = true;
            switch (status.state()) {
                case "pending" -> anyPending = true;
                case "failure", "error" -> anyFailed = true;
                default -> {}
            }
        }
        if (!any) {
            return GitHubDtos.CiVerdict.NONE;
        }
        if (anyFailed) {
            return GitHubDtos.CiVerdict.FAILED;
        }
        return anyPending ? GitHubDtos.CiVerdict.PENDING : GitHubDtos.CiVerdict.PASSED;
    }

    public static Integer pullNumberFromUrl(String prUrl) {
        if (prUrl == null || prUrl.isBlank()) {
            return null;
        }
        int idx = prUrl.lastIndexOf('/');
        if (idx < 0 || idx == prUrl.length() - 1) {
            return null;
        }
        try {
            return Integer.parseInt(prUrl.substring(idx + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }
}
