package ai.devin.mend.domain;

/** Lifecycle of a repository's persisted codebase profile. */
public enum IndexState {
    NEVER_INDEXED("Not indexed"),
    INDEXING("Indexing"),
    INDEXED("Indexed"),
    STALE("Stale"),
    INDEX_FAILED("Indexing failed");

    private final String label;

    IndexState(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** A profile good enough to inject into a prompt; STALE still beats nothing. */
    public boolean hasProfile() {
        return this == INDEXED || this == STALE;
    }
}
