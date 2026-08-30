package ai.devin.mend.domain;

/** Lifecycle of a learning. Retired learnings are kept for the audit trail but never injected. */
public enum LearningStatus {
    ACTIVE,
    RETIRED
}
