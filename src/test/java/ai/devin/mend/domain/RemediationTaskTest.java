package ai.devin.mend.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RemediationTaskTest {

    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");

    /** Auditing stamps {@code createdAt} on persist; a unit test has to plant it directly. */
    private static RemediationTask discoveredAt(int issue, Instant created) {
        RemediationTask task = new RemediationTask("acme/superset", issue, "t", "https://x/" + issue, "menD");
        ReflectionTestUtils.setField(task, "createdAt", created);
        return task;
    }

    @Test
    void anInFlightTaskHasElapsedUpToTheInstantItIsAsked() {
        RemediationTask task = discoveredAt(1, NOW);

        Duration elapsed = task.elapsed(NOW.plus(Duration.ofMinutes(7)));

        assertThat(elapsed).isEqualTo(Duration.ofMinutes(7));
    }

    @Test
    void aFinishedTaskStopsCountingAtCompletion() {
        RemediationTask task = discoveredAt(2, NOW);
        task.setCompletedAt(NOW.plus(Duration.ofMinutes(3)));

        assertThat(task.elapsed(NOW.plus(Duration.ofHours(9)))).isEqualTo(Duration.ofMinutes(3));
    }

    @Test
    void overdueIsJudgedAgainstTheGivenInstantAndOnlyWhileActive() {
        RemediationTask task = new RemediationTask("acme/superset", 3, "t", "https://x/3", "menD");
        task.setEtaAt(NOW);

        assertThat(task.isOverdue(NOW.minusSeconds(1))).isFalse();
        assertThat(task.isOverdue(NOW.plusSeconds(1))).isTrue();

        task.setState(IssueState.SUCCEEDED);
        assertThat(task.isOverdue(NOW.plusSeconds(1))).isFalse();
    }
}
