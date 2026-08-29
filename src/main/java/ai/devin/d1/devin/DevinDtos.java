package ai.devin.d1.devin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/** Wire types for the Devin v3 session API, whose payloads are snake_case. */
public final class DevinDtos {

    private DevinDtos() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CreateSessionRequest(
            String prompt,
            String title,
            List<String> tags,
            Integer maxAcuLimit,
            JsonNode structuredOutputSchema,
            Boolean structuredOutputRequired,
            List<String> repos,
            String playbookId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PullRequestInfo(String prUrl, String prState) {}

    /**
     * A v3 session. Liveness is reported as a coarse {@code status} plus a finer {@code
     * status_detail}; the predicates below collapse that pair into the four outcomes the
     * orchestrator distinguishes.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SessionDetails(
            String sessionId,
            String url,
            String status,
            String statusDetail,
            String title,
            List<String> tags,
            JsonNode structuredOutput,
            List<PullRequestInfo> pullRequests,
            Double acusConsumed,
            Long createdAt,
            Long updatedAt) {

        public boolean isWorking() {
            return "running".equals(status) && !isBlocked() && !isFinished();
        }

        /** Waiting on a human, or parked and not coming back on its own. */
        public boolean isBlocked() {
            return "waiting_for_user".equals(statusDetail) || "suspended".equals(status);
        }

        public boolean isFinished() {
            return "exit".equals(status) || "finished".equals(statusDetail);
        }

        public boolean isExpired() {
            return "error".equals(status);
        }

        public String pullRequestUrl() {
            if (pullRequests == null || pullRequests.isEmpty()) {
                return null;
            }
            return pullRequests.get(0).prUrl();
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SendMessageRequest(String message) {}
}
