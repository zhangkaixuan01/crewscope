package io.crewscope.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "io.crewscope")
public class CrewScopeApplication {

    public static void main(String[] args) {
        SpringApplication.run(CrewScopeApplication.class, args);
    }
}
