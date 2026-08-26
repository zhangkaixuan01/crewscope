package io.crewscope.server.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.infrastructure.event.projection.ProjectionSupervisor;
import io.crewscope.infrastructure.event.projection.ProjectionSupervisorSummary;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Conditional registration and low-cardinality Actuator details for M6-I02. */
class ProjectionSupervisorHealthIndicatorM6I02Test {

    @Test
    void healthBeanIsAbsentWithoutSupervisor() {
        runner().run(context -> context.assertThat()
                .hasNotFailed()
                .doesNotHaveBean(ProjectionSupervisorHealthIndicator.class));
    }

    @Test
    void reportsOnlyBoundedCountersAndFailsReadinessOnExpiredLease() {
        ProjectionSupervisor supervisor = mock(ProjectionSupervisor.class);
        when(supervisor.summary()).thenReturn(new ProjectionSupervisorSummary(2, 3, 1, 1, 4, 5));

        runner().withBean(ProjectionSupervisor.class, () -> supervisor).run(context -> {
            context.assertThat().hasNotFailed()
                    .hasSingleBean(ProjectionSupervisorHealthIndicator.class);
            var health = context.getBean(ProjectionSupervisorHealthIndicator.class).health();

            assertEquals("DOWN", health.getStatus().getCode());
            assertEquals(
                    Set.of(
                            "running",
                            "caughtUp",
                            "interrupted",
                            "expired",
                            "pendingRecovery",
                            "cleanupEligible"),
                    health.getDetails().keySet());
            assertFalse(health.getDetails().containsKey("organizationId"));
            assertFalse(health.getDetails().containsKey("projectionName"));
        });
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(ProjectionSupervisorHealthIndicator.class);
    }
}
