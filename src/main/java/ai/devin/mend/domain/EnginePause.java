package ai.devin.mend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * Whether an operator has paused menD, and who did it.
 *
 * <p>One row, id {@link #ID}: pausing is a property of the instance rather than of a repository or a
 * task. It is persisted rather than held in memory for the two reasons the Devin credential verdict
 * is — every replica has to observe the same answer, and the replica serving the dashboard is not
 * necessarily the one that would dispatch — plus one of its own: a pause that a restart silently
 * lifted would resume spending ACUs behind the operator's back.
 *
 * <p>This is a runtime control, not configuration. {@code mend.engine.enabled=false} remains the
 * startup kill switch and cannot be undone from the UI.
 */
@Entity
@Table(name = "engine_pause")
public class EnginePause {

    /** The only row. */
    public static final long ID = 1L;

    @Id
    private Long id = ID;

    @Version
    private Long version;

    @Column(nullable = false)
    private boolean paused = false;

    /** Why work is held, phrased for whoever finds the board stopped; null while running. */
    @Column(length = 1024)
    private String reason;

    /** Who last changed it. Nothing authenticates the dashboard, so this is a claim, not proof. */
    @Column(length = 255)
    private String actor;

    @Column(name = "changed_at")
    private Instant changedAt;

    public void pause(String actor, String reason) {
        this.paused = true;
        this.actor = actor;
        this.reason = reason;
        this.changedAt = Instant.now();
    }

    public void resume(String actor) {
        this.paused = false;
        this.actor = actor;
        this.reason = null;
        this.changedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public boolean isPaused() {
        return paused;
    }

    public String getReason() {
        return reason;
    }

    public String getActor() {
        return actor;
    }

    public Instant getChangedAt() {
        return changedAt;
    }
}
