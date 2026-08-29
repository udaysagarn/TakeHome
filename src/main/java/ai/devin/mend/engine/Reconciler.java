package ai.devin.mend.engine;

import ai.devin.mend.config.MendProperties;
import ai.devin.mend.domain.IssueState;
import ai.devin.mend.domain.RemediationTask;
import ai.devin.mend.domain.TaskRepository;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives every non-terminal task forward on a timer. The pipeline is level-triggered rather than
 * edge-triggered: state lives in the database, so a restart, a missed webhook or a transient API
 * failure costs one tick rather than losing the task.
 */
@Component
public class Reconciler {

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

    private static final Set<IssueState> ACTIVE = EnumSet.copyOf(
            Arrays.stream(IssueState.values()).filter(IssueState::isActive).toList());

    private final TaskRepository tasks;
    private final Orchestrator orchestrator;
    private final MendProperties props;

    public Reconciler(TaskRepository tasks, Orchestrator orchestrator, MendProperties props) {
        this.tasks = tasks;
        this.orchestrator = orchestrator;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${mend.engine.reconcile-interval:PT15S}")
    public void tick() {
        if (!props.getEngine().isEnabled()) {
            return;
        }
        List<RemediationTask> active = tasks.findByStateIn(ACTIVE);
        if (active.isEmpty()) {
            return;
        }
        log.debug("reconciling {} active task(s)", active.size());
        for (RemediationTask task : active) {
            orchestrator.advance(task);
        }
    }
}
