package ai.devin.mend.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ai.devin.mend.domain.IssueState;
import ai.devin.mend.domain.RemediationTask;
import ai.devin.mend.domain.TaskRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

/**
 * The reconciler is what makes menD survivable: it is level-triggered, so the interesting cases are
 * the ones where something went wrong — a dead worker's task, a task that reached a terminal state,
 * and a lease stolen while work was in flight.
 */
@SpringBootTest
@TestPropertySource(
        properties = {
            "mend.engine.enabled=true",
            "mend.github.polling-enabled=false",
            "mend.github.repo=acme/superset",
            "spring.datasource.url=jdbc:h2:mem:reconciler;DB_CLOSE_DELAY=-1"
        })
class ReconcilerTest {

    @Autowired
    private Reconciler reconciler;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private LeaseManager leases;

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
    }

    @Test
    void aTickAdvancesEachActiveTaskUnderALeaseThisWorkerHolds() {
        RemediationTask task = persist(21, IssueState.READY);

        reconciler.tick();

        verify(orchestrator).advance(any());
        RemediationTask after = reload(task);
        assertThat(after.getOwnerId()).isEqualTo(leases.workerId());
        assertThat(after.getLeaseExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void terminalTasksAreLeftAloneAndTheirLeaseIsGivenBack() {
        RemediationTask task = persist(22, IssueState.READY);
        doAnswer(invocation -> {
                    RemediationTask claimed = invocation.getArgument(0);
                    claimed.setState(IssueState.SUCCEEDED);
                    tasks.saveAndFlush(claimed);
                    return null;
                })
                .when(orchestrator)
                .advance(any());

        reconciler.tick();

        RemediationTask after = reload(task);
        assertThat(after.getState()).isEqualTo(IssueState.SUCCEEDED);
        assertThat(after.getOwnerId()).isNull();
        assertThat(after.getLeaseExpiresAt()).isNull();

        reconciler.tick();
        verify(orchestrator).advance(any()); // once in total: a terminal task is never re-advanced
    }

    @Test
    void aDeadWorkersTaskIsResumedFromItsPersistedState() {
        RemediationTask task = persist(23, IssueState.RUNNING);
        task.setSessionId("devin-abc");
        task.setOwnerId("worker-that-died");
        task.setLeaseAcquiredAt(Instant.now().minusSeconds(600));
        task.setLeaseExpiresAt(Instant.now().minusSeconds(30));
        tasks.saveAndFlush(task);

        reconciler.tick();

        RemediationTask after = reload(task);
        assertThat(after.getOwnerId()).isEqualTo(leases.workerId());
        assertThat(after.getLeaseTakeovers()).isEqualTo(1);
        assertThat(after.getSessionId()).isEqualTo("devin-abc");
        assertThat(after.getState()).isEqualTo(IssueState.RUNNING);
        verify(orchestrator).advance(any());
    }

    @Test
    void aTaskAnotherLiveWorkerHoldsIsSkipped() {
        RemediationTask task = persist(24, IssueState.RUNNING);
        task.setOwnerId("worker-b");
        task.setLeaseAcquiredAt(Instant.now());
        task.setLeaseExpiresAt(Instant.now().plusSeconds(300));
        tasks.saveAndFlush(task);

        reconciler.tick();

        verify(orchestrator, never()).advance(any());
        assertThat(reload(task).getOwnerId()).isEqualTo("worker-b");
    }

    @Test
    void aLeaseStolenWhileWorkWasInFlightIsNotSilentlyReasserted() {
        RemediationTask task = persist(25, IssueState.READY);
        doAnswer(invocation -> {
                    RemediationTask claimed = invocation.getArgument(0);
                    claimed.setOwnerId("worker-c");
                    claimed.setLeaseExpiresAt(Instant.now().plusSeconds(300));
                    tasks.saveAndFlush(claimed);
                    return null;
                })
                .when(orchestrator)
                .advance(any());

        reconciler.tick();

        assertThat(reload(task).getOwnerId()).isEqualTo("worker-c");
    }

    @Test
    void anOrchestratorFailureStillHandsTheTaskBackForTheNextTick() {
        RemediationTask task = persist(26, IssueState.READY);
        doAnswer(invocation -> {
                    throw new IllegalStateException("the Devin API is unreachable");
                })
                .when(orchestrator)
                .advance(any());

        try {
            reconciler.tick();
        } catch (IllegalStateException expected) {
            // the tick propagates, but the lease bookkeeping must already have run
        }

        RemediationTask after = reload(task);
        assertThat(after.getState()).isEqualTo(IssueState.READY);
        assertThat(after.getOwnerId()).isEqualTo(leases.workerId());
    }

    @Test
    void shuttingDownReleasesEveryLeaseThisWorkerHolds() {
        RemediationTask task = persist(27, IssueState.READY);
        leases.claim(task);

        reconciler.releaseOwnedLeases();

        assertThat(reload(task).getOwnerId()).isNull();
    }

    @Test
    void shuttingDownLeavesAnotherWorkersLeaseAlone() {
        RemediationTask task = persist(28, IssueState.RUNNING);
        task.setOwnerId("worker-d");
        task.setLeaseExpiresAt(Instant.now().plusSeconds(300));
        tasks.saveAndFlush(task);

        reconciler.releaseOwnedLeases();

        assertThat(reload(task).getOwnerId()).isEqualTo("worker-d");
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

    private RemediationTask reload(RemediationTask task) {
        return tasks.findById(task.getId()).orElseThrow();
    }
}
