package ai.devin.mend.devin;

import ai.devin.mend.config.MendProperties;
import ai.devin.mend.exception.DevinApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Thin, retrying client for the Devin v3 session API.
 *
 * <p>Only three calls are needed to use Devin as an execution primitive: create a session, read its
 * status and structured output, and send it a message when it stalls or CI disagrees with it.
 *
 * <p>v3 has no {@code idempotent} flag; menD gets idempotency from the unique {@code (repo,
 * issue_number)} task instead, so an issue that is already dispatched never reaches this client.
 */
@Component
public class DevinApiClient {

    private static final Logger log = LoggerFactory.getLogger(DevinApiClient.class);
    private static final int MAX_RETRIES = 3;

    private final RestClient http;
    private final ObjectMapper mapper;
    private final MendProperties props;
    private final DevinCredentialMonitor credentials;

    public DevinApiClient(
            RestClient.Builder builder,
            ObjectMapper mapper,
            MendProperties props,
            DevinCredentialMonitor credentials) {
        this.mapper = mapper;
        this.props = props;
        this.credentials = credentials;
        this.http = builder
                .baseUrl(props.getDevin().getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getDevin().getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public boolean isConfigured() {
        return props.getDevin().getApiKey() != null
                && !props.getDevin().getApiKey().isBlank()
                && props.getDevin().getOrgId() != null
                && !props.getDevin().getOrgId().isBlank();
    }

    public DevinDtos.SessionDetails createSession(
            String prompt, String title, List<String> tags, Integer maxAcuLimit, String structuredOutputSchema) {
        return createSession(prompt, title, tags, maxAcuLimit, structuredOutputSchema, props.getGithub().getRepo());
    }

    /** Same, but scoped to the repository the work is for, so the session clones the right code. */
    public DevinDtos.SessionDetails createSession(
            String prompt,
            String title,
            List<String> tags,
            Integer maxAcuLimit,
            String structuredOutputSchema,
            String repo) {
        JsonNode schema = parseSchema(structuredOutputSchema);
        DevinDtos.CreateSessionRequest request = new DevinDtos.CreateSessionRequest(
                prompt, title, tags, maxAcuLimit, schema, schema != null, repos(repo), null);
        return withRetries(
                "createSession",
                () -> http.post()
                        .uri("/v3/organizations/{org}/sessions", orgId())
                        .body(request)
                        .retrieve()
                        .body(DevinDtos.SessionDetails.class));
    }

    public Optional<DevinDtos.SessionDetails> getSession(String sessionId) {
        try {
            return Optional.ofNullable(withRetries(
                    "getSession",
                    () -> http.get()
                            .uri("/v3/organizations/{org}/sessions/{id}", orgId(), sessionId)
                            .retrieve()
                            .body(DevinDtos.SessionDetails.class)));
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("Devin session {} not found", sessionId);
            return Optional.empty();
        }
    }

    public void sendMessage(String sessionId, String message) {
        withRetries("sendMessage", () -> http.post()
                .uri("/v3/organizations/{org}/sessions/{id}/messages", orgId(), sessionId)
                .body(new DevinDtos.SendMessageRequest(message))
                .retrieve()
                .toBodilessEntity());
    }

    private String orgId() {
        return props.getDevin().getOrgId();
    }

    private static List<String> repos(String repo) {
        return repo == null || repo.isBlank() ? null : List.of(repo);
    }

    private JsonNode parseSchema(String schema) {
        if (schema == null || schema.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(schema);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("structured output schema is not valid JSON", e);
        }
    }

    private <T> T withRetries(String operation, java.util.function.Supplier<T> call) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                T answer = call.get();
                credentials.accepted();
                return answer;
            } catch (HttpClientErrorException.TooManyRequests | HttpServerErrorException | ResourceAccessException e) {
                last = (RuntimeException) e;
                Duration backoff = Duration.ofMillis(500L * (1L << (attempt - 1)));
                log.warn("Devin API {} failed (attempt {}/{}), retrying in {}ms: {}",
                        operation, attempt, MAX_RETRIES, backoff.toMillis(), e.getMessage());
                sleep(backoff);
            } catch (RuntimeException e) {
                credentials.refused(operation, e);
                throw e;
            }
        }
        throw new DevinApiException("Devin API " + operation + " failed after " + MAX_RETRIES + " attempts", last);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DevinApiException("interrupted while backing off", e);
        }
    }
}
