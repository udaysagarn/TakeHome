package ai.devin.mend.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one source of "now" for the engine. Production reads the system clock; tests replace the
 * bean with a fixed one so time-based behaviour can be asserted rather than waited for.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
