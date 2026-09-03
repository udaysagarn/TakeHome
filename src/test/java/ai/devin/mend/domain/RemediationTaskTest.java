package ai.devin.mend.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RemediationTaskTest {

    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");

    @Test
    void anInFlightTaskHasElapsedUpToTheInstantItIsAsked() {
        RemediationTask task = new RemediationTask("acme/superset", 1, "t", "https://x/1", "menD");

        Duration elapsed = task.elapsed(task.getCreatedAt().plus(Duration.ofMinutes(7)));

        assertThat(elapsed).isEqualTo(Duration.ofMinutes(7));
    }

    @Test
    void aFinishedTaskStopsCountingAtCompletion() {
        RemediationTask task = new RemediationTask("acme/superset", 2, "t", "https://x/2", "menD");
        task.setCompletedAt(task.getCreatedAt().plus(Duration.ofMinutes(3)));

        assertThat(task.elapsed(task.getCreatedAt().plus(Duration.ofHours(9)))).isEqualTo(Duration.ofMinutes(3));
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
