package io.crewscope.server.config.application;

import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.memory.AgentMemoryRepository;
import io.crewscope.application.memory.AgentMemoryService;
import io.crewscope.application.memory.AgentMemorySweeper;
import io.crewscope.application.memory.DefaultAgentMemoryPolicyCatalog;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.server.config.runtime.WorkerCapableProfileCondition;
import io.crewscope.server.observability.AgentMemoryHealthIndicator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * Server composition for the agent assistant memory lifecycle (M10-I02a). The service and
 * the code-catalog policy always assemble — member view and clear stay available with the
 * switch off. The deployment switch gates exactly two things: new model-facing writes
 * (inside the service) and this health projection. The TTL sweeper is worker-profile only
 * and intentionally ungated by the switch.
 */
@Configuration(proxyBeanMethods = false)
public class AgentMemoryConfiguration {

    @Bean
    DefaultAgentMemoryPolicyCatalog defaultAgentMemoryPolicyCatalog() {
        return new DefaultAgentMemoryPolicyCatalog();
    }

    @Bean
    AgentMemoryService agentMemoryService(
            AgentMemoryRepository memory,
            AgentProfileRepository profiles,
            AgentConfigurationRepository configurations,
            DefaultAgentMemoryPolicyCatalog policies,
            TeamRepository teams,
            TeamMembershipQuery memberships,
            TransactionExecutor transactions,
            TimeProvider timeProvider,
            @Value("${crewscope.memory.enabled:false}") boolean memoryEnabled) {
        return new AgentMemoryService(
                memory,
                profiles,
                configurations,
                policies,
                teams,
                memberships,
                transactions,
                timeProvider,
                memoryEnabled);
    }

    /** Same gate as the write path: a switched-off deployment reports no memory health. */
    @Bean
    @ConditionalOnProperty(
            name = "crewscope.memory.enabled",
            havingValue = "true",
            matchIfMissing = false)
    AgentMemoryHealthIndicator agentMemoryHealthIndicator() {
        return new AgentMemoryHealthIndicator();
    }

    @Bean
    @Conditional(WorkerCapableProfileCondition.class)
    AgentMemorySweeper agentMemorySweeper(
            AgentMemoryRepository memory, TimeProvider timeProvider) {
        return new AgentMemorySweeper(memory, timeProvider);
    }

    @Bean
    @Conditional(WorkerCapableProfileCondition.class)
    AgentMemorySweepScheduler agentMemorySweepScheduler(AgentMemorySweeper sweeper) {
        return new AgentMemorySweepScheduler(sweeper);
    }
}
