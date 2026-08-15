package io.crewscope.server.config.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

import io.crewscope.application.execution.TaskExecutionEventEncoder;
import io.crewscope.application.execution.TaskRuntimeEventReceiptRepository;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.runtime.ExecutionRuntimeRepository;
import io.crewscope.application.runtime.RuntimeWorkerRepository;
import io.crewscope.application.task.AgentInterruptRepository;
import io.crewscope.application.task.AgentRunRepository;
import io.crewscope.application.task.ClaimQuotaRepository;
import io.crewscope.application.task.ExecutionLeaseRepository;
import io.crewscope.application.task.ExecutionLeaseSweeper;
import io.crewscope.application.task.PolicySnapshotRepository;
import io.crewscope.application.task.RuntimeArtifactRepository;
import io.crewscope.application.task.TaskClaimScheduler;
import io.crewscope.application.task.TaskExecutionQueueRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskExecutionLeaseCoordinator;
import io.crewscope.application.task.TaskEventRepository;
import io.crewscope.application.task.WorkerTaskCommandService;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.runtime.RuntimeProfile;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.infrastructure.runtime.RuntimeRegistryCoordinator;
import io.crewscope.infrastructure.runtime.ExecutionLeaseCoordinatorSpec;
import io.crewscope.infrastructure.runtime.ExecutionLeaseSweeperLifecycle;
import io.crewscope.infrastructure.runtime.RuntimeWorkerLifecycle;
import io.crewscope.infrastructure.runtime.RuntimeWorkerLoadProvider;
import io.crewscope.infrastructure.runtime.RuntimeWorkerRegistrationSpec;
import io.crewscope.infrastructure.runtime.TaskWorkerLoadTracker;
import io.crewscope.infrastructure.runtime.TaskClaimSchedulerSpec;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Spring Context proof for Runtime deployment Profile selection and fail-closed configuration. */
class RuntimeRegistryConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RuntimeRegistryConfiguration.class)
            .withBean(PrincipalRepository.class, () -> mock(PrincipalRepository.class))
            .withBean(ExecutionRuntimeRepository.class, () -> mock(ExecutionRuntimeRepository.class))
            .withBean(RuntimeWorkerRepository.class, () -> mock(RuntimeWorkerRepository.class))
            .withBean(TransactionExecutor.class, () -> mock(TransactionExecutor.class))
            .withBean(TimeProvider.class, () -> mock(TimeProvider.class));

    @Test
    void serverProfileDoesNotCreateWorkerLifecycle() {
        contextRunner
                .withPropertyValues("crewscope.runtime.execution-profile=server")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RuntimeDeploymentProfile.class);
                    assertThat(context).doesNotHaveBean(RuntimeRegistryCoordinator.class);
                    assertThat(context).doesNotHaveBean(RuntimeWorkerLifecycle.class);
                    assertThat(context).doesNotHaveBean(TaskExecutionLeaseCoordinator.class);
                    assertThat(context).doesNotHaveBean(ExecutionLeaseSweeper.class);
                });
    }

    @Test
    void unknownProfileFailsClosed() {
        contextRunner
                .withPropertyValues("crewscope.runtime.execution-profile=hybrid")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void workerCapableProfileRequiresStableTenantIdentity() {
        contextRunner
                .withPropertyValues("crewscope.runtime.execution-profile=all")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "crewscope.runtime.registry.organization-id must not be blank");
                });
    }

    @Test
    void allProfileBindsStableIdentityAndStartsAllWorker() {
        OrganizationId organizationId = OrganizationId.generate();
        Principal actor = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.SERVICE,
                Optional.empty(),
                "Runtime Registry",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                UtcTimestamp.parse("2026-08-14T12:00:00Z"));
        PrincipalRepository principals = mock(PrincipalRepository.class);
        when(principals.findById(organizationId, actor.id())).thenReturn(Optional.of(actor));
        ExecutionRuntimeRepository runtimes = mock(ExecutionRuntimeRepository.class);
        when(runtimes.findByKey(any(), any(), any())).thenReturn(Optional.empty());
        when(runtimes.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        RuntimeWorkerRepository workers = mock(RuntimeWorkerRepository.class);
        when(workers.findByStableKey(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(workers.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(workers.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        new ApplicationContextRunner()
                .withUserConfiguration(RuntimeRegistryConfiguration.class)
                .withBean(PrincipalRepository.class, () -> principals)
                .withBean(ExecutionRuntimeRepository.class, () -> runtimes)
                .withBean(RuntimeWorkerRepository.class, () -> workers)
                .withBean(TaskExecutionQueueRepository.class,
                        () -> mock(TaskExecutionQueueRepository.class))
                .withBean(TaskExecutionRepository.class,
                        () -> mock(TaskExecutionRepository.class))
                .withBean(PolicySnapshotRepository.class,
                        () -> mock(PolicySnapshotRepository.class))
                .withBean(ExecutionLeaseRepository.class,
                        () -> mock(ExecutionLeaseRepository.class))
                .withBean(ClaimQuotaRepository.class,
                        () -> mock(ClaimQuotaRepository.class))
                .withBean(AgentRunRepository.class, () -> mock(AgentRunRepository.class))
                .withBean(AgentInterruptRepository.class,
                        () -> mock(AgentInterruptRepository.class))
                .withBean(RuntimeArtifactRepository.class,
                        () -> mock(RuntimeArtifactRepository.class))
                .withBean(TaskRuntimeEventReceiptRepository.class,
                        () -> mock(TaskRuntimeEventReceiptRepository.class))
                .withBean(TaskExecutionEventEncoder.class,
                        () -> mock(TaskExecutionEventEncoder.class))
                .withBean(DomainEventStore.class, () -> mock(DomainEventStore.class))
                .withBean(TaskEventRepository.class, () -> mock(TaskEventRepository.class))
                .withBean(OutboxRepository.class, () -> mock(OutboxRepository.class))
                .withBean(CommandReceiptStore.class, () -> mock(CommandReceiptStore.class))
                .withBean(TransactionExecutor.class, DirectTransactionExecutor::new)
                .withBean(AuthoritativeTimeProvider.class, () -> () ->
                        UtcTimestamp.parse("2026-08-14T12:00:00Z"))
                .withBean(TimeProvider.class, () -> () ->
                        UtcTimestamp.parse("2026-08-14T12:00:00Z"))
                .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues(
                        "crewscope.runtime.execution-profile=all",
                        "crewscope.runtime.registry.organization-id=" + organizationId,
                        "crewscope.runtime.registry.actor-principal-id=" + actor.id(),
                        "crewscope.runtime.registry.worker.stable-key=all-node-a")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RuntimeWorkerLifecycle.class);
                    assertThat(context).hasSingleBean(RuntimeWorkerLoadProvider.class);
                    assertThat(context).hasSingleBean(TaskWorkerLoadTracker.class);
                    assertThat(context.getBean(RuntimeWorkerLoadProvider.class))
                            .isSameAs(context.getBean(TaskWorkerLoadTracker.class));
                    assertThat(context).hasSingleBean(TaskClaimScheduler.class);
                    assertThat(context).hasSingleBean(TaskClaimSchedulerSpec.class);
                    assertThat(context).hasSingleBean(TaskExecutionLeaseCoordinator.class);
                    assertThat(context).hasSingleBean(WorkerTaskCommandService.class);
                    assertThat(context).hasSingleBean(ExecutionLeaseSweeper.class);
                    assertThat(context).hasSingleBean(ExecutionLeaseSweeperLifecycle.class);
                    assertThat(context).hasSingleBean(ExecutionLeaseCoordinatorSpec.class);
                    assertThat(context.getBean(RuntimeWorkerRegistrationSpec.class).workerProfile())
                            .isEqualTo(RuntimeProfile.ALL);
                    assertThat(context.getBean(RuntimeWorkerLifecycle.class).identity().stableKey())
                            .isEqualTo("all-node-a");
                    assertThat(context.getBean(TaskClaimSchedulerSpec.class).maximumBatchSize())
                            .isEqualTo(8);
                    assertThat(context.getBean(ExecutionLeaseCoordinatorSpec.class).runLeaseDuration())
                            .isEqualTo(java.time.Duration.ofSeconds(30));
                });
    }

    private static final class DirectTransactionExecutor implements TransactionExecutor {

        @Override
        public <T> T required(Supplier<T> operation) {
            return operation.get();
        }
    }
}
