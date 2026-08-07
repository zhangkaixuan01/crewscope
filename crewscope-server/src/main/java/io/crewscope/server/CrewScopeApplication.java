package io.crewscope.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "io.crewscope")
@EnableScheduling
public class CrewScopeApplication {

    public static void main(String[] args) {
        SpringApplication.run(CrewScopeApplication.class, args);
    }
}
