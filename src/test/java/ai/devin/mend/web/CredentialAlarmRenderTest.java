package ai.devin.mend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.devin.mend.devin.DevinApiClient;
import ai.devin.mend.devin.DevinCredentialMonitor;
import ai.devin.mend.domain.AccessState;
import ai.devin.mend.domain.DevinCredentialVerdicts;
import ai.devin.mend.domain.Repository;
import ai.devin.mend.domain.RepositoryRegistry;
import ai.devin.mend.github.GitHubClient;
import ai.devin.mend.github.GitHubDtos;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;

/**
 * A credential menD cannot use is invisible in the numbers — the board simply stays empty — so the
 * dashboard has to say it on whatever page the operator opens.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "mend.engine.enabled=false",
            "mend.github.polling-enabled=false",
            "mend.github.repos=",
            "spring.datasource.url=jdbc:h2:mem:alarm;DB_CLOSE_DELAY=-1"
        })
class CredentialAlarmRenderTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RepositoryRegistry repositories;

    @Autowired
    private DevinCredentialMonitor devinCredential;

    @Autowired
    private DevinCredentialVerdicts devinVerdicts;

    @MockBean
    private GitHubClient github;

    @MockBean
    private DevinApiClient devin;

    @BeforeEach
    void setUp() {
        repositories.deleteAll();
        devinVerdicts.deleteAll();
        when(github.isConfigured()).thenReturn(true);
        when(devin.isConfigured()).thenReturn(true);
    }

    @Test
    void aRejectedRepositoryIsAnnouncedOnEveryPageWithTheWayToRetry() throws Exception {
        Repository rejected = new Repository("acme", "superset");
        rejected.markAccessFailure(
                AccessState.NO_ACCESS,
                "menD could not ask GitHub about acme/superset: GitHub answered 401."
                        + " Check the GitHub App id, installation id and private key, then re-validate.");
        repositories.save(rejected);

        for (String page : new String[] {"/", "/flows", "/learnings", "/repositories/new"}) {
            assertThat(html(page))
                    .as(page)
                    .contains("Credentials failing")
                    .contains("GitHub answered 401")
                    .contains("Re-validate");
        }
    }

    @Test
    void aDevinKeyThatIsPresentButRefusedIsAnnouncedToo() throws Exception {
        devinCredential.refused(
                "createSession", HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "", null, null, null));

        assertThat(html("/"))
                .contains("Credentials failing")
                .contains("Devin refused menD")
                .contains("401")
                // Nothing to re-validate: menD learns this from the next call it makes, not a probe.
                .doesNotContain("Re-validate");
    }

    @Test
    void aWorkingDevinKeyIsSilentEvenAfterAnEarlierRefusal() throws Exception {
        devinCredential.refused(
                "createSession", HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "", null, null, null));
        assertThat(html("/")).contains("Devin refused menD");

        devinCredential.accepted();

        assertThat(html("/")).doesNotContain("Credentials failing");
    }

    @Test
    void theAlarmIsInThePolledFragmentSoItClearsWithoutAReload() throws Exception {
        Repository rejected = new Repository("acme", "superset");
        rejected.markAccessFailure(AccessState.MISSING_PERMISSION, "Issues are turned off on acme/superset.");
        repositories.save(rejected);
        assertThat(html("/fragments/live")).contains("Credentials failing", "Issues are turned off");

        rejected.markValidated("main", "1");
        repositories.save(rejected);
        assertThat(html("/fragments/live")).doesNotContain("Credentials failing");
    }

    @Test
    void aMissingCredentialIsNamedOnceRatherThanOncePerRepository() throws Exception {
        when(github.isConfigured()).thenReturn(false);
        when(devin.isConfigured()).thenReturn(false);
        repositories.save(new Repository("acme", "superset"));
        repositories.save(new Repository("acme", "airflow"));

        assertThat(html("/"))
                .contains("GitHub credentials are not configured")
                .contains("Devin credentials are not configured")
                .doesNotContain("Re-validate");
    }

    @Test
    void aRetryThatSucceedsDoesNotStillAnnounceTheOldFailure() throws Exception {
        Repository rejected = new Repository("acme", "superset");
        rejected.markAccessFailure(AccessState.NO_ACCESS, "menD cannot see acme/superset.");
        repositories.save(rejected);
        GitHubDtos.Repo remote = new GitHubDtos.Repo(
                1L,
                "superset",
                "acme/superset",
                "https://github.com/acme/superset",
                "main",
                false,
                false,
                "public",
                true,
                "Python");
        when(github.getRepo("acme/superset")).thenReturn(Optional.of(remote));
        when(github.installationRepos()).thenReturn(List.of(remote));
        when(github.installationPermissions()).thenReturn(Map.of("issues", "write", "pull_requests", "write"));

        String html = mvc.perform(post("/repositories").param("repo", "acme/superset"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(html).doesNotContain("Credentials failing", "menD cannot see acme/superset.");
    }

    @Test
    void nothingIsShownWhileEveryCredentialWorks() throws Exception {
        Repository validated = new Repository("acme", "superset");
        validated.markValidated("main", "1");
        repositories.save(validated);

        assertThat(html("/")).doesNotContain("Credentials failing");
        assertThat(html("/flows")).doesNotContain("Credentials failing");
    }

    private String html(String path) throws Exception {
        return mvc.perform(get(path)).andReturn().getResponse().getContentAsString();
    }
}
