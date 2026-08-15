package io.crewscope.server.config.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.state.AgentStateStore;
import io.crewscope.agentscope.AgentScopeModelResolver;
import io.crewscope.application.execution.DurableTaskExecutionEventService;
import io.crewscope.application.execution.TaskAgentStateSnapshotService;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.task.AgentRunRepository;
import io.crewscope.application.task.AgentStateSnapshotRepository;
import io.crewscope.application.task.ExecutionLeaseRepository;
import io.crewscope.application.task.ExecutionLeaseSweeper;
import io.crewscope.application.task.LeaseSweepResult;
import io.crewscope.application.task.PlanVersionRepository;
import io.crewscope.application.task.PolicySnapshotRepository;
import io.crewscope.application.task.SafetyEnforcementOverlayRepository;
import io.crewscope.application.task.StepExecutionRepository;
import io.crewscope.application.task.TaskAgentRuntimeSessionRepository;
import io.crewscope.application.task.TaskClaimBatchResult;
import io.crewscope.application.task.TaskClaimScheduler;
import io.crewscope.application.task.TaskExecutionLeaseCoordinator;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.task.TaskTokenService;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeCapabilities;
import io.crewscope.domain.runtime.RuntimeCapability;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeProfile;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.infrastructure.runtime.ExecutionLeaseCoordinatorSpec;
import io.crewscope.infrastructure.runtime.RuntimeWorkerIdentity;
import io.crewscope.infrastructure.runtime.RuntimeWorkerLifecycle;
import io.crewscope.infrastructure.runtime.RuntimeWorkerRegistrationSpec;
import io.crewscope.infrastructure.runtime.TaskClaimSchedulerSpec;
import io.crewscope.infrastructure.runtime.TaskWorkerExecutionLoop;
import io.crewscope.infrastructure.runtime.TaskWorkerLoadTracker;
import io.crewscope.server.observability.TaskWorkerHealthIndicator;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Spring composition proof for identical ALL/WORKER loops and SERVER exclusion. */
class TaskWorkerConfigurationM3I09Test {

    @Test
    void serverProfileDoesNotCreateTaskWorkerBeans() {
        new ApplicationContextRunner()
                .withUserConfiguration(TaskWorkerConfiguration.class)
                .withPropertyValues("crewscope.runtime.execution-profile=server")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TaskWorkerExecutionLoop.class);
                    assertThat(context).doesNotHaveBean(TaskWorkerLoadTracker.class);
                    assertThat(context).doesNotHaveBean(TaskWorkerHealthIndicator.class);
                });
    }

    @Test
    void allProfileCreatesCompleteTaskWorkerAndActuatorHealth() {
        workerContext("all", RuntimeProfile.ALL).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TaskWorkerExecutionLoop.class);
            assertThat(context).hasSingleBean(TaskWorkerLoadTracker.class);
            assertThat(context).hasSingleBean(TaskWorkerHealthIndicator.class);
            assertThat(context.getBean(TaskWorkerExecutionLoop.class).health().started()).isTrue();
            assertThat(context.getBean(TaskWorkerHealthIndicator.class).health().getStatus()
                    .getCode()).isEqualTo("UP");
        });
    }

    @Test
    void independentWorkerProfileUsesTheSameExecutionLoop() {
        workerContext("worker", RuntimeProfile.WORKER).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TaskWorkerExecutionLoop.class);
            assertThat(context.getBean(RuntimeWorkerRegistrationSpec.class).workerProfile())
                    .isEqualTo(RuntimeProfile.WORKER);
            assertThat(context.getBean(TaskWorkerExecutionLoop.class).health().acceptingClaims())
                    .isTrue();
        });
    }

    private static ApplicationContextRunner workerContext(
            String deploymentProfile, RuntimeProfile runtimeProfile) {
        OrganizationId organizationId = OrganizationId.generate();
        Principal actor = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.SERVICE,
                Optional.empty(),
                "Task Worker",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                UtcTimestamp.parse("2026-08-15T06:00:00Z"));
        RuntimeCapabilities capabilities = new RuntimeCapabilities(
                Set.of(RuntimeCapability.PLAN, RuntimeCapability.SESSION_STATE));
        RuntimeWorkerRegistrationSpec registration = new RuntimeWorkerRegistrationSpec(
                organizationId,
                new RuntimeEnvironment("test"),
                "agentscope-java",
                "AgentScope Java",
                "2.0.0",
                capabilities,
                deploymentProfile + "-node-a",
                runtimeProfile,
                capabilities,
                2,
                Duration.ofSeconds(1),
                Duration.ofSeconds(10),
                actor);
        RuntimeWorkerLifecycle lifecycle = mock(RuntimeWorkerLifecycle.class);
        when(lifecycle.identity()).thenReturn(new RuntimeWorkerIdentity(
                new ExecutionRuntimeId(java.util.UUID.randomUUID()),
                new RuntimeWorkerId(java.util.UUID.randomUUID()),
                registration.workerStableKey(),
                runtimeProfile));
        TaskClaimScheduler claimScheduler = mock(TaskClaimScheduler.class);
        when(claimScheduler.claim(anyInt()))
                .thenReturn(new TaskClaimBatchResult(List.of(), 0, 0, 0, 0));
        ExecutionLeaseSweeper sweeper = mock(ExecutionLeaseSweeper.class);
        when(sweeper.sweep(anyInt())).thenReturn(new LeaseSweepResult(List.of()));
        TaskExecutionRepository executions = mock(TaskExecutionRepository.class);
        when(executions.findRecoveringForUpdate(organizationId, 100)).thenReturn(List.of());

        return new ApplicationContextRunner()
                .withUserConfiguration(TaskWorkerConfiguration.class)
                // Production ownership lives in RuntimeRegistryConfiguration; this focused
                // composition test supplies the shared counter at that configuration boundary.
                .withBean(TaskWorkerLoadTracker.class, TaskWorkerLoadTracker::new)
                .withBean(RuntimeWorkerRegistrationSpec.class, () -> registration)
                .withBean(RuntimeWorkerLifecycle.class, () -> lifecycle)
                .withBean(TaskClaimScheduler.class, () -> claimScheduler)
                .withBean(TaskClaimSchedulerSpec.class, () -> new TaskClaimSchedulerSpec(
                        organizationId,
                        registration.environment(),
                        registration.runtimeKey(),
                        registration.workerStableKey(),
                        actor,
                        registration.heartbeatTimeout(),
                        Duration.ofSeconds(30),
                        8,
                        32,
                        2,
                        8))
                .withBean(ExecutionLeaseCoordinatorSpec.class, () ->
                        new ExecutionLeaseCoordinatorSpec(
                                organizationId,
                                registration.environment(),
                                actor,
                                Duration.ofSeconds(30),
                                Duration.ofSeconds(30),
                                Duration.ofSeconds(10),
                                Duration.ofSeconds(5),
                                Duration.ofSeconds(5),
                                100))
                .withBean(ExecutionLeaseSweeper.class, () -> sweeper)
                .withBean(TaskRepository.class, () -> mock(TaskRepository.class))
                .withBean(TaskExecutionRepository.class, () -> executions)
                .withBean(ExecutionLeaseRepository.class,
                        () -> mock(ExecutionLeaseRepository.class))
                .withBean(PolicySnapshotRepository.class,
                        () -> mock(PolicySnapshotRepository.class))
                .withBean(SafetyEnforcementOverlayRepository.class,
                        () -> mock(SafetyEnforcementOverlayRepository.class))
                .withBean(PlanVersionRepository.class,
                        () -> mock(PlanVersionRepository.class))
                .withBean(StepExecutionRepository.class,
                        () -> mock(StepExecutionRepository.class))
                .withBean(TaskAgentRuntimeSessionRepository.class,
                        () -> mock(TaskAgentRuntimeSessionRepository.class))
                .withBean(AgentRunRepository.class, () -> mock(AgentRunRepository.class))
                .withBean(AgentStateSnapshotRepository.class,
                        () -> mock(AgentStateSnapshotRepository.class))
                .withBean(PrincipalRepository.class, () -> mock(PrincipalRepository.class))
                .withBean(AgentProfileRepository.class,
                        () -> mock(AgentProfileRepository.class))
                .withBean(TaskExecutionLeaseCoordinator.class,
                        () -> mock(TaskExecutionLeaseCoordinator.class))
                .withBean(TaskTokenService.class, () -> mock(TaskTokenService.class))
                .withBean(DurableTaskExecutionEventService.class,
                        () -> mock(DurableTaskExecutionEventService.class))
                .withBean(TaskAgentStateSnapshotService.class,
                        () -> mock(TaskAgentStateSnapshotService.class))
                .withBean(AgentStateStore.class, () -> mock(AgentStateStore.class))
                .withBean(AgentScopeModelResolver.class,
                        () -> mock(AgentScopeModelResolver.class))
                .withBean(AuthoritativeTimeProvider.class, () -> () ->
                        UtcTimestamp.parse("2026-08-15T06:00:00Z"))
                .withBean(TransactionExecutor.class, DirectTransactionExecutor::new)
                .withPropertyValues(
                        "crewscope.runtime.execution-profile=" + deploymentProfile,
                        "crewscope.runtime.task-worker.poll-interval=50ms",
                        "crewscope.runtime.task-worker.graceful-shutdown-timeout=1s");
    }

    private static final class DirectTransactionExecutor implements TransactionExecutor {
        @Override
        public <T> T required(Supplier<T> operation) {
            return operation.get();
        }
    }
}
