package ai.devin.mend.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devin.mend.domain.Repository;
import ai.devin.mend.engine.Orchestrator;
import ai.devin.mend.registry.ContextService;
import ai.devin.mend.registry.RepositoryService;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
            "mend.engine.enabled=false",
            "mend.github.polling-enabled=false",
            "mend.github.webhook-secret=s3cret",
            "mend.github.trigger-label=menD:fix",
            "spring.datasource.url=jdbc:h2:mem:webhook;DB_CLOSE_DELAY=-1",
            "spring.jpa.hibernate.ddl-auto=create-drop"
        })
class WebhookControllerTest {

    private static final String LABELED =
            """
            {"action":"labeled","label":{"name":"menD:fix"},
             "repository":{"full_name":"acme/superset"},
             "issue":{"number":42,"title":"chore: bump nth-check","body":"body",
                      "html_url":"https://github.com/acme/superset/issues/42","labels":[{"name":"menD:fix"}]}}
            """;

    private static final String PUSH =
            """
            {"ref":"refs/heads/master","after":"deadbeef","repository":{"full_name":"acme/superset"},
             "commits":[{"id":"a","added":[],"modified":["package.json"],"removed":[]},
                        {"id":"b","added":["src/new.ts"],"modified":[],"removed":["src/old.ts"]}]}
            """;

    @Autowired
    private MockMvc mvc;

    @MockBean
    private Orchestrator orchestrator;

    @MockBean
    private RepositoryService registry;

    @MockBean
    private ContextService context;

    @BeforeEach
    void setUp() {
        Repository repository = new Repository("acme", "superset");
        repository.markValidated("master", "1");
        when(registry.find("acme/superset")).thenReturn(Optional.of(repository));
        when(registry.triggerLabel(any())).thenReturn("menD:fix");
    }

    @Test
    void unsignedPayloadsAreRejectedWhenASecretIsConfigured() throws Exception {
        mvc.perform(post("/webhooks/github")
                        .header("X-GitHub-Event", "issues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LABELED))
                .andExpect(status().isUnauthorized());
        verify(orchestrator, never()).onTriggerLabel(anyString(), any());
    }

    @Test
    void aSignedTriggerLabelEventIsQueued() throws Exception {
        mvc.perform(post("/webhooks/github")
                        .header("X-GitHub-Event", "issues")
                        .header("X-Hub-Signature-256", sign(LABELED))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LABELED))
                .andExpect(status().isAccepted());
        verify(orchestrator).onTriggerLabel(eq("acme/superset"), any());
    }

    @Test
    void eventsFromUnregisteredRepositoriesAreIgnored() throws Exception {
        String body = LABELED.replace("acme/superset\"}", "stranger/repo\"}");
        mvc.perform(post("/webhooks/github")
                        .header("X-GitHub-Event", "issues")
                        .header("X-Hub-Signature-256", sign(body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        verify(orchestrator, never()).onTriggerLabel(anyString(), any());
    }

    @Test
    void aPushToTheDefaultBranchAgesTheRepositoryProfile() throws Exception {
        mvc.perform(post("/webhooks/github")
                        .header("X-GitHub-Event", "push")
                        .header("X-Hub-Signature-256", sign(PUSH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PUSH))
                .andExpect(status().isAccepted());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> paths = ArgumentCaptor.forClass(List.class);
        verify(context).onPush(any(), eq(2), paths.capture(), eq("deadbeef"));
        assertThat(paths.getValue()).containsExactly("package.json", "src/new.ts", "src/old.ts");
    }

    @Test
    void aPushToAFeatureBranchDoesNotAgeTheProfile() throws Exception {
        String body = PUSH.replace("refs/heads/master", "refs/heads/feature");
        mvc.perform(post("/webhooks/github")
                        .header("X-GitHub-Event", "push")
                        .header("X-Hub-Signature-256", sign(body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());
        verify(context, never()).onPush(any(), anyInt(), any(), anyString());
    }

    @Test
    void unrelatedLabelsAreIgnored() throws Exception {
        String body = LABELED.replace("menD:fix\"},", "documentation\"},");
        mvc.perform(post("/webhooks/github")
                        .header("X-GitHub-Event", "issues")
                        .header("X-Hub-Signature-256", sign(body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        verify(orchestrator, never()).onTriggerLabel(anyString(), any());
    }

    private static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("s3cret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
