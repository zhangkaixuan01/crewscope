package io.crewscope.server;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.spring.boot.agui.mvc.AgentscopeAguiMvcAutoConfiguration;
import io.agentscope.spring.boot.agui.webflux.AgentscopeAguiWebFluxAutoConfiguration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Proves the generic path/header-routed AgentScope endpoints are absent from CrewScope. */
class CrewScopeApplicationAguiBoundaryTest {

    @Test
    void genericMvcAndWebFluxAguiRoutesAreExplicitlyDisabled() {
        SpringBootApplication annotation =
                CrewScopeApplication.class.getAnnotation(SpringBootApplication.class);
        Set<Class<?>> exclusions = Set.of(annotation.exclude());

        assertTrue(exclusions.contains(AgentscopeAguiMvcAutoConfiguration.class));
        assertTrue(exclusions.contains(AgentscopeAguiWebFluxAutoConfiguration.class));
    }
}
