package io.crewscope.server;

import io.agentscope.spring.boot.agui.mvc.AgentscopeAguiMvcAutoConfiguration;
import io.agentscope.spring.boot.agui.webflux.AgentscopeAguiWebFluxAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
        scanBasePackages = "io.crewscope",
        exclude = {
            AgentscopeAguiMvcAutoConfiguration.class,
            AgentscopeAguiWebFluxAutoConfiguration.class
        })
@EnableScheduling
public class CrewScopeApplication {

    public static void main(String[] args) {
        SpringApplication.run(CrewScopeApplication.class, args);
    }
}
