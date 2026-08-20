package io.crewscope.infrastructure.workspace.repository;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import io.crewscope.application.coding.ExecutionWorkspaceRepository;
import io.crewscope.application.coding.WorkspacePolicyRepository;
import io.crewscope.application.coding.CodingTaskTimelinePublisher;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.infrastructure.runtime.DurableTaskWorkerStartupReconciler;
import io.crewscope.infrastructure.runtime.RuntimeWorkerRegistrationSpec;
import io.crewscope.infrastructure.runtime.TaskWorkerStartupReconciler;
import io.crewscope.domain.identity.Principal;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Worker-only primary-decorator and deployment-budget assembly proof for M4-I10. */
class CodingWorkspaceRecoveryConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withPropertyValues("crewscope.runtime.execution-profile=worker")
            .withUserConfiguration(CodingWorkspaceRecoveryConfiguration.class)
            .withBean(ExecutionWorkspaceRepository.class, () -> mock(ExecutionWorkspaceRepository.class))
            .withBean(WorkspacePolicyRepository.class, () -> mock(WorkspacePolicyRepository.class))
            .withBean(WorktreeProvisioner.class, () -> mock(WorktreeProvisioner.class))
            .withBean(WorkspaceDiffMonitorFactory.class, () -> mock(WorkspaceDiffMonitorFactory.class))
            .withBean(DockerSandboxControl.class, () -> mock(DockerSandboxControl.class))
            .withBean(CodingArtifactLifecycle.class, () -> mock(CodingArtifactLifecycle.class))
            .withBean(TransactionExecutor.class, () -> mock(TransactionExecutor.class))
            .withBean(AuthoritativeTimeProvider.class, () -> mock(AuthoritativeTimeProvider.class))
            .withBean(CodingTaskTimelinePublisher.class, () -> CodingTaskTimelinePublisher.NO_OP)
            .withBean(RuntimeWorkerRegistrationSpec.class, CodingWorkspaceRecoveryConfigurationTest::registration)
            .withBean(DurableTaskWorkerStartupReconciler.class,
                    () -> mock(DurableTaskWorkerStartupReconciler.class));

    @Test
    void exposesCodingDecoratorAsThePrimaryStartupReconciler() {
        runner.run(context -> {
            context.assertThat()
                    .hasNotFailed()
                    .hasSingleBean(CodingWorkspaceRecoveryMarker.class)
                    .hasSingleBean(CodingWorkspaceStartupReconciler.class)
                    .hasSingleBean(CodingWorkspaceStartupProperties.class);
            assertSame(
                    context.getBean(CodingWorkspaceStartupReconciler.class),
                    context.getBean(TaskWorkerStartupReconciler.class));
        });
    }

    @Test
    void rejectsArtifactPurgeBudgetAboveStoreContract() {
        runner.withPropertyValues("crewscope.coding.recovery.artifact-purge-batch-size=1001")
                .run(context -> context.assertThat().hasFailed());
    }

    @Test
    void rejectsWorkspaceBatchAboveRepositoryContract() {
        runner.withPropertyValues("crewscope.coding.recovery.recovery-batch-size=1001")
                .run(context -> context.assertThat().hasFailed());
    }

    private static RuntimeWorkerRegistrationSpec registration() {
        RuntimeWorkerRegistrationSpec registration = mock(RuntimeWorkerRegistrationSpec.class);
        org.mockito.Mockito.when(registration.actor()).thenReturn(mock(Principal.class));
        return registration;
    }
}
