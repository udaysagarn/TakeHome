package ai.devin.mend.config;

import java.time.Clock;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Lets JPA auditing stamp {@code createdAt}/{@code updatedAt} on persist and flush, reading the time
 * from the same {@link Clock} the engine uses, so an audited row and the transition that wrote it
 * never disagree about when "now" was.
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "clockDateTimeProvider")
public class AuditingConfig {

    @Bean
    public DateTimeProvider clockDateTimeProvider(Clock clock) {
        return () -> Optional.of(clock.instant());
    }
}
