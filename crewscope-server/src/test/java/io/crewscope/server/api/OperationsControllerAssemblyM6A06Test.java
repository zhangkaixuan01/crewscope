package io.crewscope.server.api;

import static org.mockito.Mockito.mock;

import io.crewscope.application.operations.OperationsHealthService;
import io.crewscope.application.operations.OperationsRecoveryService;
import io.crewscope.application.projection.ProjectionAdministrationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Ensures the administration API cannot start with a partially assembled operations stack. */
class OperationsControllerAssemblyM6A06Test {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OperationsController.class);

    @Test
    void missingOperationsServicesFailClosed() {
        runner.run(context -> context.assertThat().hasFailed());
    }

    @Test
    void completeOperationsStackCreatesExactlyOneController() {
        runner.withBean(OperationsHealthService.class, () -> mock(OperationsHealthService.class))
                .withBean(
                        OperationsRecoveryService.class,
                        () -> mock(OperationsRecoveryService.class))
                .withBean(
                        ProjectionAdministrationService.class,
                        () -> mock(ProjectionAdministrationService.class))
                .withBean(
                        TeamRequestIdentityResolver.class,
                        () -> mock(TeamRequestIdentityResolver.class))
                .run(context -> context.assertThat()
                        .hasNotFailed()
                        .hasSingleBean(OperationsController.class));
    }
}
