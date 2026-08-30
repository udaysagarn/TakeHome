package ai.devin.mend.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;

/** Wire types for the small slice of the GitHub REST API the control plane uses; payloads are snake_case. */
public final class GitHubDtos {

    private GitHubDtos() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Label(String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Issue(
            int number,
            String title,
            String body,
            String state,
            String htmlUrl,
            List<Label> labels,
            PullRequestRef pullRequest,
            Instant createdAt,
            Instant updatedAt) {

        public boolean isPullRequest() {
            return pullRequest != null;
        }

        public List<String> labelNames() {
            return labels == null ? List.of() : labels.stream().map(Label::name).toList();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PullRequestRef(String htmlUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PullRequest(int number, String htmlUrl, String state, boolean merged, Head head) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Head(String sha, String ref) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CheckRun(String name, String status, String conclusion, String htmlUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CheckRuns(int totalCount, List<CheckRun> checkRuns) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CombinedStatus(String state, int totalCount) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Repo(
            long id,
            String name,
            String fullName,
            String htmlUrl,
            String defaultBranch,
            boolean archived,
            boolean disabled,
            String visibility,
            boolean hasIssues,
            String language) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record InstallationRepos(int totalCount, List<Repo> repositories) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Ref(String sha) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record User(String login, String type) {

        public boolean isBot() {
            return "Bot".equals(type);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Review(long id, User user, String state, String body, String htmlUrl, Instant submittedAt) {

        public boolean isRejection() {
            return "CHANGES_REQUESTED".equalsIgnoreCase(state) || "DISMISSED".equalsIgnoreCase(state);
        }

        public boolean isApproval() {
            return "APPROVED".equalsIgnoreCase(state);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ReviewComment(
            long id, User user, String body, String path, Integer line, String htmlUrl, Instant createdAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PrFile(String filename, String status, int additions, int deletions, int changes) {}

    /** Aggregate CI verdict for a pull request. */
    public enum CiVerdict {
        PENDING,
        PASSED,
        FAILED,
        NONE
    }
}
