package ai.devin.mend.domain;

/** Whether menD's GitHub identity can actually do its job on a registered repository. */
public enum AccessState {
    /** Registered but not yet validated. */
    PENDING("Validating access"),
    /** Readable and writable: issues, comments, labels and pull requests all reachable. */
    VALIDATED("Access confirmed"),
    /** The repository does not exist, or menD's identity cannot see it at all. */
    NO_ACCESS("Not visible to menD"),
    /** Visible, but the installation is missing a permission menD needs. */
    MISSING_PERMISSION("Missing a required permission");

    private final String label;

    AccessState(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public boolean isUsable() {
        return this == VALIDATED;
    }
}
