package ai.devin.mend.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import ai.devin.mend.devin.DevinApiClient;
import ai.devin.mend.devin.DevinCredentialMonitor;
import ai.devin.mend.domain.AccessState;
import ai.devin.mend.domain.EnginePauses;
import ai.devin.mend.domain.Repository;
import ai.devin.mend.domain.RepositoryRegistry;
import ai.devin.mend.github.GitHubClient;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

/**
 * A credential menD cannot use has to stop the engine, not just raise an alarm: dispatching against a
 * refused key spends the operator's attention and achieves nothing. And it has to stop it once — an
 * operator who resumes with the key still broken must not be overruled on the next tick.
 */
@SpringBootTest
@TestPropertySource(
        properties = {
            "mend.engine.enabled=true",
            "mend.github.polling-enabled=false",
            "mend.github.repos=",
            "spring.datasource.url=jdbc:h2:mem:credentialguard;DB_CLOSE_DELAY=-1"
        })
class CredentialGuardTest {

    @Autowired
    private CredentialGuard guard;

    @Autowired
    private EngineControl control;

    @Autowired
    private EnginePauses pauses;

    @Autowired
    private RepositoryRegistry registry;

    @MockBean
    private GitHubClient github;

    @MockBean
    private DevinApiClient devin;

    @MockBean
    private DevinCredentialMonitor devinCredential;

    @MockBean
    private Orchestrator orchestrator;

    @BeforeEach
    void setUp() {
        pauses.deleteAll();
        registry.deleteAll();
        when(github.isConfigured()).thenReturn(true);
        when(devin.isConfigured()).thenReturn(true);
        when(devinCredential.refusal()).thenReturn(Optional.empty());
    }

    @Test
    void workingCredentialsLeaveTheEngineAlone() {
        guard.enforce();

        assertThat(control.paused()).isFalse();
    }

    @Test
    void aMissingDevinKeyPausesMendAndSaysWhichVariableToSet() {
        when(devin.isConfigured()).thenReturn(false);

        guard.enforce();

        EngineControl.Status status = control.status();
        assertThat(status.paused()).isTrue();
        assertThat(status.actor()).isEqualTo(EngineControl.SELF);
        assertThat(status.reason()).contains("DEVIN_API_KEY");
    }

    @Test
    void aMissingGitHubAppPausesMendBeforeItTriesToReadAnIssue() {
        when(github.isConfigured()).thenReturn(false);

        guard.enforce();

        assertThat(control.status().reason()).contains("GITHUB_APP_ID");
    }

    @Test
    void aRefusedDevinKeyPausesMendWithTheRefusalItself() {
        when(devinCredential.refusal()).thenReturn(Optional.of("Devin refused menD's credential on createSession with 401."));

        guard.enforce();

        assertThat(control.status().reason()).contains("createSession with 401");
    }

    @Test
    void resumingWithTheSameBrokenKeyIsNotOverruledOnTheNextTick() {
        when(devin.isConfigured()).thenReturn(false);
        guard.enforce();

        control.resume("operator");
        guard.enforce();

        assertThat(control.paused()).isFalse();
    }

    @Test
    void aDifferentFailureAfterAResumePausesMendAgain() {
        when(devin.isConfigured()).thenReturn(false);
        guard.enforce();
        control.resume("operator");

        when(devin.isConfigured()).thenReturn(true);
        when(github.isConfigured()).thenReturn(false);
        guard.enforce();

        assertThat(control.paused()).isTrue();
        assertThat(control.status().reason()).contains("GITHUB_APP_ID");
    }

    @Test
    void theSameFailureRecurringAfterItClearedPausesMendAgain() {
        when(devin.isConfigured()).thenReturn(false);
        guard.enforce();
        control.resume("operator");

        when(devin.isConfigured()).thenReturn(true);
        guard.enforce();
        when(devin.isConfigured()).thenReturn(false);
        guard.enforce();

        assertThat(control.paused()).isTrue();
    }

    @Test
    void anOperatorsOwnPauseKeepsItsReasonWhenACredentialAlsoBreaks() {
        control.pause("operator", "the demo is over");
        when(devin.isConfigured()).thenReturn(false);

        guard.enforce();

        assertThat(control.status().actor()).isEqualTo("operator");
        assertThat(control.status().reason()).isEqualTo("the demo is over");
    }

    @Test
    void everyRegisteredRepositoryRefusedReadsAsAWrongAppRatherThanAWrongRepository() {
        registry.saveAndFlush(refused("acme/superset", "the app is not installed on acme/superset"));

        guard.enforce();

        assertThat(control.status().reason())
                .contains("every registered repository")
                .contains("the app is not installed on acme/superset");
    }

    @Test
    void oneRepositoryLosingAccessWhileAnotherWorksIsThatRepositorysProblem() {
        registry.saveAndFlush(refused("acme/superset", "the app is not installed on acme/superset"));
        Repository usable = new Repository("acme", "other");
        usable.setAccessState(AccessState.VALIDATED);
        registry.saveAndFlush(usable);

        guard.enforce();

        assertThat(control.paused()).isFalse();
    }

    private Repository refused(String slug, String error) {
        Repository repository = new Repository(slug.split("/")[0], slug.split("/")[1]);
        repository.setAccessState(AccessState.NO_ACCESS);
        repository.setAccessError(error);
        return repository;
    }
}
