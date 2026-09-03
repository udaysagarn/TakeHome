package ai.devin.mend.devin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ai.devin.mend.config.MendProperties;
import ai.devin.mend.domain.SuccessCriteria;
import ai.devin.mend.exception.DevinApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

class DevinApiClientTest {

    private MockRestServiceServer server;
    private DevinApiClient client;
    private DevinCredentialMonitor credentials;

    @BeforeEach
    void setUp() {
        MendProperties props = new MendProperties();
        props.getDevin().setApiKey("cog_test");
        props.getDevin().setOrgId("org-1");
        props.getGithub().setRepo("o/r");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        credentials = mock(DevinCredentialMonitor.class);
        client = new DevinApiClient(builder, new ObjectMapper(), props, credentials);
    }

    @Test
    void createSessionSendsSnakeCaseFieldsAndAuthenticates() {
        server.expect(requestTo("https://api.devin.ai/v3/organizations/org-1/sessions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer cog_test"))
                .andExpect(jsonPath("$.prompt").value("fix it"))
                .andExpect(jsonPath("$.max_acu_limit").value(10))
                .andExpect(jsonPath("$.repos[0]").value("o/r"))
                .andExpect(jsonPath("$.structured_output_required").value(true))
                .andExpect(jsonPath("$.structured_output_schema.type").value("object"))
                .andRespond(withSuccess(
                        """
                        {"session_id":"devin-abc","url":"https://app.devin.ai/sessions/abc",
                         "status":"new","org_id":"org-1","acus_consumed":0.0,
                         "created_at":1788000000,"updated_at":1788000000,"tags":[],"pull_requests":[]}
                        """,
                        MediaType.APPLICATION_JSON));

        DevinDtos.SessionDetails response =
                client.createSession("fix it", "t", List.of("mend"), 10, SuccessCriteria.JSON_SCHEMA);

        assertThat(response.sessionId()).isEqualTo("devin-abc");
        assertThat(response.url()).contains("app.devin.ai");
        server.verify();
    }

    @Test
    void sessionDetailsExposeStatusStructuredOutputAndPullRequest() {
        server.expect(requestTo("https://api.devin.ai/v3/organizations/org-1/sessions/devin-abc"))
                .andRespond(withSuccess(
                        """
                        {
                          "session_id": "devin-abc",
                          "status": "running",
                          "status_detail": "waiting_for_user",
                          "acus_consumed": 2.5,
                          "pull_requests": [{"pr_url": "https://github.com/o/r/pull/7", "pr_state": "open"}],
                          "structured_output": {"remediated": true}
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        DevinDtos.SessionDetails details = client.getSession("devin-abc").orElseThrow();

        assertThat(details.isBlocked()).isTrue();
        assertThat(details.isWorking()).isFalse();
        assertThat(details.acusConsumed()).isEqualTo(2.5);
        assertThat(details.pullRequestUrl()).isEqualTo("https://github.com/o/r/pull/7");
        assertThat(details.structuredOutput().path("remediated").asBoolean()).isTrue();
        assertThat(details.hasStructuredOutput()).isTrue();
        server.verify();
    }

    @Test
    void jsonNullStructuredOutputCountsAsAbsent() {
        server.expect(requestTo("https://api.devin.ai/v3/organizations/org-1/sessions/devin-abc"))
                .andRespond(withSuccess(
                        """
                        {"session_id": "devin-abc", "status": "running", "structured_output": null}
                        """,
                        MediaType.APPLICATION_JSON));

        DevinDtos.SessionDetails details = client.getSession("devin-abc").orElseThrow();

        assertThat(details.hasStructuredOutput()).isFalse();
        server.verify();
    }

    @Test
    void messagesAreSentToTheSessionEndpoint() {
        server.expect(requestTo("https://api.devin.ai/v3/organizations/org-1/sessions/devin-abc/messages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"message\":\"CI is red\"}"))
                .andRespond(withSuccess());

        client.sendMessage("devin-abc", "CI is red");
        server.verify();
    }

    @Test
    void aRefusedKeyIsReportedSoTheDashboardCanSayWhyNothingIsDispatched() {
        server.expect(requestTo("https://api.devin.ai/v3/organizations/org-1/sessions"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.createSession("fix it", "t", List.of(), 10, null))
                .isInstanceOf(HttpClientErrorException.Unauthorized.class);

        verify(credentials).refused(eq("createSession"), any(HttpClientErrorException.Unauthorized.class));
        verify(credentials, never()).accepted();
    }

    @Test
    void devinBeingDownIsNotACredentialProblem() {
        for (int attempt = 0; attempt < 3; attempt++) {
            server.expect(requestTo("https://api.devin.ai/v3/organizations/org-1/sessions"))
                    .andRespond(withServerError());
        }

        assertThatThrownBy(() -> client.createSession("fix it", "t", List.of(), 10, null))
                .isInstanceOf(DevinApiException.class);

        verify(credentials, never()).refused(any(), any());
    }

    @Test
    void aCallThatWorksSaysTheCredentialIsGood() {
        server.expect(requestTo("https://api.devin.ai/v3/organizations/org-1/sessions/devin-abc/messages"))
                .andRespond(withSuccess());

        client.sendMessage("devin-abc", "CI is red");

        verify(credentials).accepted();
    }
}
