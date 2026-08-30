package ai.devin.mend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A GitHub repository menD has been asked to watch. */
@Entity
@Table(
        name = "repository",
        uniqueConstraints = @UniqueConstraint(columnNames = {"owner", "name"}))
public class Repository {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(nullable = false, length = 128)
    private String owner;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "default_branch", length = 128)
    private String defaultBranch;

    /** Which GitHub App installation can see this repository; null when running on a PAT. */
    @Column(name = "installation_id", length = 64)
    private String installationId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "access_state", nullable = false, length = 32)
    private AccessState accessState = AccessState.PENDING;

    @Column(name = "access_checked_at")
    private Instant accessCheckedAt;

    /** Why validation failed, phrased for a human who can fix it. */
    @Column(name = "access_error", length = 1024)
    private String accessError;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "index_state", nullable = false, length = 32)
    private IndexState indexState = IndexState.NEVER_INDEXED;

    /** The commit the persisted profile describes. */
    @Column(name = "indexed_sha", length = 64)
    private String indexedSha;

    @Column(name = "indexed_at")
    private Instant indexedAt;

    @Column(name = "index_error", length = 1024)
    private String indexError;

    @Column(name = "context_session_id", length = 128)
    private String contextSessionId;

    @Column(name = "context_session_url", length = 512)
    private String contextSessionUrl;

    /** Commits seen on the default branch since the profile was last rebuilt. */
    @ColumnDefault("0")
    @Column(name = "commits_since_index", nullable = false)
    private int commitsSinceIndex;

    @Column(name = "trigger_label", length = 128)
    private String triggerLabel;

    @ColumnDefault("true")
    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "acu_budget")
    private Integer acuBudget;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected Repository() {}

    public Repository(String owner, String name) {
        this.owner = owner;
        this.name = name;
    }

    /** {@code owner/name}, the form used everywhere a repository is identified by string. */
    public String slug() {
        return owner + "/" + name;
    }

    public String htmlUrl() {
        return "https://github.com/" + slug();
    }

    /** Ready to have issues ingested and Devin sessions dispatched against it. */
    public boolean isOperational() {
        return enabled && accessState.isUsable();
    }

    public void markValidated(String defaultBranch, String installationId) {
        this.defaultBranch = defaultBranch;
        this.installationId = installationId;
        this.accessState = AccessState.VALIDATED;
        this.accessError = null;
        this.accessCheckedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void markAccessFailure(AccessState state, String error) {
        this.accessState = state;
        this.accessError = error;
        this.accessCheckedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getOwner() {
        return owner;
    }

    public String getName() {
        return name;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public void setDefaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch;
    }

    public String getInstallationId() {
        return installationId;
    }

    public void setInstallationId(String installationId) {
        this.installationId = installationId;
    }

    public AccessState getAccessState() {
        return accessState;
    }

    public void setAccessState(AccessState accessState) {
        this.accessState = accessState;
    }

    public Instant getAccessCheckedAt() {
        return accessCheckedAt;
    }

    public void setAccessCheckedAt(Instant accessCheckedAt) {
        this.accessCheckedAt = accessCheckedAt;
    }

    public String getAccessError() {
        return accessError;
    }

    public void setAccessError(String accessError) {
        this.accessError = accessError;
    }

    public IndexState getIndexState() {
        return indexState;
    }

    public void setIndexState(IndexState indexState) {
        this.indexState = indexState;
    }

    public String getIndexedSha() {
        return indexedSha;
    }

    public void setIndexedSha(String indexedSha) {
        this.indexedSha = indexedSha;
    }

    public Instant getIndexedAt() {
        return indexedAt;
    }

    public void setIndexedAt(Instant indexedAt) {
        this.indexedAt = indexedAt;
    }

    public String getIndexError() {
        return indexError;
    }

    public void setIndexError(String indexError) {
        this.indexError = indexError;
    }

    public String getContextSessionId() {
        return contextSessionId;
    }

    public void setContextSessionId(String contextSessionId) {
        this.contextSessionId = contextSessionId;
    }

    public String getContextSessionUrl() {
        return contextSessionUrl;
    }

    public void setContextSessionUrl(String contextSessionUrl) {
        this.contextSessionUrl = contextSessionUrl;
    }

    public int getCommitsSinceIndex() {
        return commitsSinceIndex;
    }

    public void setCommitsSinceIndex(int commitsSinceIndex) {
        this.commitsSinceIndex = commitsSinceIndex;
    }

    public String getTriggerLabel() {
        return triggerLabel;
    }

    public void setTriggerLabel(String triggerLabel) {
        this.triggerLabel = triggerLabel;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Integer getAcuBudget() {
        return acuBudget;
    }

    public void setAcuBudget(Integer acuBudget) {
        this.acuBudget = acuBudget;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
