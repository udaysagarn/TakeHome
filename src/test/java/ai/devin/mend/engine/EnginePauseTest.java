package ai.devin.mend.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ai.devin.mend.config.MendProperties;
import ai.devin.mend.domain.EnginePause;
import ai.devin.mend.domain.EnginePauses;
import ai.devin.mend.domain.IssueState;
import ai.devin.mend.domain.RemediationTask;
import ai.devin.mend.domain.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

/**
 * A pause is only worth having if it stops the spending and nothing else: the states whose next step
 * creates a Devin session are held, and a task already dispatched still reaches its verdict.
 */
@SpringBootTest
@TestPropertySource(
        properties = {
            "mend.engine.enabled=true",
            "mend.github.polling-enabled=false",
            "mend.github.repos=",
            "spring.datasource.url=jdbc:h2:mem:enginepause;DB_CLOSE_DELAY=-1"
        })
class EnginePauseTest {

    @Autowired
    private Reconciler reconciler;

    @Autowired
    private EngineControl control;

    @Autowired
    private EnginePauses pauses;

    @Autowired
    private TaskRepository tasks;

    @MockBean
    private Orchestrator orchestrator;

    /**
     * The credential guard pauses menD when a mocked client reports no credentials, which is not what
     * these tests are about; it has its own coverage in {@code CredentialGuardTest}.
     */
    @MockBean
    private CredentialGuard credentialGuard;

    @BeforeEach
    void setUp() {
        tasks.deleteAll();
        pauses.deleteAll();
    }

    @Test
    void anEngineNobodyHasTouchedIsRunning() {
        EngineControl.Status status = control.status();

        assertThat(status.paused()).isFalse();
        assertThat(status.off()).isFalse();
        assertThat(status.running()).isTrue();
        assertThat(status.reason()).isNull();
    }

    @Test
    void pausingHoldsATaskWhoseNextStepWouldStartASession() {
        persist(41, IssueState.READY);

        control.pause("operator", "the demo is over");
        reconciler.tick();

        verify(orchestrator, never()).advance(any());
    }

    @Test
    void pausingAlsoHoldsTriageAndRetries() {
        persist(42, IssueState.DISCOVERED);
        persist(43, IssueState.FAILED);

        control.pause("operator", null);
        reconciler.tick();

        verify(orchestrator, never()).advance(any());
    }

    @Test
    void aDispatchedTaskIsStillPolledWhilePaused() {
        RemediationTask running = persist(44, IssueState.RUNNING);
        running.setSessionId("devin-abc");
        tasks.saveAndFlush(running);

        control.pause("operator", "hold new work");
        reconciler.tick();

        verify(orchestrator).advance(any());
    }

    @Test
    void resumingLetsNewWorkStartAgain() {
        persist(45, IssueState.READY);
        control.pause("operator", "hold new work");

        control.resume("operator");
        reconciler.tick();

        assertThat(control.status().running()).isTrue();
        assertThat(control.status().reason()).isNull();
        verify(orchestrator).advance(any());
    }

    @Test
    void aPauseIsPersistedSoARestartDoesNotResumeSpendingBehindTheOperator() {
        control.pause("operator", "the demo is over");

        EnginePause stored = pauses.findById(EnginePause.ID).orElseThrow();

        assertThat(stored.isPaused()).isTrue();
        assertThat(stored.getActor()).isEqualTo("operator");
        assertThat(stored.getReason()).isEqualTo("the demo is over");
        assertThat(stored.getChangedAt()).isNotNull();
        // Read back through a control that shares nothing with the one that wrote it.
        assertThat(new EngineControl(pauses, new MendProperties()).paused()).isTrue();
    }

    @Test
    void pausingTwiceKeepsTheFirstReasonRatherThanRewritingHistory() {
        control.pause("operator", "the demo is over");

        control.pause("someone else", "different reason");

        assertThat(pauses.findById(EnginePause.ID).orElseThrow().getReason()).isEqualTo("the demo is over");
    }

    @Test
    void theConfiguredKillSwitchIsNotTheSameThingAsAPause() {
        MendProperties disabled = new MendProperties();
        disabled.getEngine().setEnabled(false);
        EngineControl off = new EngineControl(pauses, disabled);

        assertThat(off.off()).isTrue();
        assertThat(off.paused()).isFalse();
        assertThat(off.newWorkAllowed()).isFalse();
        assertThat(off.status().running()).isFalse();
    }

    private RemediationTask persist(int issueNumber, IssueState state) {
        RemediationTask task = new RemediationTask(
                "acme/superset",
                issueNumber,
                "chore: bump nth-check",
                "https://github.com/acme/superset/issues/" + issueNumber,
                "");
        task.setState(state);
        return tasks.saveAndFlush(task);
    }
}
