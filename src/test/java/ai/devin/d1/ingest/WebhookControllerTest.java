package ai.devin.d1.ingest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devin.d1.engine.Orchestrator;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "d1.engine.enabled=false",
            "d1.github.polling-enabled=false",
            "d1.github.webhook-secret=s3cret",
            "d1.github.trigger-label=devin:fix",
            "spring.datasource.url=jdbc:h2:mem:webhook;DB_CLOSE_DELAY=-1",
            "spring.jpa.hibernate.ddl-auto=create-drop"
        })
class WebhookControllerTest {

    private static final String LABELED =
            """
            {"action":"labeled","label":{"name":"devin:fix"},
             "issue":{"number":42,"title":"chore: bump nth-check","body":"body",
                      "html_url":"https://github.com/acme/superset/issues/42","labels":[{"name":"devin:fix"}]}}
            """;

    @Autowired
    private MockMvc mvc;

    @MockBean
    private Orchestrator orchestrator;

    @Test
    void unsignedPayloadsAreRejectedWhenASecretIsConfigured() throws Exception {
        mvc.perform(post("/webhooks/github")
                        .header("X-GitHub-Event", "issues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LABELED))
                .andExpect(status().isUnauthorized());
        verify(orchestrator, never()).onTriggerLabel(any());
    }

    @Test
    void aSignedTriggerLabelEventIsQueued() throws Exception {
        mvc.perform(post("/webhooks/github")
                        .header("X-GitHub-Event", "issues")
                        .header("X-Hub-Signature-256", sign(LABELED))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LABELED))
                .andExpect(status().isAccepted());
        verify(orchestrator).onTriggerLabel(any());
    }

    @Test
    void unrelatedLabelsAreIgnored() throws Exception {
        String body = LABELED.replace("devin:fix\"},", "documentation\"},");
        mvc.perform(post("/webhooks/github")
                        .header("X-GitHub-Event", "issues")
                        .header("X-Hub-Signature-256", sign(body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        verify(orchestrator, never()).onTriggerLabel(any());
    }

    private static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("s3cret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
