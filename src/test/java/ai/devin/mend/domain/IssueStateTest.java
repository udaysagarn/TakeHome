package ai.devin.mend.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class IssueStateTest {

    @Test
    void terminalStatesOtherThanFailedAcceptNoTransitions() {
        Arrays.stream(IssueState.values())
                .filter(IssueState::isTerminal)
                .filter(state -> state != IssueState.FAILED)
                .forEach(state -> Arrays.stream(IssueState.values())
                        .forEach(next -> assertThat(state.canTransitionTo(next))
                                .as("%s -> %s", state, next)
                                .isFalse()));
    }

    @Test
    void failedOnlyReopensForARetryOrAnEscalation() {
        Arrays.stream(IssueState.values())
                .filter(next -> next != IssueState.DISPATCHED && next != IssueState.NEEDS_HUMAN)
                .forEach(next -> assertThat(IssueState.FAILED.canTransitionTo(next))
                        .as("FAILED -> %s", next)
                        .isFalse());
    }

    @Test
    void everyActiveStateCanBeCancelled() {
        Arrays.stream(IssueState.values())
                .filter(IssueState::isActive)
                .forEach(state -> assertThat(state.canTransitionTo(IssueState.CANCELLED))
                        .as("%s -> CANCELLED", state)
                        .isTrue());
    }

    @Test
    void happyPathIsReachable() {
        assertThat(IssueState.DISCOVERED.canTransitionTo(IssueState.CRITERIA_PENDING)).isTrue();
        assertThat(IssueState.CRITERIA_PENDING.canTransitionTo(IssueState.READY)).isTrue();
        assertThat(IssueState.READY.canTransitionTo(IssueState.DISPATCHED)).isTrue();
        assertThat(IssueState.DISPATCHED.canTransitionTo(IssueState.RUNNING)).isTrue();
        assertThat(IssueState.RUNNING.canTransitionTo(IssueState.PR_OPEN)).isTrue();
        assertThat(IssueState.PR_OPEN.canTransitionTo(IssueState.VERIFYING)).isTrue();
        assertThat(IssueState.VERIFYING.canTransitionTo(IssueState.SUCCEEDED)).isTrue();
    }

    @Test
    void anIssueCannotBeRemediatedWithoutPassingTheCriteriaGate() {
        assertThat(IssueState.DISCOVERED.canTransitionTo(IssueState.DISPATCHED)).isFalse();
        assertThat(IssueState.CRITERIA_PENDING.canTransitionTo(IssueState.DISPATCHED)).isFalse();
    }

    @Test
    void retryGoesThroughFailedAndCiFailureCanReopenWork() {
        assertThat(IssueState.FAILED.canTransitionTo(IssueState.DISPATCHED)).isTrue();
        assertThat(IssueState.VERIFYING.canTransitionTo(IssueState.RUNNING)).isTrue();
    }

    @Test
    void bucketsGroupStatesForTheDashboard() {
        assertThat(IssueState.RUNNING.bucket()).isEqualTo("in_flight");
        assertThat(IssueState.SUCCEEDED.bucket()).isEqualTo("succeeded");
        assertThat(IssueState.NEEDS_HUMAN.bucket()).isEqualTo("failed");
        assertThat(IssueState.NOT_A_CANDIDATE.bucket()).isEqualTo("excluded");
    }
}
