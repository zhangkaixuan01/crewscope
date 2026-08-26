package io.crewscope.server.config.application;

import static org.mockito.Mockito.mock;

import io.crewscope.application.operations.OperationsHealthQueryPort;
import io.crewscope.application.operations.OperationsHealthService;
import io.crewscope.application.operations.OperationsHealthThresholds;
import io.crewscope.application.operations.OperationsRecoveryRepository;
import io.crewscope.application.operations.OperationsRecoveryService;
import io.crewscope.application.projection.ProjectionAdministration;
import io.crewscope.application.projection.ProjectionAdministrationRepository;
import io.crewscope.application.projection.ProjectionAdministrationService;
import io.crewscope.application.projection.ProjectionSnapshotVerifier;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.shared.time.TimeProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Conditional assembly and fail-closed threshold configuration tests for M6-E07. */
class OperationsApplicationConfigurationM6E07Test {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OperationsApplicationConfiguration.class);

    @Test
    void exposesOnlyValidatedThresholdsUntilInfrastructurePortsExist() {
        runner.run(context -> context.assertThat()
                .hasNotFailed()
                .hasSingleBean(OperationsHealthThresholds.class)
                .doesNotHaveBean(OperationsHealthService.class)
                .doesNotHaveBean(OperationsRecoveryService.class)
                .doesNotHaveBean(ProjectionAdministrationService.class));
    }

    @Test
    void wiresHealthRecoveryAndProjectionAdministrationWhenPortsExist() {
        runner.withBean(WorkItemAccessPolicy.class, () -> mock(WorkItemAccessPolicy.class))
                .withBean(ProjectionAdministration.class,
                        () -> mock(ProjectionAdministration.class))
                .withBean(OperationsHealthQueryPort.class,
                        () -> mock(OperationsHealthQueryPort.class))
                .withBean(OperationsRecoveryRepository.class,
                        () -> mock(OperationsRecoveryRepository.class))
                .withBean(ProjectionAdministrationRepository.class,
                        () -> mock(ProjectionAdministrationRepository.class))
                .withBean(ProjectionSnapshotVerifier.class,
                        () -> mock(ProjectionSnapshotVerifier.class))
                .withBean(TransactionExecutor.class, () -> mock(TransactionExecutor.class))
                .withBean(TimeProvider.class, () -> mock(TimeProvider.class))
                .run(context -> context.assertThat()
                        .hasNotFailed()
                        .hasSingleBean(OperationsHealthService.class)
                        .hasSingleBean(OperationsRecoveryService.class)
                        .hasSingleBean(ProjectionAdministrationService.class));
    }

    @Test
    void invalidThresholdOrderFailsStartup() {
        runner.withPropertyValues(
                        "crewscope.operations-health.outbox.degraded-after=5m",
                        "crewscope.operations-health.outbox.attention-after=1m")
                .run(context -> context.assertThat().hasFailed());
    }
}
