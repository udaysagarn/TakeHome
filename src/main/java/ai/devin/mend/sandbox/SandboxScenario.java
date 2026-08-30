package ai.devin.mend.sandbox;

/**
 * What the fake GitHub and the fake Devin should do with a simulated issue. Each scenario exercises
 * one path through {@link ai.devin.mend.domain.IssueState}, so a contributor can watch the whole
 * control plane run without credentials, network access or ACU spend.
 */
public enum SandboxScenario {

    /** Scoped, fixed, and proved by the repository's own checks: DISCOVERED → SUCCEEDED. */
    CLEAN_FIX("a well-formed issue that Devin fixes and the repository's CI proves"),

    /** The candidacy gate refuses it before a remediation session is ever created. */
    NOT_A_CANDIDATE("an issue that needs a human decision, so menD declines to spend a session on it"),

    /** A pull request lands, but nothing independent can prove it: SUCCEEDED is not claimed. */
    UNVERIFIED("a repository with no CI and no verifier, where the fix lands as UNVERIFIED"),

    /** A reviewer asks for changes; the feedback goes back to the session and produces lessons. */
    REVIEW_THEN_FIX("a reviewer rejects the pull request once, then the fix lands and teaches menD");

    private final String description;

    SandboxScenario(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
