package ai.devin.mend;

import ai.devin.mend.config.MendProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(MendProperties.class)
@EnableScheduling
public class MendApplication {

    public static void main(String[] args) {
        SpringApplication.run(MendApplication.class, args);
    }
}
