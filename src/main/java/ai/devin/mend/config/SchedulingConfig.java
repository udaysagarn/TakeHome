package ai.devin.mend.config;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;

/**
 * The scheduled loops run on a platform-thread pool sized by {@code
 * spring.task.scheduling.pool.size}, not on the virtual-thread scheduler Boot would pick because
 * {@code spring.threads.virtual.enabled} is set.
 *
 * <p>{@code SimpleAsyncTaskScheduler} runs a {@code fixedDelay} task on its single scheduler thread
 * — the delay is measured from completion, so it cannot hand the body to a fresh virtual thread and
 * still know when it finished. Every loop here is {@code fixedDelay}, so that scheduler serialises
 * all five: the lease heartbeat would wait behind a reconcile pass blocked in a Devin or GitHub
 * call, and a healthy worker's lease would expire.
 */
@Configuration
public class SchedulingConfig {

    @Bean
    public TaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }
}
