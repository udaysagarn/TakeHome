package ai.devin.mend.ingest;

import ai.devin.mend.config.MendProperties;
import ai.devin.mend.engine.Orchestrator;
import ai.devin.mend.github.GitHubDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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
    private final ObjectMapper mapper;
    private final MendProperties props;

    public WebhookController(Orchestrator orchestrator, ObjectMapper mapper, MendProperties props) {
        this.orchestrator = orchestrator;
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
        if (!"issues".equals(event)) {
            return ResponseEntity.ok("ignored: " + event);
        }
        try {
            JsonNode root = mapper.readTree(payload);
            String action = root.path("action").asText();
            String label = root.path("label").path("name").asText();
            if (!"labeled".equals(action) || !props.getGithub().getTriggerLabel().equals(label)) {
                return ResponseEntity.ok("ignored: %s/%s".formatted(action, label));
            }
            GitHubDtos.Issue issue = mapper.treeToValue(root.path("issue"), GitHubDtos.Issue.class);
            orchestrator.onTriggerLabel(issue);
            return ResponseEntity.accepted().body("queued issue #" + issue.number());
        } catch (Exception e) {
            log.error("failed to handle webhook", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("error");
        }
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
