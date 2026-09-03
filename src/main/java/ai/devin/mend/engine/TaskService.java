package ai.devin.mend.engine;

import ai.devin.mend.domain.IssueState;
import ai.devin.mend.domain.RemediationTask;
import ai.devin.mend.domain.TaskEvent;
import ai.devin.mend.domain.TaskEventRepository;
import ai.devin.mend.domain.TaskRepository;
import ai.devin.mend.metrics.MendMetrics;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only place state changes. Every transition is validated against the state machine, persisted,
 * audited and metered, which is what makes the dashboard trustworthy.
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final TaskRepository tasks;
    private final TaskEventRepository events;
    private final MendMetrics metrics;
    private final LeaseManager leases;
    private final Clock clock;

    public TaskService(
            TaskRepository tasks,
            TaskEventRepository events,
            MendMetrics metrics,
            LeaseManager leases,
            Clock clock) {
        this.tasks = tasks;
        this.events = events;
        this.metrics = metrics;
        this.leases = leases;
        this.clock = clock;
    }

    @Transactional
    public RemediationTask transition(RemediationTask task, IssueState next, String reason, String actor) {
        IssueState current = task.getState();
        if (current == next) {
            return task;
        }
        if (!current.canTransitionTo(next)) {
            throw new IllegalStateTransitionException(task.key(), current, next);
        }
        Instant now = clock.instant();
        task.setState(next);
        applyTimestamps(task, next, now);
        if (next.isTerminal()) {
            releaseLease(task);
        } else {
            applyEta(task, next, now);
        }
        RemediationTask saved = tasks.save(task);
        events.save(new TaskEvent(saved.getId(), saved.key(), current, next, reason, actor));
        metrics.recordTransition(saved, current, next, now);
        log.info(
                "state_transition task={} from={} to={} actor={} reason={}",
                saved.key(),
                current,
                next,
                actor,
                reason);
        return saved;
    }

    private void applyTimestamps(RemediationTask task, IssueState next, Instant now) {
        switch (next) {
            case CRITERIA_PENDING -> task.setCriteriaStartedAt(now);
            case READY -> task.setReadyAt(now);
            case DISPATCHED -> task.setDispatchedAt(now);
            case PR_OPEN -> {
                if (task.getPrOpenedAt() == null) {
                    task.setPrOpenedAt(now);
                }
            }
            default -> {
                if (next.isTerminal()) {
                    task.setCompletedAt(now);
                }
            }
        }
    }

    /**
     * Re-predicts completion whenever the task changes phase, so the lease carries a fresh promise
     * rather than the one made when the task was first claimed.
     */
    private void applyEta(RemediationTask task, IssueState next, Instant now) {
        task.setEtaAt(now.plus(leases.estimatedRemaining(next)));
    }

    /** A terminal task owes nothing: no owner, no lease, no promised completion. */
    private static void releaseLease(RemediationTask task) {
        task.setOwnerId(null);
        task.setLeaseExpiresAt(null);
        task.setLeaseAcquiredAt(null);
        task.setEtaAt(null);
    }

    @Transactional
    public RemediationTask save(RemediationTask task) {
        return tasks.save(task);
    }

    public static class IllegalStateTransitionException extends RuntimeException {
        public IllegalStateTransitionException(String key, IssueState from, IssueState to) {
            super("illegal transition for " + key + ": " + from + " -> " + to);
        }
    }
}
