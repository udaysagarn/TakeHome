package ai.devin.mend.domain;

/**
 * Where a learning should ultimately live. menD can act on the first two itself; the rest are
 * recommendations surfaced to a human, because they change artefacts menD does not own.
 */
public enum RecommendedAction {

    /** menD injects it into the scoping and remediation prompts for the relevant repository. */
    PROMPT_PREAMBLE,

    /** Retire it: superseded, too narrow, or it stopped reducing review feedback. */
    RETIRE,

    /** Worth a Devin knowledge note or playbook so every session in the org benefits, not just menD. */
    DEVIN_KNOWLEDGE,

    /** Belongs in the repository's own instruction file (AGENTS.md, CLAUDE.md, CONTRIBUTING.md). */
    REPO_INSTRUCTIONS,

    /** The lesson is about menD itself, e.g. the candidacy gate accepting the wrong kind of issue. */
    MEND_BACKLOG;

    public String label() {
        return switch (this) {
            case PROMPT_PREAMBLE -> "inject into this repository's sessions";
            case RETIRE -> "retire";
            case DEVIN_KNOWLEDGE -> "promote to a Devin knowledge note for the whole org";
            case REPO_INSTRUCTIONS -> "add to the repository's own instruction file";
            case MEND_BACKLOG -> "file against menD itself";
        };
    }
}
