package ai.devin.mend.domain;

/** Whether a lesson is about one codebase or about how Devin should work anywhere. */
public enum LearningScope {

    /** Specific to a repository: its conventions, its reviewers, its test layout. */
    REPO,

    /** True beyond this repository, so it is worth pushing further than menD's own prompts. */
    GENERAL
}
