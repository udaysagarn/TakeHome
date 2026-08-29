package ai.devin.d1.devin;

import ai.devin.d1.config.D1Properties;
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
 * Thin, retrying client for the Devin v1 session API.
 *
 * <p>Only three calls are needed to use Devin as an execution primitive: create a session, read its
 * status and structured output, and send it a message when it stalls or CI disagrees with it.
 */
@Component
public class DevinApiClient {

    private static final Logger log = LoggerFactory.getLogger(DevinApiClient.class);
    private static final int MAX_RETRIES = 3;

    private final RestClient http;
    private final ObjectMapper mapper;
    private final D1Properties props;

    public DevinApiClient(RestClient.Builder builder, ObjectMapper mapper, D1Properties props) {
        this.mapper = mapper;
        this.props = props;
        this.http = builder
                .baseUrl(props.getDevin().getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getDevin().getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public boolean isConfigured() {
        return props.getDevin().getApiKey() != null && !props.getDevin().getApiKey().isBlank();
    }

    public DevinDtos.CreateSessionResponse createSession(
            String prompt, String title, List<String> tags, Integer maxAcuLimit, String structuredOutputSchema) {
        JsonNode schema = parseSchema(structuredOutputSchema);
        DevinDtos.CreateSessionRequest request =
                new DevinDtos.CreateSessionRequest(prompt, title, tags, true, maxAcuLimit, schema, false, null);
        return withRetries(
                "createSession",
                () -> http.post()
                        .uri("/v1/sessions")
                        .body(request)
                        .retrieve()
                        .body(DevinDtos.CreateSessionResponse.class));
    }

    public Optional<DevinDtos.SessionDetails> getSession(String sessionId) {
        try {
            return Optional.ofNullable(withRetries(
                    "getSession",
                    () -> http.get()
                            .uri("/v1/sessions/{id}", sessionId)
                            .retrieve()
                            .body(DevinDtos.SessionDetails.class)));
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("Devin session {} not found", sessionId);
            return Optional.empty();
        }
    }

    public void sendMessage(String sessionId, String message) {
        withRetries("sendMessage", () -> http.post()
                .uri("/v1/sessions/{id}/message", sessionId)
                .body(new DevinDtos.SendMessageRequest(message))
                .retrieve()
                .toBodilessEntity());
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
                return call.get();
            } catch (HttpClientErrorException.TooManyRequests | HttpServerErrorException | ResourceAccessException e) {
                last = (RuntimeException) e;
                Duration backoff = Duration.ofMillis(500L * (1L << (attempt - 1)));
                log.warn("Devin API {} failed (attempt {}/{}), retrying in {}ms: {}",
                        operation, attempt, MAX_RETRIES, backoff.toMillis(), e.getMessage());
                sleep(backoff);
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

    public static class DevinApiException extends RuntimeException {
        public DevinApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
