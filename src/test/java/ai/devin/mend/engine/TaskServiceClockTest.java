package ai.devin.mend.engine;

import static org.assertj.core.api.Assertions.assertThat;

import ai.devin.mend.domain.IssueState;
import ai.devin.mend.domain.RemediationTask;
import ai.devin.mend.domain.TaskRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

/** Every timestamp a transition writes comes from the injected clock, so time is an input, not a race. */
@SpringBootTest
@Import(TaskServiceClockTest.FrozenClock.class)
@TestPropertySource(
        properties = {
            "mend.engine.enabled=false",
            "mend.github.polling-enabled=false",
            "mend.github.repo=acme/superset",
            "spring.datasource.url=jdbc:h2:mem:clock;DB_CLOSE_DELAY=-1"
        })
class TaskServiceClockTest {

    static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");

    @TestConfiguration
    static class FrozenClock {
        @Bean
        @Primary
        Clock frozenClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private TaskService taskService;

    @Autowired
    private LeaseManager leases;

    @BeforeEach
    void setUp() {
        tasks.deleteAll();
    }

    @Test
    void aTransitionStampsEveryTimestampFromTheSameInstant() {
        RemediationTask task = new RemediationTask("acme/superset", 1, "t", "https://x/1", "menD");
        task.setState(IssueState.READY);
        task = tasks.save(task);

        RemediationTask dispatched = taskService.transition(task, IssueState.DISPATCHED, "session", "test");

        assertThat(dispatched.getDispatchedAt()).isEqualTo(NOW);
        assertThat(dispatched.getUpdatedAt()).isEqualTo(NOW);
        assertThat(dispatched.getEtaAt()).isEqualTo(NOW.plus(leases.estimatedRemaining(IssueState.DISPATCHED)));
    }

    @Test
    void aTerminalTransitionRecordsCompletionAtTheClockTime() {
        RemediationTask task = new RemediationTask("acme/superset", 2, "t", "https://x/2", "menD");
        task.setState(IssueState.VERIFYING);
        task = tasks.save(task);

        RemediationTask done = taskService.transition(task, IssueState.SUCCEEDED, "verified", "test");

        assertThat(done.getCompletedAt()).isEqualTo(NOW);
        assertThat(done.elapsed(NOW.plus(Duration.ofDays(1)))).isEqualTo(Duration.between(done.getCreatedAt(), NOW));
    }

    @Test
    void savingRefreshesUpdatedAtFromTheClock() {
        RemediationTask task = new RemediationTask("acme/superset", 3, "t", "https://x/3", "menD");

        assertThat(taskService.save(task).getUpdatedAt()).isEqualTo(NOW);
    }
}
