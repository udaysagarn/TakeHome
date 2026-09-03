package ai.devin.mend.exception;

import ai.devin.mend.domain.IssueState;

/** A transition {@link IssueState#canTransitionTo} forbids; the task is left untouched. */
public class IllegalStateTransitionException extends RuntimeException {
    public IllegalStateTransitionException(String key, IssueState from, IssueState to) {
        super("illegal transition for " + key + ": " + from + " -> " + to);
    }
}
