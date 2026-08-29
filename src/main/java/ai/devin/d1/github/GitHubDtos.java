package ai.devin.d1.github;

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

    /** Aggregate CI verdict for a pull request. */
    public enum CiVerdict {
        PENDING,
        PASSED,
        FAILED,
        NONE
    }
}
