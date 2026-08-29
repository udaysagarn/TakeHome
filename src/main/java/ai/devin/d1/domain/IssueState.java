package ai.devin.d1.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * States a single issue moves through inside the control plane.
 *
 * <p>The full transition table lives in {@link #canTransitionTo(IssueState)}; it is the single
 * authority for what the reconciler is allowed to do, so an illegal transition fails loudly instead
 * of corrupting the pipeline.
 */
public enum IssueState {

    /** Trigger label observed; nothing spent yet. */
    DISCOVERED,

    /** Deterministic pre-filters passed; a scoping Devin session is establishing success criteria. */
    CRITERIA_PENDING,

    /** Verifiable success criteria exist; queued for remediation. */
    READY,

    /** A remediation Devin session has been created. */
    DISPATCHED,

    /** The remediation session is working. */
    RUNNING,

    /** The session reported {@code blocked}; the control plane nudges it. */
    BLOCKED,

    /** The session produced a pull request. */
    PR_OPEN,

    /** CI is running against the pull request. */
    VERIFYING,

    /** Terminal: PR open with green CI and acceptance criteria asserted. */
    SUCCEEDED,

    /** Terminal for this attempt: no PR, or CI red after the attempt budget is exhausted. */
    FAILED,

    /** Terminal: success criteria could not be established, so Devin should not attempt it. */
    NOT_A_CANDIDATE,

    /** Terminal: escalated to a human after exhausting nudges or attempts. */
    NEEDS_HUMAN,

    /** Terminal: cancelled from the dashboard or by label removal. */
    CANCELLED;

    private static final Set<IssueState> TERMINAL =
            EnumSet.of(SUCCEEDED, FAILED, NOT_A_CANDIDATE, NEEDS_HUMAN, CANCELLED);

    /**
     * True when the issue has an outcome and the reconciler stops driving it. {@link #FAILED} is
     * terminal in that sense but still re-dispatchable while the attempt budget allows a retry.
     */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean isActive() {
        return !isTerminal();
    }

    /** True when a Devin session is expected to be alive in this state. */
    public boolean holdsSession() {
        return this == CRITERIA_PENDING || this == DISPATCHED || this == RUNNING || this == BLOCKED;
    }

    public boolean canTransitionTo(IssueState next) {
        if (next == CANCELLED) {
            return isActive();
        }
        return switch (this) {
            case DISCOVERED -> next == CRITERIA_PENDING || next == READY || next == NOT_A_CANDIDATE;
            case CRITERIA_PENDING -> next == READY || next == NOT_A_CANDIDATE || next == NEEDS_HUMAN;
            case READY -> next == DISPATCHED || next == NEEDS_HUMAN;
            case DISPATCHED -> next == RUNNING || next == BLOCKED || next == PR_OPEN || next == FAILED;
            case RUNNING -> next == BLOCKED || next == PR_OPEN || next == FAILED;
            case BLOCKED -> next == RUNNING || next == PR_OPEN || next == NEEDS_HUMAN || next == FAILED;
            case PR_OPEN -> next == VERIFYING || next == SUCCEEDED || next == FAILED;
            case VERIFYING -> next == SUCCEEDED || next == FAILED || next == RUNNING;
            case FAILED -> next == DISPATCHED || next == NEEDS_HUMAN;
            case SUCCEEDED, NOT_A_CANDIDATE, NEEDS_HUMAN, CANCELLED -> false;
        };
    }

    /** Grouping used by the dashboard's KPI strip. */
    public String bucket() {
        if (this == SUCCEEDED) {
            return "succeeded";
        }
        if (this == FAILED || this == NEEDS_HUMAN) {
            return "failed";
        }
        if (this == NOT_A_CANDIDATE || this == CANCELLED) {
            return "excluded";
        }
        return "in_flight";
    }
}
