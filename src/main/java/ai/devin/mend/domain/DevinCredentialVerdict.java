package ai.devin.mend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * What happened the last time menD used its Devin credential, so an operator can be told that the
 * key is rejected rather than discovering it as an empty board.
 *
 * <p>One row, id {@link #ID}: this is a property of the credential, not of a repository or a task.
 * It is persisted rather than held in memory because every replica shares the one credential, and
 * the replica serving the dashboard is not necessarily the one that tried to dispatch.
 *
 * <p>menD never probes the Devin API to fill this in. The verdict is a side effect of calls it was
 * already making, which is why a fresh instance reads as usable until the first real call.
 */
@Entity
@Table(name = "devin_credential")
public class DevinCredentialVerdict {

    /** The only row. */
    public static final long ID = 1L;

    @Id
    private Long id = ID;

    @Version
    private Long version;

    @Column(nullable = false)
    private boolean usable = true;

    /** Why the credential was refused, phrased for whoever can replace it; null while usable. */
    @Column(length = 1024)
    private String reason;

    @Column(name = "checked_at")
    private Instant checkedAt;

    public void reject(String reason) {
        this.usable = false;
        this.reason = reason;
        this.checkedAt = Instant.now();
    }

    public void accept() {
        this.usable = true;
        this.reason = null;
        this.checkedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public boolean isUsable() {
        return usable;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }
}
