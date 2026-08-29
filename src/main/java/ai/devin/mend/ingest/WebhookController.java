package ai.devin.mend.ingest;

import ai.devin.mend.config.MendProperties;
import ai.devin.mend.domain.Repository;
import ai.devin.mend.engine.Orchestrator;
import ai.devin.mend.github.GitHubDtos;
import ai.devin.mend.registry.ContextService;
import ai.devin.mend.registry.RepositoryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Low-latency trigger: GitHub {@code issues.labeled} events. */
@RestController
@RequestMapping("/webhooks/github")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final Orchestrator orchestrator;
    private final RepositoryService registry;
    private final ContextService context;
    private final ObjectMapper mapper;
    private final MendProperties props;

    public WebhookController(
            Orchestrator orchestrator,
            RepositoryService registry,
            ContextService context,
            ObjectMapper mapper,
            MendProperties props) {
        this.orchestrator = orchestrator;
        this.registry = registry;
        this.context = context;
        this.mapper = mapper;
        this.props = props;
    }

    @PostMapping
    public ResponseEntity<String> receive(
            @RequestHeader(value = "X-GitHub-Event", required = false) String event,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody byte[] payload) {

        if (!signatureValid(payload, signature)) {
            log.warn("rejected webhook with invalid signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("invalid signature");
        }
        if (!"issues".equals(event) && !"push".equals(event)) {
            return ResponseEntity.ok("ignored: " + event);
        }
        try {
            JsonNode root = mapper.readTree(payload);
            String slug = root.path("repository").path("full_name").asText();
            Repository repository = registry.find(slug).orElse(null);
            if (repository == null || !repository.isOperational()) {
                return ResponseEntity.ok("ignored: %s is not a registered repository".formatted(slug));
            }
            if ("push".equals(event)) {
                return ResponseEntity.accepted().body(notePush(root, repository));
            }
            String action = root.path("action").asText();
            String label = root.path("label").path("name").asText();
            if (!"labeled".equals(action) || !registry.triggerLabel(repository).equals(label)) {
                return ResponseEntity.ok("ignored: %s/%s".formatted(action, label));
            }
            GitHubDtos.Issue issue = mapper.treeToValue(root.path("issue"), GitHubDtos.Issue.class);
            orchestrator.onTriggerLabel(repository.slug(), issue);
            return ResponseEntity.accepted().body("queued %s#%d".formatted(repository.slug(), issue.number()));
        } catch (Exception e) {
            log.error("failed to handle webhook", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("error");
        }
    }

    /**
     * A push to the default branch ages only the profile slices whose files it touched. The refresh
     * itself happens on menD's own schedule, so a busy repository cannot drive Devin sessions from
     * webhook traffic.
     */
    private String notePush(JsonNode root, Repository repository) {
        String ref = root.path("ref").asText();
        String defaultRef = "refs/heads/" + String.valueOf(repository.getDefaultBranch());
        if (!defaultRef.equals(ref)) {
            return "ignored: push to " + ref;
        }
        JsonNode commits = root.path("commits");
        List<String> paths = new ArrayList<>();
        for (JsonNode commit : commits) {
            for (String field : List.of("added", "modified", "removed")) {
                commit.path(field).forEach(path -> paths.add(path.asText()));
            }
        }
        context.onPush(repository, commits.size(), paths, root.path("after").asText());
        return "noted %d commit(s), %d changed path(s) on %s".formatted(commits.size(), paths.size(), repository.slug());
    }

    private boolean signatureValid(byte[] payload, String signature) {
        String secret = props.getGithub().getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            return true; // no secret configured: local/demo mode
        }
        if (signature == null || !signature.startsWith("sha256=")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("signature verification failed", e);
            return false;
        }
    }
}
