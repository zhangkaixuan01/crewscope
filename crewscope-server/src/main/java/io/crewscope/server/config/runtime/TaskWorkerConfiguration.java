package io.crewscope.server.config.runtime;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.model.Model;
import io.crewscope.agentscope.AgentScopeModelResolver;
import io.crewscope.agentscope.task.AgentScopeTaskPlanAdapter;
import io.crewscope.agentscope.task.AgentScopeTaskPlanningSnapshotMapper;
import io.crewscope.agentscope.task.AgentScopeTaskRuntime;
import io.crewscope.agentscope.task.ApplicationTaskPlanPublisher;
import io.crewscope.agentscope.task.ControlledTaskPlanParser;
import io.crewscope.agentscope.task.ControlledTaskToolkitFactory;
import io.crewscope.agentscope.task.TaskAgentConfiguration;
import io.crewscope.agentscope.task.TaskAgentConfigurationSource;
import io.crewscope.agentscope.task.TaskAgentFactory;
import io.crewscope.agentscope.coding.AgentScopeCodingRuntime;
import io.crewscope.agentscope.coding.CodingSpecialistConfiguration;
import io.crewscope.agentscope.coding.CodingSpecialistConfigurationSource;
import io.crewscope.agentscope.coding.CodingSpecialistFactory;
import io.crewscope.agentscope.coding.CodingSpecialistSkillBundle;
import io.crewscope.agentscope.coding.CodingSpecialistAuthorityGateway;
import io.crewscope.agentscope.coding.CodingSpecialistStepRuntime;
import io.crewscope.agentscope.coding.DurableCodingSpecialistExecutionStore;
import io.crewscope.application.coding.CodingCheckpointRepository;
import io.crewscope.application.coding.TestEvidenceRepository;
import io.crewscope.application.coding.output.CodingOutputValidator;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.execution.DurableTaskExecutionEventService;
import io.crewscope.application.execution.TaskAgentStateSnapshotService;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.task.AgentRunRepository;
import io.crewscope.application.task.AgentStateSnapshotRepository;
import io.crewscope.application.task.ExecutionLeaseRepository;
import io.crewscope.application.task.ExecutionLeaseSweeper;
import io.crewscope.application.task.PlanVersionRepository;
import io.crewscope.application.task.PolicySnapshotRepository;
import io.crewscope.application.task.SafetyEnforcementOverlayRepository;
import io.crewscope.application.task.StepExecutionRepository;
import io.crewscope.application.task.TaskAgentRuntimeSessionRepository;
import io.crewscope.application.task.TaskClaimScheduler;
import io.crewscope.application.task.TaskExecutionLeaseCoordinator;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskPlanPublicationService;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.task.TaskTokenService;
import io.crewscope.application.runtime.RuntimeMaintenanceService;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.infrastructure.runtime.DurableTaskWorkerExecutionFactory;
import io.crewscope.infrastructure.runtime.DurableTaskWorkerExecutionHandler;
import io.crewscope.infrastructure.runtime.DurableTaskWorkerStartupReconciler;
import io.crewscope.infrastructure.runtime.RuntimeWorkerLifecycle;
import io.crewscope.infrastructure.runtime.RuntimeRegistryCoordinator;
import io.crewscope.infrastructure.runtime.RuntimeWorkerRegistrationSpec;
import io.crewscope.infrastructure.runtime.TaskWorkerExecutionHandler;
import io.crewscope.infrastructure.runtime.TaskWorkerExecutionLoop;
import io.crewscope.infrastructure.runtime.TaskWorkerExecutionSpec;
import io.crewscope.infrastructure.runtime.TaskWorkerLoadTracker;
import io.crewscope.infrastructure.runtime.TaskWorkerLoopSpec;
import io.crewscope.infrastructure.runtime.TaskWorkerSpecialistExecution;
import io.crewscope.infrastructure.runtime.TaskWorkerStartupReconciler;
import io.crewscope.infrastructure.workspace.repository.CodingSpecialistToolSessionFactory;
import io.crewscope.infrastructure.workspace.repository.CodingWorkspaceExecutionLifecycle;
import io.crewscope.infrastructure.workspace.repository.CodingWorkspaceRecoveryMarker;
import io.crewscope.infrastructure.workspace.repository.CodingWorkspaceRuntimeRegistry;
import io.crewscope.infrastructure.workspace.repository.CodingWorkspaceRuntimeOperationsAdapter;
import io.crewscope.infrastructure.workspace.repository.CodingWorkspaceStartupReconciler;
import io.crewscope.server.observability.CodingWorkspaceStartupHealthIndicator;
import io.crewscope.server.observability.TaskWorkerHealthIndicator;
import jakarta.validation.Validator;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.SmartLifecycle;

/** Production composition root for the M3-I09 AgentScope JVM Task Worker. */
@Configuration(proxyBeanMethods = false)
@Conditional(WorkerCapableProfileCondition.class)
@EnableConfigurationProperties({
        TaskWorkerRuntimeProperties.class,
        CodingSpecialistRuntimeProperties.class
})
public class TaskWorkerConfiguration {

    @Bean
    TaskAgentConfigurationSource taskAgentConfigurationSource(
            TaskWorkerRuntimeProperties properties) {
        return (profileId, version) -> new TaskAgentConfiguration(
                profileId,
                version,
                properties.getModelId(),
                properties.fallbackModelId(),
                properties.getSystemPrompt(),
                properties.getMaxIterations(),
                properties.getMaxRetries());
    }

    @Bean
    ControlledTaskPlanParser controlledTaskPlanParser() {
        return new ControlledTaskPlanParser();
    }

    @Bean
    ControlledTaskToolkitFactory controlledTaskToolkitFactory(
            ControlledTaskPlanParser parser) {
        return new ControlledTaskToolkitFactory(parser);
    }

    @Bean(destroyMethod = "close")
    TaskAgentFactory taskAgentFactory(
            TaskAgentConfigurationSource configurationSource,
            AgentScopeModelResolver modelResolver,
            AgentStateStore stateStore,
            ControlledTaskToolkitFactory toolkitFactory,
            TaskWorkerRuntimeProperties properties) {
        return new TaskAgentFactory(
                configurationSource,
                modelResolver,
                stateStore,
                toolkitFactory,
                properties.getRuntimeRoot());
    }

    @Bean
    TaskPlanPublicationService taskPlanPublicationService(
            TaskRepository taskRepository,
            TaskExecutionRepository executionRepository,
            PlanVersionRepository planRepository,
            StepExecutionRepository stepRepository,
            PolicySnapshotRepository policyRepository,
            SafetyEnforcementOverlayRepository safetyRepository,
            PrincipalRepository principalRepository,
            TransactionExecutor transactionExecutor) {
        return new TaskPlanPublicationService(
                taskRepository,
                executionRepository,
                planRepository,
                stepRepository,
                policyRepository,
                safetyRepository,
                principalRepository,
                transactionExecutor,
                Clock.systemUTC());
    }

    @Bean
    ApplicationTaskPlanPublisher applicationTaskPlanPublisher(
            TaskPlanPublicationService service, TaskWorkerRuntimeProperties properties) {
        return new ApplicationTaskPlanPublisher(service, properties.getMaxStepRunAttempts());
    }

    @Bean(destroyMethod = "close")
    AgentScopeTaskRuntime agentScopeTaskRuntime(
            TaskAgentFactory factory,
            ControlledTaskPlanParser parser,
            ApplicationTaskPlanPublisher publisher,
            TaskAgentStateSnapshotService snapshotService) {
        return new AgentScopeTaskRuntime(
                factory,
                new AgentScopeTaskPlanningSnapshotMapper(),
                new AgentScopeTaskPlanAdapter(parser),
                publisher,
                snapshotService,
                Clock.systemUTC());
    }

    @Bean
    CodingSpecialistConfigurationSource codingSpecialistConfigurationSource(
            CodingSpecialistRuntimeProperties properties) {
        return (profileId, version) -> new CodingSpecialistConfiguration(
                profileId,
                version,
                properties.getModelId(),
                properties.fallbackModelId(),
                properties.getCompactionModelId(),
                properties.getSystemPrompt(),
                properties.getMaxIterations(),
                properties.getMaxRetries(),
                properties.getTemperature(),
                properties.getTopP(),
                properties.getMaxOutputTokens(),
                properties.getCompactionTriggerMessages(),
                properties.getCompactionKeepMessages(),
                properties.getToolResultEvictionChars(),
                properties.getToolResultPreviewChars());
    }

    @Bean
    CodingSpecialistSkillBundle codingSpecialistSkillBundle() {
        return new CodingSpecialistSkillBundle();
    }

    @Bean
    CodingSpecialistFactory codingSpecialistFactory(
            CodingSpecialistConfigurationSource configurationSource,
            AgentScopeModelResolver modelResolver,
            AgentStateStore stateStore,
            CodingSpecialistSkillBundle skillBundle,
            CodingSpecialistRuntimeProperties properties) {
        return new CodingSpecialistFactory(
                configurationSource,
                modelResolver,
                stateStore,
                skillBundle,
                properties.getRuntimeRoot());
    }

    @Bean
    AgentScopeCodingRuntime agentScopeCodingRuntime(CodingSpecialistFactory factory) {
        return new AgentScopeCodingRuntime(factory);
    }

    @Bean
    CodingOutputValidator codingOutputValidator(Validator validator) {
        return new CodingOutputValidator(validator);
    }

    @Bean
    DurableCodingSpecialistExecutionStore durableCodingSpecialistExecutionStore(
            DurableTaskExecutionEventService eventService,
            TaskAgentStateSnapshotService snapshotService,
            AgentStateSnapshotRepository snapshotRepository,
            CodingCheckpointRepository checkpointRepository,
            StepExecutionRepository stepRepository,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider timeProvider) {
        return new DurableCodingSpecialistExecutionStore(
                eventService,
                snapshotService,
                snapshotRepository,
                checkpointRepository,
                stepRepository,
                transactionExecutor,
                timeProvider);
    }

    @Bean
    @ConditionalOnMissingBean(CodingSpecialistAuthorityGateway.class)
    CodingSpecialistAuthorityGateway codingSpecialistAuthorityGateway(
            CodingWorkspaceRuntimeRegistry workspaces,
            CodingSpecialistToolSessionFactory tools,
            CodingWorkspaceExecutionLifecycle lifecycle,
            ExecutionLeaseRepository leases,
            TestEvidenceRepository testEvidence,
            PrincipalRepository principals,
            RuntimeWorkerRegistrationSpec registration,
            AuthoritativeTimeProvider timeProvider,
            TransactionExecutor transactionExecutor) {
        return new WorkerCodingSpecialistAuthorityGateway(
                workspaces,
                tools,
                lifecycle,
                leases,
                testEvidence,
                principals,
                registration,
                timeProvider,
                transactionExecutor);
    }

    /** M4-A03 supplies the production Workspace/Tool lifecycle Gateway. */
    @Bean
    @ConditionalOnMissingBean(CodingSpecialistStepRuntime.class)
    CodingSpecialistStepRuntime codingSpecialistStepRuntime(
            AgentScopeCodingRuntime runtime,
            CodingSpecialistAuthorityGateway authorityGateway,
            DurableCodingSpecialistExecutionStore executionStore,
            CodingOutputValidator outputValidator) {
        return new CodingSpecialistStepRuntime(
                runtime, authorityGateway, executionStore, outputValidator);
    }

    /** Routes Coding Task completion into the Specialist before the owning Lease is released. */
    @Bean
    @ConditionalOnMissingBean(TaskWorkerSpecialistExecution.class)
    TaskWorkerSpecialistExecution taskWorkerSpecialistExecution(
            CodingSpecialistStepRuntime runtime,
            TaskExecutionRepository executionRepository,
            ExecutionLeaseRepository leaseRepository,
            PlanVersionRepository planRepository,
            SafetyEnforcementOverlayRepository overlayRepository,
            StepExecutionRepository stepRepository,
            TaskAgentRuntimeSessionRepository sessionRepository,
            AgentRunRepository runRepository,
            PrincipalRepository principalRepository,
            AgentProfileRepository profileRepository,
            TestEvidenceRepository testEvidenceRepository,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider timeProvider,
            TaskWorkerRuntimeProperties properties) {
        return new DurableCodingTaskRouter(
                runtime,
                executionRepository,
                leaseRepository,
                planRepository,
                overlayRepository,
                stepRepository,
                sessionRepository,
                runRepository,
                principalRepository,
                profileRepository,
                testEvidenceRepository,
                transactionExecutor,
                timeProvider,
                properties.getRecoveryCandidateLimit());
    }

    @Bean
    TaskWorkerLoopSpec taskWorkerLoopSpec(
            RuntimeWorkerRegistrationSpec registration,
            io.crewscope.infrastructure.runtime.TaskClaimSchedulerSpec scheduler,
            TaskWorkerRuntimeProperties properties) {
        return new TaskWorkerLoopSpec(
                registration.maxConcurrentExecutions(),
                Math.min(scheduler.maximumBatchSize(), registration.maxConcurrentExecutions()),
                properties.getPollInterval(),
                properties.getGracefulShutdownTimeout());
    }

    @Bean
    TaskWorkerExecutionSpec taskWorkerExecutionSpec(
            io.crewscope.infrastructure.runtime.ExecutionLeaseCoordinatorSpec leaseSpec,
            TaskWorkerRuntimeProperties properties) {
        return new TaskWorkerExecutionSpec(
                properties.getTaskTokenLifetime(),
                leaseSpec.heartbeatInterval(),
                properties.getRecoveryCandidateLimit());
    }

    @Bean
    DurableTaskWorkerExecutionFactory durableTaskWorkerExecutionFactory(
            TaskRepository taskRepository,
            TaskExecutionRepository executionRepository,
            ExecutionLeaseRepository leaseRepository,
            PolicySnapshotRepository policyRepository,
            SafetyEnforcementOverlayRepository overlayRepository,
            PlanVersionRepository planRepository,
            TaskAgentRuntimeSessionRepository sessionRepository,
            AgentRunRepository runRepository,
            PrincipalRepository principalRepository,
            AgentProfileRepository profileRepository,
            TaskExecutionLeaseCoordinator leaseCoordinator,
            TaskTokenService tokenService,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider timeProvider,
            RuntimeWorkerRegistrationSpec registration,
            RuntimeWorkerLifecycle workerLifecycle,
            TaskWorkerExecutionSpec executionSpec,
            CodingWorkspaceExecutionLifecycle codingWorkspaceLifecycle) {
        return new DurableTaskWorkerExecutionFactory(
                taskRepository,
                executionRepository,
                leaseRepository,
                policyRepository,
                overlayRepository,
                planRepository,
                sessionRepository,
                runRepository,
                principalRepository,
                profileRepository,
                leaseCoordinator,
                tokenService,
                transactionExecutor,
                timeProvider,
                registration,
                workerLifecycle,
                executionSpec.taskTokenLifetime(),
                codingWorkspaceLifecycle);
    }

    @Bean(destroyMethod = "close")
    DurableTaskWorkerExecutionHandler taskWorkerExecutionHandler(
            DurableTaskWorkerExecutionFactory executionFactory,
            AgentScopeTaskRuntime runtime,
            DurableTaskExecutionEventService eventService,
            TaskExecutionLeaseCoordinator leaseCoordinator,
            TaskExecutionRepository executionRepository,
            ExecutionLeaseRepository leaseRepository,
            AgentStateSnapshotRepository snapshotRepository,
            TaskTokenService tokenService,
            AuthoritativeTimeProvider timeProvider,
            RuntimeWorkerRegistrationSpec registration,
            TaskWorkerExecutionSpec spec,
            TaskWorkerSpecialistExecution specialistExecution) {
        return new DurableTaskWorkerExecutionHandler(
                executionFactory,
                runtime,
                runtime,
                specialistExecution,
                eventService,
                leaseCoordinator,
                executionRepository,
                leaseRepository,
                snapshotRepository,
                tokenService,
                timeProvider,
                registration,
                spec);
    }

    @Bean
    DurableTaskWorkerStartupReconciler durableTaskWorkerStartupReconciler(
            ExecutionLeaseSweeper leaseSweeper,
            TaskExecutionRepository executionRepository,
            ExecutionLeaseRepository leaseRepository,
            StepExecutionRepository stepRepository,
            AgentRunRepository runRepository,
            PrincipalRepository principalRepository,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider timeProvider,
            RuntimeWorkerRegistrationSpec registration,
            TaskWorkerRuntimeProperties properties,
            CodingWorkspaceRecoveryMarker recoveryMarker) {
        return new DurableTaskWorkerStartupReconciler(
                leaseSweeper,
                executionRepository,
                leaseRepository,
                stepRepository,
                runRepository,
                principalRepository,
                transactionExecutor,
                timeProvider,
                registration,
                properties.getMaximumReconcileSize(),
                recoveryMarker);
    }

    @Bean
    TaskWorkerExecutionLoop taskWorkerExecutionLoop(
            TaskClaimScheduler claimScheduler,
            TaskWorkerExecutionHandler executionHandler,
            TaskWorkerStartupReconciler startupReconciler,
            RuntimeWorkerLifecycle workerLifecycle,
            TaskWorkerLoadTracker loadTracker,
            TaskWorkerLoopSpec spec,
            ObjectProvider<Model> providerModels) {
        // The loop's init method can dispatch immediately. Materialize the optional provider Model
        // on the Spring refresh thread so a claim never races singleton construction on a Worker
        // thread. API-only deployments may still omit a Model and fail closed on first invocation.
        providerModels.getIfAvailable();
        return new TaskWorkerExecutionLoop(
                claimScheduler,
                executionHandler,
                startupReconciler,
                workerLifecycle,
                loadTracker,
                spec);
    }

    /** Starts claims after every singleton and transaction resource has completed initialization. */
    @Bean
    SmartLifecycle taskWorkerExecutionLoopLifecycle(TaskWorkerExecutionLoop workerLoop) {
        return new SmartLifecycle() {
            @Override
            public void start() {
                workerLoop.start();
            }

            @Override
            public void stop() {
                workerLoop.close();
            }

            @Override
            public void stop(Runnable callback) {
                try {
                    workerLoop.close();
                } finally {
                    callback.run();
                }
            }

            @Override
            public boolean isRunning() {
                return workerLoop.health().started();
            }

            @Override
            public boolean isAutoStartup() {
                return true;
            }

            @Override
            public int getPhase() {
                return Integer.MAX_VALUE - 100;
            }
        };
    }

    @Bean
    TaskWorkerHealthIndicator taskWorkerHealthIndicator(
            TaskWorkerExecutionLoop workerLoop,
            RuntimeRegistryCoordinator registryCoordinator) {
        return new TaskWorkerHealthIndicator(workerLoop, registryCoordinator);
    }

    @Bean
    @ConditionalOnBean({
        CodingWorkspaceRuntimeRegistry.class,
        CodingWorkspaceStartupReconciler.class
    })
    CodingWorkspaceRuntimeOperationsAdapter codingWorkspaceRuntimeOperationsAdapter(
            CodingWorkspaceRuntimeRegistry registry,
            CodingWorkspaceStartupReconciler reconciler,
            RuntimeWorkerRegistrationSpec registration,
            AuthoritativeTimeProvider timeProvider,
            TransactionExecutor transactionExecutor) {
        return new CodingWorkspaceRuntimeOperationsAdapter(
                registry, reconciler, registration, timeProvider, transactionExecutor);
    }

    @Bean
    @ConditionalOnBean(CodingWorkspaceRuntimeOperationsAdapter.class)
    RuntimeMaintenanceService runtimeMaintenanceService(
            CodingWorkspaceRuntimeOperationsAdapter operations,
            DomainEventStore eventStore,
            OutboxRepository outboxRepository,
            CommandReceiptStore receiptStore,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider timeProvider) {
        return new RuntimeMaintenanceService(
                operations,
                eventStore,
                outboxRepository,
                receiptStore,
                transactionExecutor,
                timeProvider);
    }

    @Bean
    @ConditionalOnBean(CodingWorkspaceRuntimeOperationsAdapter.class)
    CodingWorkspaceStartupHealthIndicator codingWorkspaceStartupHealthIndicator(
            CodingWorkspaceRuntimeOperationsAdapter operations) {
        return new CodingWorkspaceStartupHealthIndicator(operations);
    }
}
