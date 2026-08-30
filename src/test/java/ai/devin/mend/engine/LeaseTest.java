package ai.devin.mend.engine;

import static org.assertj.core.api.Assertions.assertThat;

import ai.devin.mend.config.MendProperties;
import ai.devin.mend.domain.IssueState;
import ai.devin.mend.domain.RemediationTask;
import ai.devin.mend.domain.TaskRepository;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The properties the lease protocol exists for: two workers never own the same task, and a worker
 * that dies never keeps one.
 */
@SpringBootTest
@TestPropertySource(
        properties = {
            "mend.engine.enabled=false",
            "mend.github.polling-enabled=false",
            "mend.github.repo=acme/superset",
            "spring.datasource.url=jdbc:h2:mem:lease;DB_CLOSE_DELAY=-1"
        })
class LeaseTest {

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private LeaseManager leases;

    @Autowired
    private MendProperties props;

    @Autowired
    private PlatformTransactionManager txManager;

    @BeforeEach
    void setUp() {
        tasks.deleteAll();
    }

    @Test
    void claimingRecordsTheOwnerAndAPredictedCompletion() {
        RemediationTask task = persist(11);

        RemediationTask claimed = leases.claim(task).orElseThrow();

        assertThat(claimed.getOwnerId()).isEqualTo(leases.workerId());
        assertThat(claimed.getLeaseExpiresAt()).isAfter(Instant.now());
        assertThat(claimed.getEtaAt()).isAfter(Instant.now());
        assertThat(claimed.isLeased(Instant.now())).isTrue();
    }

    @Test
    void aTaskHeldByALiveWorkerCannotBeClaimedByAnother() {
        RemediationTask task = persist(12);
        leaseTo("worker-a", task, props.getEngine().getLeaseDuration().toSeconds());

        assertThat(leases.claim(reload(task))).isEmpty();
        assertThat(reload(task).getOwnerId()).isEqualTo("worker-a");
    }

    @Test
    void concurrentClaimsProduceExactlyOneOwner() throws Exception {
        RemediationTask task = persist(13);
        int racers = 8;

        try (ExecutorService pool = Executors.newFixedThreadPool(racers)) {
            List<Callable<Integer>> attempts = IntStream.range(0, racers)
                    .<Callable<Integer>>mapToObj(i -> () -> new TransactionTemplate(txManager).execute(status -> {
                        Instant now = Instant.now();
                        return tasks.claim(
                                task.getId(), "worker-" + i, now, now.plusSeconds(120), now.plusSeconds(600));
                    }))
                    .toList();
            long winners = pool.invokeAll(attempts).stream()
                    .map(LeaseTest::get)
                    .filter(updated -> updated == 1)
                    .count();
            assertThat(winners).isEqualTo(1);
        }
        assertThat(reload(task).getOwnerId()).startsWith("worker-");
    }

    @Test
    void anExpiredLeaseIsReclaimedWithTheTaskStateIntact() {
        RemediationTask task = persist(14);
        task.setState(IssueState.RUNNING);
        task.setSessionId("devin-abc");
        task.setSessionUrl("https://app.devin.ai/sessions/abc");
        task.setAttempts(1);
        tasks.save(task);
        leaseTo("dead-worker", reload(task), -30);

        assertThat(leases.claimable(List.of(IssueState.RUNNING))).extracting(RemediationTask::getId)
                .contains(task.getId());

        RemediationTask reclaimed = leases.claim(reload(task)).orElseThrow();

        assertThat(reclaimed.getOwnerId()).isEqualTo(leases.workerId());
        assertThat(reclaimed.getLeaseTakeovers()).isEqualTo(1);
        assertThat(reclaimed.getState()).isEqualTo(IssueState.RUNNING);
        assertThat(reclaimed.getSessionId()).isEqualTo("devin-abc");
        assertThat(reclaimed.getAttempts()).isEqualTo(1);
    }

    @Test
    void onlyTheCurrentOwnerCanExtendTheLease() {
        RemediationTask task = persist(15);
        RemediationTask claimed = leases.claim(task).orElseThrow();
        Instant firstExpiry = claimed.getLeaseExpiresAt();

        assertThat(leases.renew(claimed)).isTrue();
        assertThat(reload(task).getLeaseExpiresAt()).isAfterOrEqualTo(firstExpiry);

        leaseTo("someone-else", reload(task), 120);
        assertThat(leases.renew(reload(task))).isFalse();
    }

    @Test
    void reachingATerminalStateReleasesTheLease() {
        RemediationTask task = persist(16);
        RemediationTask claimed = leases.claim(task).orElseThrow();

        leases.release(claimed);

        RemediationTask released = reload(task);
        assertThat(released.getOwnerId()).isNull();
        assertThat(released.getLeaseExpiresAt()).isNull();
        assertThat(released.isLeased(Instant.now())).isFalse();
    }

    @Test
    void theHeartbeatKeepsAHeldLeaseAliveWhileWorkIsInFlight() {
        RemediationTask task = persist(18);
        RemediationTask claimed = leases.claim(task).orElseThrow();
        Instant firstExpiry = claimed.getLeaseExpiresAt();

        leaseTo(leases.workerId(), reload(task), 1);
        leases.heartbeat();

        assertThat(reload(task).getLeaseExpiresAt()).isAfter(firstExpiry.minusSeconds(1));
        assertThat(reload(task).isLeased(Instant.now())).isTrue();
    }

    @Test
    void theHeartbeatStopsOnceAnotherWorkerHasTakenOver() {
        RemediationTask task = persist(19);
        leases.claim(task);
        leaseTo("other-worker", reload(task), 120);

        leases.heartbeat();

        assertThat(reload(task).getOwnerId()).isEqualTo("other-worker");
    }

    @Test
    void aWorkerReclaimsItsOwnTaskWithoutCountingATakeover() {
        RemediationTask task = persist(17);
        leases.claim(task);

        RemediationTask again = leases.claim(reload(task)).orElseThrow();

        assertThat(again.getLeaseTakeovers()).isZero();
        assertThat(again.getOwnerId()).isEqualTo(leases.workerId());
    }

    private RemediationTask persist(int issueNumber) {
        RemediationTask task = new RemediationTask(
                "acme/superset",
                issueNumber,
                "chore: bump nth-check",
                "https://github.com/acme/superset/issues/" + issueNumber,
                "");
        return tasks.save(task);
    }

    /** Simulates another worker's lease; a negative offset simulates a worker that died. */
    private void leaseTo(String owner, RemediationTask task, long secondsUntilExpiry) {
        task.setOwnerId(owner);
        task.setLeaseAcquiredAt(Instant.now().minusSeconds(60));
        task.setLeaseExpiresAt(Instant.now().plusSeconds(secondsUntilExpiry));
        tasks.saveAndFlush(task);
    }

    private RemediationTask reload(RemediationTask task) {
        return tasks.findById(task.getId()).orElseThrow();
    }

    private static <T> T get(Future<T> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
