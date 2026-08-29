package ai.devin.d1.devin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;

/** Wire types for the Devin v1 session API, whose payloads are snake_case. */
public final class DevinDtos {

    private DevinDtos() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CreateSessionRequest(
            String prompt,
            String title,
            List<String> tags,
            Boolean idempotent,
            Integer maxAcuLimit,
            JsonNode structuredOutputSchema,
            Boolean unlisted,
            String playbookId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CreateSessionResponse(String sessionId, String url, Boolean isNewSession) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PullRequestInfo(String url) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SessionDetails(
            String sessionId,
            String status,
            String statusEnum,
            String title,
            List<String> tags,
            JsonNode structuredOutput,
            PullRequestInfo pullRequest,
            Instant createdAt,
            Instant updatedAt) {

        /** Devin reports {@code finished}, {@code blocked}, {@code working}, {@code expired}, ... */
        public boolean isWorking() {
            return "working".equals(statusEnum);
        }

        public boolean isBlocked() {
            return "blocked".equals(statusEnum);
        }

        public boolean isFinished() {
            return "finished".equals(statusEnum);
        }

        public boolean isExpired() {
            return "expired".equals(statusEnum);
        }

        public String pullRequestUrl() {
            return pullRequest == null ? null : pullRequest.url();
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SendMessageRequest(String message) {}
}
