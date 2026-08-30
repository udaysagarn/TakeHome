package ai.devin.mend.engine;

import ai.devin.mend.config.MendProperties;
import ai.devin.mend.domain.EnginePause;
import ai.devin.mend.domain.EnginePauses;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The pause switch an operator can reach: whether menD is allowed to start work that spends.
 *
 * <p>Two different things can stop the engine and they are not interchangeable.
 * {@code mend.engine.enabled=false} is configuration, read once at startup, and is the kill switch —
 * nothing runs and the dashboard cannot lift it. A pause is runtime state, held in
 * {@link EnginePause}, and is what the button in the navigation writes.
 *
 * <p>A pause holds back only the steps that begin new spend. Work already dispatched to Devin has
 * been paid for, so it keeps being polled to completion: freezing it would leave a session running
 * with menD no longer reading it back, which is the one outcome worse than either state.
 */
@Service
public class EngineControl {

    private static final Logger log = LoggerFactory.getLogger(EngineControl.class);

    /** What the engine is doing, as the dashboard and the API report it. */
    public record Status(boolean paused, boolean off, String reason, String actor, Instant since) {

        /** True only when menD may start new work. */
        public boolean running() {
            return !paused && !off;
        }
    }

    private final EnginePauses pauses;
    private final MendProperties props;

    public EngineControl(EnginePauses pauses, MendProperties props) {
        this.pauses = pauses;
        this.props = props;
    }

    /** Disabled in configuration: the loops do not run at all until the instance is restarted. */
    public boolean off() {
        return !props.getEngine().isEnabled();
    }

    @Transactional(readOnly = true)
    public boolean paused() {
        return pauses.findById(EnginePause.ID).map(EnginePause::isPaused).orElse(false);
    }

    /** Whether a step that would create a Devin session, and so spend ACUs, may go ahead. */
    public boolean newWorkAllowed() {
        return !off() && !paused();
    }

    @Transactional(readOnly = true)
    public Status status() {
        return pauses.findById(EnginePause.ID).map(this::statusOf).orElseGet(() -> statusOf(new EnginePause()));
    }

    @Transactional
    public Status pause(String actor, String reason) {
        EnginePause row = row();
        if (!row.isPaused()) {
            row.pause(actor, reason);
            log.info("engine paused by {}{}", actor, reason == null ? "" : ": " + reason);
        }
        return statusOf(pauses.saveAndFlush(row));
    }

    @Transactional
    public Status resume(String actor) {
        EnginePause row = row();
        if (row.isPaused()) {
            row.resume(actor);
            log.info("engine resumed by {}", actor);
        }
        return statusOf(pauses.saveAndFlush(row));
    }

    private EnginePause row() {
        return pauses.findById(EnginePause.ID).orElseGet(EnginePause::new);
    }

    private Status statusOf(EnginePause row) {
        return new Status(row.isPaused(), off(), row.getReason(), row.getActor(), row.getChangedAt());
    }
}
