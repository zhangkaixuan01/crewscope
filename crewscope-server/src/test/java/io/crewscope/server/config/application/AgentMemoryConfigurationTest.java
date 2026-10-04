package io.crewscope.server.config.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

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
import io.crewscope.server.observability.AgentMemoryHealthIndicator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * M10-I02a assembly contract: the service and the code catalog always exist so member view
 * and clear survive a switch-off; the health projection shares the deployment switch (off
 * = no indicator, never DOWN); the TTL sweeper follows the worker profile and explicitly
 * not the switch.
 */
class AgentMemoryConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AgentMemoryConfiguration.class)
            .withBean(AgentMemoryRepository.class, () -> mock(AgentMemoryRepository.class))
            .withBean(AgentProfileRepository.class, () -> mock(AgentProfileRepository.class))
            .withBean(AgentConfigurationRepository.class,
                    () -> mock(AgentConfigurationRepository.class))
            .withBean(TeamRepository.class, () -> mock(TeamRepository.class))
            .withBean(TeamMembershipQuery.class, () -> mock(TeamMembershipQuery.class))
            .withBean(TransactionExecutor.class, AgentMemoryConfigurationTest::directTransactions)
            .withBean(TimeProvider.class, () -> () -> NOW);

    @Test
    void theDefaultDeploymentAssemblesTheLifecycleWithoutTheIndicator() {
        runner.run(context -> context.assertThat()
                .hasNotFailed()
                .hasSingleBean(AgentMemoryService.class)
                .hasSingleBean(DefaultAgentMemoryPolicyCatalog.class)
                .hasSingleBean(AgentMemorySweeper.class)
                .hasSingleBean(AgentMemorySweepScheduler.class)
                .doesNotHaveBean(AgentMemoryHealthIndicator.class));
    }

    @Test
    void theSwitchAssemblesTheHealthProjectionUpWithTheFrozenPolicy() {
        runner.withPropertyValues("crewscope.memory.enabled=true")
                .run(context -> {
                    context.assertThat()
                            .hasNotFailed()
                            .hasSingleBean(AgentMemoryHealthIndicator.class);
                    AgentMemoryHealthIndicator indicator =
                            context.getBean(AgentMemoryHealthIndicator.class);
                    assertEquals(Status.UP, indicator.health().getStatus());
                    String details = indicator.health().getDetails().toString();
                    assertTrue(details.contains("ttlDays=90"), details);
                    assertTrue(details.contains("maxEntriesPerOwner=100"), details);
                    assertTrue(details.contains("valueMaxBytes=1024"), details);
                });
    }

    @Test
    void anApiRoleSkipsTheSweeperButKeepsMemberAccess() {
        runner.withPropertyValues("crewscope.runtime.execution-profile=api")
                .run(context -> context.assertThat()
                        .hasNotFailed()
                        .hasSingleBean(AgentMemoryService.class)
                        .doesNotHaveBean(AgentMemorySweeper.class)
                        .doesNotHaveBean(AgentMemorySweepScheduler.class)
                        .doesNotHaveBean(AgentMemoryHealthIndicator.class));
    }

    @Test
    void aSwitchedOffWorkerStillRunsTheTtlSweep() {
        runner.withPropertyValues("crewscope.runtime.execution-profile=worker")
                .run(context -> context.assertThat()
                        .hasNotFailed()
                        .hasSingleBean(AgentMemorySweeper.class)
                        .hasSingleBean(AgentMemorySweepScheduler.class)
                        .doesNotHaveBean(AgentMemoryHealthIndicator.class));
    }

    private static final io.crewscope.domain.shared.time.UtcTimestamp NOW =
            io.crewscope.domain.shared.time.UtcTimestamp.parse("2026-10-04T11:00:00Z");

    private static TransactionExecutor directTransactions() {
        return new TransactionExecutor() {
            @Override
            public <T> T required(java.util.function.Supplier<T> operation) {
                return operation.get();
            }
        };
    }
}
