package ai.devin.mend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.TestPropertySource;

/**
 * The lease heartbeat has to renew a lease while the reconcile loop sits in a blocking Devin or
 * GitHub call, so the scheduled loops must not queue behind one another. With one thread — Spring's
 * default for a platform-thread scheduler — a healthy worker's lease expires and is taken over.
 */
@SpringBootTest
@TestPropertySource(
        properties = {
            "mend.engine.enabled=false",
            "mend.github.polling-enabled=false",
            "spring.datasource.url=jdbc:h2:mem:schedpool;DB_CLOSE_DELAY=-1"
        })
class SchedulerPoolTest {

    /** Reconciler, LeaseManager, IssuePoller, ContextReconciler, ReviewLoop. */
    private static final int SCHEDULED_LOOPS = 5;

    @Autowired
    private TaskScheduler scheduler;

    @Test
    void oneBlockedLoopDoesNotHoldUpTheOthers() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(SCHEDULED_LOOPS);
        try {
            for (int i = 0; i < SCHEDULED_LOOPS - 1; i++) {
                scheduler.schedule(
                        () -> {
                            running.countDown();
                            try {
                                release.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        },
                        Instant.now());
            }
            scheduler.schedule(running::countDown, Instant.now());

            assertThat(running.await(5, TimeUnit.SECONDS))
                    .as("all %d loops got a thread while %d were blocked", SCHEDULED_LOOPS, SCHEDULED_LOOPS - 1)
                    .isTrue();
        } finally {
            release.countDown();
        }
    }
}
