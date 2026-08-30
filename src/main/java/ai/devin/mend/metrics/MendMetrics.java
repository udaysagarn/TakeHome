package ai.devin.mend.metrics;

import ai.devin.mend.domain.IssueState;
import ai.devin.mend.domain.RemediationTask;
import ai.devin.mend.domain.TaskRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Prometheus-facing instrumentation for the pipeline. */
@Component
public class MendMetrics {

    private final MeterRegistry registry;
    private final Timer timeToPr;
    private final Timer timeToOutcome;

    public MendMetrics(MeterRegistry registry, TaskRepository tasks) {
        this.registry = registry;
        this.timeToPr = Timer.builder("mend.time.to.pr")
                .description("Wall clock from issue discovery to pull request opened")
                .publishPercentiles(0.5, 0.9)
                .register(registry);
        this.timeToOutcome = Timer.builder("mend.time.to.outcome")
                .description("Wall clock from issue discovery to a terminal state")
                .publishPercentiles(0.5, 0.9)
                .register(registry);

        for (IssueState state : IssueState.values()) {
            registry.gauge(
                    "mend.issues",
                    java.util.List.of(io.micrometer.core.instrument.Tag.of("state", state.name())),
                    state,
                    s -> tasks.countByStateIn(EnumSet.of((IssueState) s)));
        }
        registry.gauge(
                "mend.sessions.active",
                tasks,
                t -> t.countByStateIn(EnumSet.of(
                        IssueState.CRITERIA_PENDING, IssueState.DISPATCHED, IssueState.RUNNING, IssueState.BLOCKED)));
    }

    public void recordTransition(RemediationTask task, IssueState from, IssueState to) {
        Counter.builder("mend.transitions")
                .tag("from", from.name())
                .tag("to", to.name())
                .register(registry)
                .increment();

        if (to == IssueState.PR_OPEN) {
            Duration d = task.timeToPr();
            if (d != null) {
                timeToPr.record(d.toMillis(), TimeUnit.MILLISECONDS);
            }
        }
        if (to.isTerminal()) {
            timeToOutcome.record(task.elapsed().toMillis(), TimeUnit.MILLISECONDS);
            Counter.builder("mend.outcomes").tag("outcome", to.name()).register(registry).increment();
        }
    }

    public void recordAcuBudget(int acu, String kind) {
        Counter.builder("mend.acu.budget").tag("kind", kind).register(registry).increment(acu);
    }

    public void recordApiCall(String api, String operation, boolean success) {
        Counter.builder("mend.api.calls")
                .tag("api", api)
                .tag("operation", operation)
                .tag("success", Boolean.toString(success))
                .register(registry)
                .increment();
    }
}
