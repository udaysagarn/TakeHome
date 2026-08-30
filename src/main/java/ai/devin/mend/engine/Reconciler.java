package ai.devin.mend.engine;

import ai.devin.mend.config.MendProperties;
import ai.devin.mend.domain.IssueState;
import ai.devin.mend.domain.RemediationTask;
import ai.devin.mend.domain.TaskRepository;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives every non-terminal task forward on a timer. The flow is level-triggered rather than
 * edge-triggered: state lives in the database, so a restart, a missed webhook or a transient API
 * failure costs one tick rather than losing the task.
 *
 * <p>Workers coordinate through leases rather than through in-process locks, so several replicas can
 * run the same loop: a task is only advanced by the worker that atomically claimed it, and a worker
 * that dies mid-task simply stops heartbeating until its lease expires and another worker resumes
 * the task from its persisted state.
 */
@Component
public class Reconciler {

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

    private static final List<IssueState> ACTIVE =
            Arrays.stream(IssueState.values()).filter(IssueState::isActive).toList();

    /**
     * FAILED is terminal for one attempt but re-dispatchable while the budget allows, so the loop
     * looks at it too: an inline retry the concurrency cap refused is picked up on a later tick
     * instead of stranding the task.
     */
    private static final List<IssueState> DRIVEN =
            Stream.concat(ACTIVE.stream(), Stream.of(IssueState.FAILED)).toList();

    /**
     * The states whose next step creates a Devin session, and so spends. A pause holds these and
     * lets every other state through, so a task already dispatched still reaches its verdict.
     */
    private static final Set<IssueState> BEGINS_NEW_SPEND =
            EnumSet.of(IssueState.DISCOVERED, IssueState.READY, IssueState.FAILED);

    private final TaskRepository tasks;
    private final Orchestrator orchestrator;
    private final LeaseManager leases;
    private final EngineControl control;
    private final CredentialGuard credentials;
    private final MendProperties props;

    public Reconciler(
            TaskRepository tasks,
            Orchestrator orchestrator,
            LeaseManager leases,
            EngineControl control,
            CredentialGuard credentials,
            MendProperties props) {
        this.tasks = tasks;
        this.orchestrator = orchestrator;
        this.leases = leases;
        this.control = control;
        this.credentials = credentials;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${mend.engine.reconcile-interval:PT15S}")
    public void tick() {
        if (control.off()) {
            return;
        }
        // A credential menD cannot use pauses it here rather than one dispatch at a time.
        credentials.enforce();
        boolean holdNewSpend = control.paused();
        List<RemediationTask> claimable = leases.claimable(DRIVEN).stream()
                .filter(task -> !holdNewSpend || !BEGINS_NEW_SPEND.contains(task.getState()))
                .filter(this::worthDriving)
                .toList();
        if (claimable.isEmpty()) {
            return;
        }
        log.debug("worker {} sees {} claimable task(s)", leases.workerId(), claimable.size());
        for (RemediationTask candidate : claimable) {
            advanceUnderLease(candidate);
        }
    }

    /** Releases leases this worker still holds so a rolling restart hands tasks over immediately. */
    @jakarta.annotation.PreDestroy
    public void releaseOwnedLeases() {
        for (RemediationTask task : tasks.findByStateIn(ACTIVE)) {
            if (leases.workerId().equals(task.getOwnerId())) {
                leases.release(task);
            }
        }
    }

    private boolean worthDriving(RemediationTask task) {
        return task.getState() != IssueState.FAILED
                || task.getAttempts() < props.getEngine().getMaxAttempts();
    }

    private void advanceUnderLease(RemediationTask candidate) {
        Optional<RemediationTask> claimed = leases.claim(candidate);
        if (claimed.isEmpty()) {
            return;
        }
        RemediationTask task = claimed.get();
        try {
            orchestrator.advance(task);
        } finally {
            finishLease(task.getId());
        }
    }

    /**
     * A task that reached a terminal state is unowned again; anything still active keeps the lease
     * extended so no other worker takes it over while this one is healthy.
     */
    private void finishLease(Long taskId) {
        RemediationTask fresh = tasks.findById(taskId).orElse(null);
        if (fresh == null) {
            return;
        }
        if (fresh.getState().isTerminal()) {
            leases.release(fresh);
            return;
        }
        if (!leases.renew(fresh)) {
            leases.forget(taskId);
            log.warn("worker {} lost the lease on {} while advancing it", leases.workerId(), fresh.key());
            return;
        }
        if (fresh.isOverdue(Instant.now())) {
            log.warn(
                    "task {} is past its predicted completion ({}) in state {}",
                    fresh.key(),
                    fresh.getEtaAt(),
                    fresh.getState());
        }
    }
}
