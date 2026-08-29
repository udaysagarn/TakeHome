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

    public String repo() {
        return props.getGithub().getRepo();
    }

    /** Open issues carrying the trigger label. Pull requests are filtered out. */
    public List<GitHubDtos.Issue> listIssuesWithLabel(String label) {
        GitHubDtos.Issue[] issues = http.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/repos/{repo}/issues")
                        .queryParam("labels", label)
                        .queryParam("state", "open")
                        .queryParam("per_page", 100)
                        .build(repo()))
                .retrieve()
                .body(GitHubDtos.Issue[].class);
        return issues == null
                ? List.of()
                : java.util.Arrays.stream(issues).filter(i -> !i.isPullRequest()).toList();
    }

    public Optional<GitHubDtos.Issue> getIssue(int number) {
        try {
            return Optional.ofNullable(http.get()
                    .uri("/repos/{repo}/issues/{n}", repo(), number)
                    .retrieve()
                    .body(GitHubDtos.Issue.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    public void comment(int issueNumber, String body) {
        if (!props.getGithub().isCommentsEnabled()) {
            log.info("comments disabled; would have commented on #{}: {}", issueNumber, abbreviate(body));
            return;
        }
        http.post()
                .uri("/repos/{repo}/issues/{n}/comments", repo(), issueNumber)
                .body(Map.of("body", body))
                .retrieve()
                .toBodilessEntity();
    }

    public void addLabels(int issueNumber, List<String> labels) {
        http.post()
                .uri("/repos/{repo}/issues/{n}/labels", repo(), issueNumber)
                .body(Map.of("labels", labels))
                .retrieve()
                .toBodilessEntity();
    }

    public void removeLabel(int issueNumber, String label) {
        try {
            http.delete()
                    .uri("/repos/{repo}/issues/{n}/labels/{label}", repo(), issueNumber, label)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound e) {
            log.debug("label {} not present on #{}", label, issueNumber);
        }
    }

    /** Ensures a label exists so the pipeline never fails on a fresh repository. */
    public void ensureLabel(String name, String color, String description) {
        try {
            http.post()
                    .uri("/repos/{repo}/labels", repo())
                    .body(Map.of("name", name, "color", color, "description", description))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            log.debug("label {} already exists or could not be created: {}", name, e.getStatusCode());
        }
    }

    public Optional<GitHubDtos.PullRequest> getPullRequest(int number) {
        try {
            return Optional.ofNullable(http.get()
                    .uri("/repos/{repo}/pulls/{n}", repo(), number)
                    .retrieve()
                    .body(GitHubDtos.PullRequest.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    /**
     * Aggregate CI verdict for the head commit of a pull request, combining check runs and legacy
     * commit statuses.
     */
    public GitHubDtos.CiVerdict ciVerdict(int pullNumber) {
        Optional<GitHubDtos.PullRequest> pr = getPullRequest(pullNumber);
        if (pr.isEmpty() || pr.get().head() == null) {
            return GitHubDtos.CiVerdict.NONE;
        }
        String sha = pr.get().head().sha();
        GitHubDtos.CheckRuns runs = http.get()
                .uri("/repos/{repo}/commits/{sha}/check-runs", repo(), sha)
                .retrieve()
                .body(GitHubDtos.CheckRuns.class);
        GitHubDtos.CombinedStatus status = http.get()
                .uri("/repos/{repo}/commits/{sha}/status", repo(), sha)
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
