package io.crewscope.server.config.runtime;

import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.execution.DurableTaskExecutionEventService;
import io.crewscope.application.execution.DurableAgentRunResumeService;
import io.crewscope.application.execution.TaskExecutionEventEncoder;
import io.crewscope.application.execution.TaskRuntimeEventReceiptRepository;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.runtime.ExecutionRuntimeRepository;
import io.crewscope.application.runtime.RuntimeWorkerRepository;
import io.crewscope.application.task.ClaimQuotaRepository;
import io.crewscope.application.task.ClaimSchedulerMetrics;
import io.crewscope.application.task.ClaimTokenGenerator;
import io.crewscope.application.task.ExecutionLeaseRepository;
import io.crewscope.application.task.AgentInterruptRepository;
import io.crewscope.application.task.AgentRunRepository;
import io.crewscope.application.task.ExecutionLeaseSweeper;
import io.crewscope.application.task.LeaseCoordinatorMetrics;
import io.crewscope.application.task.PolicySnapshotRepository;
import io.crewscope.application.task.RuntimeArtifactRepository;
import io.crewscope.application.task.TaskClaimScheduler;
import io.crewscope.application.task.TaskExecutionQueueRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskExecutionLeaseCoordinator;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.infrastructure.runtime.DurableTaskClaimScheduler;
import io.crewscope.infrastructure.runtime.DurableExecutionLeaseSweeper;
import io.crewscope.infrastructure.runtime.DurableTaskExecutionLeaseCoordinator;
import io.crewscope.infrastructure.runtime.ExecutionLeaseCoordinatorSpec;
import io.crewscope.infrastructure.runtime.ExecutionLeaseSweeperLifecycle;
import io.crewscope.infrastructure.runtime.RuntimeRegistryCoordinator;
import io.crewscope.infrastructure.runtime.RuntimeWorkerLifecycle;
import io.crewscope.infrastructure.runtime.RuntimeWorkerLoadProvider;
import io.crewscope.infrastructure.runtime.RuntimeWorkerRegistrationSpec;
import io.crewscope.infrastructure.runtime.SecureClaimTokenGenerator;
import io.crewscope.infrastructure.runtime.TaskWorkerLoadTracker;
import io.crewscope.infrastructure.runtime.TaskClaimSchedulerSpec;
import io.crewscope.server.observability.TaskClaimSchedulerMetrics;
import io.crewscope.server.observability.TaskExecutionLeaseMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/** Spring composition root for persistent Runtime registration and JVM Worker Heartbeat. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RuntimeWorkerProperties.class)
public class RuntimeRegistryConfiguration {

    @Bean
    RuntimeDeploymentProfile runtimeDeploymentProfile(RuntimeWorkerProperties properties) {
        return RuntimeDeploymentProfile.parse(properties.getExecutionProfile());
    }

    @Bean
    @Conditional(WorkerCapableProfileCondition.class)
    RuntimeWorkerRegistrationSpec runtimeWorkerRegistrationSpec(
            RuntimeWorkerProperties properties,
            RuntimeDeploymentProfile profile,
            PrincipalRepository principalRepository) {
        Principal actor = principalRepository
                .findById(properties.organizationId(), properties.actorPrincipalId())
                .orElseThrow(() -> new IllegalStateException(
                        "Configured Runtime actor Principal does not exist in the Organization"));
        if (!actor.canAct()) {
            throw new IllegalStateException(
                    "Configured Runtime actor Principal must be ACTIVE");
        }
        return properties.registrationSpec(profile, actor);
    }

    @Bean
    @Conditional(WorkerCapableProfileCondition.class)
    TaskWorkerLoadTracker taskWorkerLoadTracker() {
        // The same counter drives Claim capacity and RuntimeWorker Heartbeat publication.
        return new TaskWorkerLoadTracker();
    }

    @Bean
    @Conditional(WorkerCapableProfileCondition.class)
    RuntimeRegistryCoordinator runtimeRegistryCoordinator(
            ExecutionRuntimeRepository runtimeRepository,
            RuntimeWorkerRepository workerRepository,
            TransactionExecutor transactionExecutor,
            TimeProvider timeProvider,
            RuntimeWorkerRegistrationSpec spec,
            RuntimeWorkerLoadProvider loadProvider) {
        return new RuntimeRegistryCoordinator(
                runtimeRepository,
                workerRepository,
                transactionExecutor,
                timeProvider,
                spec,
                loadProvider);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @Conditional(WorkerCapableProfileCondition.class)
    RuntimeWorkerLifecycle runtimeWorkerLifecycle(
            RuntimeRegistryCoordinator coordinator, RuntimeWorkerRegistrationSpec spec) {
        return new RuntimeWorkerLifecycle(coordinator, spec.heartbeatInterval());
    }

    @Bean
    @Conditional(WorkerCapableProfileCondition.class)
    TaskClaimSchedulerSpec taskClaimSchedulerSpec(
            RuntimeWorkerProperties properties, RuntimeWorkerRegistrationSpec registrationSpec) {
        return properties.claimSchedulerSpec(registrationSpec);
    }

    @Bean
    @Conditional(WorkerCapableProfileCondition.class)
    ExecutionLeaseCoordinatorSpec executionLeaseCoordinatorSpec(
            RuntimeWorkerProperties properties,
            RuntimeWorkerRegistrationSpec registrationSpec) {
        return properties.executionLeaseCoordinatorSpec(registrationSpec);
    }

    @Bean
    @Conditional(WorkerCapableProfileCondition.class)
    @ConditionalOnMissingBean(ClaimTokenGenerator.class)
    ClaimTokenGenerator claimTokenGenerator() {
        return new SecureClaimTokenGenerator();
    }

    @Bean
    @Conditional(WorkerCapableProfileCondition.class)
    @ConditionalOnMissingBean(ClaimSchedulerMetrics.class)
    ClaimSchedulerMetrics claimSchedulerMetrics(MeterRegistry meterRegistry) {
        return new TaskClaimSchedulerMetrics(meterRegistry);
    }

    @Bean
    @Conditional(WorkerCapableProfileCondition.class)
    @ConditionalOnMissingBean(LeaseCoordinatorMetrics.class)
    LeaseCoordinatorMetrics leaseCoordinatorMetrics(MeterRegistry meterRegistry) {
        return new TaskExecutionLeaseMetrics(meterRegistry);
    }

    @Bean
    @Conditional(WorkerCapableProfileCondition.class)
    TaskClaimScheduler taskClaimScheduler(
            ExecutionRuntimeRepository runtimeRepository,
            RuntimeWorkerRepository workerRepository,
            TaskExecutionQueueRepository queueRepository,
            TaskExecutionRepository executionRepository,
            PolicySnapshotRepository policyRepository,
            ExecutionLeaseRepository leaseRepository,
            ClaimQuotaRepository quotaRepository,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider authoritativeTimeProvider,
            ClaimTokenGenerator tokenGenerator,
            ClaimSchedulerMetrics metrics,
            TaskClaimSchedulerSpec schedulerSpec) {
        return new DurableTaskClaimScheduler(
                runtimeRepository,
                workerRepository,
                queueRepository,
                executionRepository,
                policyRepository,
                leaseRepository,
                quotaRepository,
                transactionExecutor,
                authoritativeTimeProvider,
                tokenGenerator,
                metrics,
                schedulerSpec);
    }

    @Bean
    @Conditional(WorkerCapableProfileCondition.class)
    TaskExecutionLeaseCoordinator taskExecutionLeaseCoordinator(
            TaskExecutionRepository executionRepository,
            ExecutionLeaseRepository leaseRepository,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider authoritativeTimeProvider,
            LeaseCoordinatorMetrics metrics,
            ExecutionLeaseCoordinatorSpec coordinatorSpec) {
        return new DurableTaskExecutionLeaseCoordinator(
                executionRepository,
                leaseRepository,
                transactionExecutor,
                authoritativeTimeProvider,
                metrics,
                coordinatorSpec);
    }

    @Bean
    @Conditional(WorkerCapableProfileCondition.class)
    DurableTaskExecutionEventService durableTaskExecutionEventService(
            AgentRunRepository runRepository,
            AgentInterruptRepository interruptRepository,
            RuntimeArtifactRepository artifactRepository,
            TaskRuntimeEventReceiptRepository receiptRepository,
            ExecutionLeaseRepository leaseRepository,
            PrincipalRepository principalRepository,
            TaskExecutionEventEncoder eventEncoder,
            DomainEventStore domainEventStore,
            OutboxRepository outboxRepository,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider authoritativeTimeProvider) {
        return new DurableTaskExecutionEventService(
                runRepository,
                interruptRepository,
                artifactRepository,
                receiptRepository,
                leaseRepository,
                principalRepository,
                eventEncoder,
                domainEventStore,
                outboxRepository,
                transactionExecutor,
                authoritativeTimeProvider);
    }

    @Bean
    @Conditional(WorkerCapableProfileCondition.class)
    DurableAgentRunResumeService durableAgentRunResumeService(
            AgentRunRepository runRepository,
            AgentInterruptRepository interruptRepository,
            PrincipalRepository principalRepository,
            DomainEventStore domainEventStore,
            OutboxRepository outboxRepository,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider authoritativeTimeProvider) {
        return new DurableAgentRunResumeService(
                runRepository,
                interruptRepository,
                principalRepository,
                domainEventStore,
                outboxRepository,
                transactionExecutor,
                authoritativeTimeProvider);
    }

    @Bean
    @Conditional(WorkerCapableProfileCondition.class)
    ExecutionLeaseSweeper executionLeaseSweeper(
            TaskExecutionRepository executionRepository,
            ExecutionLeaseRepository leaseRepository,
            DomainEventStore domainEventStore,
            OutboxRepository outboxRepository,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider authoritativeTimeProvider,
            LeaseCoordinatorMetrics metrics,
            ExecutionLeaseCoordinatorSpec coordinatorSpec) {
        return new DurableExecutionLeaseSweeper(
                executionRepository,
                leaseRepository,
                domainEventStore,
                outboxRepository,
                transactionExecutor,
                authoritativeTimeProvider,
                metrics,
                coordinatorSpec);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @Conditional(WorkerCapableProfileCondition.class)
    ExecutionLeaseSweeperLifecycle executionLeaseSweeperLifecycle(
            ExecutionLeaseSweeper sweeper, ExecutionLeaseCoordinatorSpec coordinatorSpec) {
        return new ExecutionLeaseSweeperLifecycle(
                sweeper,
                coordinatorSpec.maximumSweepSize(),
                coordinatorSpec.sweeperInterval());
    }
}
