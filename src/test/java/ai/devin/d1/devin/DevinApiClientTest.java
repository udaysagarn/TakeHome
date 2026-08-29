package ai.devin.d1.devin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ai.devin.d1.config.D1Properties;
import ai.devin.d1.domain.SuccessCriteria;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DevinApiClientTest {

    private MockRestServiceServer server;
    private DevinApiClient client;

    @BeforeEach
    void setUp() {
        D1Properties props = new D1Properties();
        props.getDevin().setApiKey("apk_test");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new DevinApiClient(builder, new ObjectMapper(), props);
    }

    @Test
    void createSessionSendsSnakeCaseFieldsAndAuthenticates() {
        server.expect(requestTo("https://api.devin.ai/v1/sessions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer apk_test"))
                .andExpect(jsonPath("$.prompt").value("fix it"))
                .andExpect(jsonPath("$.max_acu_limit").value(10))
                .andExpect(jsonPath("$.idempotent").value(true))
                .andExpect(jsonPath("$.structured_output_schema.type").value("object"))
                .andRespond(withSuccess(
                        """
                        {"session_id":"devin-abc","url":"https://app.devin.ai/sessions/abc","is_new_session":true}
                        """,
                        MediaType.APPLICATION_JSON));

        DevinDtos.CreateSessionResponse response =
                client.createSession("fix it", "t", List.of("d1"), 10, SuccessCriteria.JSON_SCHEMA);

        assertThat(response.sessionId()).isEqualTo("devin-abc");
        assertThat(response.url()).contains("app.devin.ai");
        server.verify();
    }

    @Test
    void sessionDetailsExposeStatusStructuredOutputAndPullRequest() {
        server.expect(requestTo("https://api.devin.ai/v1/sessions/devin-abc"))
                .andRespond(withSuccess(
                        """
                        {
                          "session_id": "devin-abc",
                          "status_enum": "blocked",
                          "pull_request": {"url": "https://github.com/o/r/pull/7"},
                          "structured_output": {"remediated": true}
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        DevinDtos.SessionDetails details = client.getSession("devin-abc").orElseThrow();

        assertThat(details.isBlocked()).isTrue();
        assertThat(details.pullRequestUrl()).isEqualTo("https://github.com/o/r/pull/7");
        assertThat(details.structuredOutput().path("remediated").asBoolean()).isTrue();
        server.verify();
    }

    @Test
    void messagesAreSentToTheSessionEndpoint() {
        server.expect(requestTo("https://api.devin.ai/v1/sessions/devin-abc/message"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"message\":\"CI is red\"}"))
                .andRespond(withSuccess());

        client.sendMessage("devin-abc", "CI is red");
        server.verify();
    }
}
