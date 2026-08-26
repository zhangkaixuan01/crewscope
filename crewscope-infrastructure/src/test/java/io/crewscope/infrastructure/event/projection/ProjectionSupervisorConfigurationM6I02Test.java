package io.crewscope.infrastructure.event.projection;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Fail-closed Spring assembly and startup-recovery contract for M6-I02. */
class ProjectionSupervisorConfigurationM6I02Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-26T10:00:00Z");

    @Test
    void disabledSupervisorCreatesNoRuntimeOrScheduler() {
        runner().withPropertyValues("crewscope.projection.supervisor.enabled=false")
                .run(context -> context.assertThat()
                        .hasNotFailed()
                        .doesNotHaveBean(ProjectionSupervisor.class)
                        .doesNotHaveBean(ProjectionSupervisorScheduler.class));
    }

    @Test
    void enabledSupervisorRunsStartupRecoveryAndCreatesScheduler() {
        JdbcProjectionSupervisorStore store = mock(JdbcProjectionSupervisorStore.class);
        when(store.recoverExpired(any())).thenReturn(0);

        runner()
                .withPropertyValues(
                        "crewscope.projection.supervisor.enabled=true",
                        "crewscope.projection.supervisor.instance-id=test-node")
                .withBean(JdbcProjectionSupervisorStore.class, () -> store)
                .withBean(
                        GenerationAwareProjectionRunnerFactory.class,
                        () -> mock(GenerationAwareProjectionRunnerFactory.class))
                .withBean(
                        JdbcProjectionEventHistoryStore.class,
                        () -> mock(JdbcProjectionEventHistoryStore.class))
                .withBean(TimeProvider.class, () -> () -> NOW)
                .run(context -> {
                    context.assertThat()
                            .hasNotFailed()
                            .hasSingleBean(ProjectionSupervisor.class)
                            .hasSingleBean(ProjectionSupervisorScheduler.class);
                    verify(store).recoverExpired(NOW);
                });
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(ProjectionSupervisorConfiguration.class);
    }
}
