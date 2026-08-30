package ai.devin.mend.engine;

import ai.devin.mend.config.MendProperties;
import ai.devin.mend.domain.IssueState;
import ai.devin.mend.domain.RemediationTask;
import ai.devin.mend.domain.TaskRepository;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cooperative ownership of tasks across workers. A worker must hold an unexpired lease on a task
 * before touching it, must heartbeat while it works, and loses the task automatically if it dies:
 * the lease simply expires and any other worker reclaims it. Because all progress is in the task
 * row, the reclaiming worker resumes rather than restarts.
 */
@Service
public class LeaseManager {

    private static final Logger log = LoggerFactory.getLogger(LeaseManager.class);

    private final TaskRepository tasks;
    private final MendProperties props;
    private final String workerId;

    /** Tasks this worker is actively advancing; the heartbeat keeps their leases alive. */
    private final Set<Long> held = ConcurrentHashMap.newKeySet();

    public LeaseManager(TaskRepository tasks, MendProperties props) {
        this.tasks = tasks;
        this.props = props;
        this.workerId = buildWorkerId();
    }

    /** Stable for the life of this process, unique across processes on the same host. */
    public String workerId() {
        return workerId;
    }

    public List<RemediationTask> claimable(List<IssueState> states) {
        return tasks.findClaimable(states, workerId, Instant.now());
    }

    /**
     * Atomically takes the lease. Returns the freshly read task when this worker won the race, or
     * empty when another worker holds a live lease.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<RemediationTask> claim(RemediationTask task) {
        Instant now = Instant.now();
        Duration ttl = props.getEngine().getLeaseDuration();
        Instant eta = now.plus(estimatedRemaining(task.getState()));
        int claimed = tasks.claim(task.getId(), workerId, now, now.plus(ttl), eta);
        if (claimed == 0) {
            return Optional.empty();
        }
        held.add(task.getId());
        RemediationTask fresh = tasks.findById(task.getId()).orElse(null);
        if (fresh != null && fresh.getLeaseTakeovers() > task.getLeaseTakeovers()) {
            log.warn(
                    "reclaimed expired lease task={} previousOwner={} takeovers={}",
                    fresh.key(),
                    task.getOwnerId(),
                    fresh.getLeaseTakeovers());
        }
        return Optional.ofNullable(fresh);
    }

    /** Extends the lease. False means the lease was lost and the caller must stop working. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean renew(RemediationTask task) {
        Instant now = Instant.now();
        return tasks.renew(task.getId(), workerId, now, now.plus(props.getEngine().getLeaseDuration())) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(RemediationTask task) {
        held.remove(task.getId());
        tasks.release(task.getId(), workerId);
    }

    /** Stops heartbeating a task without releasing it, e.g. after the lease was lost. */
    public void forget(Long taskId) {
        held.remove(taskId);
    }

    /**
     * Extends every lease this worker holds, independently of the reconcile loop, so a task whose
     * Devin call outlives the lease duration is not stolen from a healthy worker.
     */
    @Scheduled(fixedDelayString = "${mend.engine.heartbeat-interval:PT30S}")
    @Transactional
    public void heartbeat() {
        if (held.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        Instant expiresAt = now.plus(props.getEngine().getLeaseDuration());
        for (Long id : held) {
            if (tasks.renew(id, workerId, now, expiresAt) == 0) {
                log.warn("worker {} no longer owns task id={}; stopping heartbeat", workerId, id);
                held.remove(id);
            }
        }
    }

    /**
     * How long this worker predicts the task still needs. It is a commitment, not a guess with no
     * consequences: once {@code eta_at} passes the dashboard shows the task as overdue.
     */
    public Duration estimatedRemaining(IssueState state) {
        MendProperties.Engine engine = props.getEngine();
        return switch (state) {
            case DISCOVERED, CRITERIA_PENDING -> engine.getCriteriaEta();
            case READY, DISPATCHED, RUNNING, BLOCKED, FAILED -> engine.getSessionTimeout();
            case PR_OPEN, VERIFYING -> engine.getVerifyEta();
            default -> Duration.ZERO;
        };
    }

    private static String buildWorkerId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown-host";
        }
        return host + "/" + ProcessHandle.current().pid() + "/" + UUID.randomUUID().toString().substring(0, 8);
    }
}
