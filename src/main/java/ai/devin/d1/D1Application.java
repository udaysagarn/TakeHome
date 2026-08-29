package ai.devin.d1;

import ai.devin.d1.config.D1Properties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(D1Properties.class)
@EnableScheduling
public class D1Application {

    public static void main(String[] args) {
        SpringApplication.run(D1Application.class, args);
    }
}
