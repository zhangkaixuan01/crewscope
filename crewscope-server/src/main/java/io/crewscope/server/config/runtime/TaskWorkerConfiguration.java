package io.crewscope.server.config.runtime;

import io.agentscope.core.state.AgentStateStore;
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
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.infrastructure.runtime.DurableTaskWorkerExecutionFactory;
import io.crewscope.infrastructure.runtime.DurableTaskWorkerExecutionHandler;
import io.crewscope.infrastructure.runtime.DurableTaskWorkerStartupReconciler;
import io.crewscope.infrastructure.runtime.RuntimeWorkerLifecycle;
import io.crewscope.infrastructure.runtime.RuntimeWorkerRegistrationSpec;
import io.crewscope.infrastructure.runtime.TaskWorkerExecutionHandler;
import io.crewscope.infrastructure.runtime.TaskWorkerExecutionLoop;
import io.crewscope.infrastructure.runtime.TaskWorkerExecutionSpec;
import io.crewscope.infrastructure.runtime.TaskWorkerLoadTracker;
import io.crewscope.infrastructure.runtime.TaskWorkerLoopSpec;
import io.crewscope.infrastructure.runtime.TaskWorkerStartupReconciler;
import io.crewscope.server.observability.TaskWorkerHealthIndicator;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/** Production composition root for the M3-I09 AgentScope JVM Task Worker. */
@Configuration(proxyBeanMethods = false)
@Conditional(WorkerCapableProfileCondition.class)
@EnableConfigurationProperties(TaskWorkerRuntimeProperties.class)
public class TaskWorkerConfiguration {

    @Bean
    @ConditionalOnMissingBean(AgentScopeModelResolver.class)
    AgentScopeModelResolver taskAgentScopeModelResolver() {
        return AgentScopeModelResolver.registry();
    }

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
            TaskWorkerExecutionSpec executionSpec) {
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
                executionSpec.taskTokenLifetime());
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
            TaskWorkerExecutionSpec spec) {
        return new DurableTaskWorkerExecutionHandler(
                executionFactory,
                runtime,
                runtime,
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
    TaskWorkerStartupReconciler taskWorkerStartupReconciler(
            ExecutionLeaseSweeper leaseSweeper,
            TaskExecutionRepository executionRepository,
            ExecutionLeaseRepository leaseRepository,
            StepExecutionRepository stepRepository,
            AgentRunRepository runRepository,
            PrincipalRepository principalRepository,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider timeProvider,
            RuntimeWorkerRegistrationSpec registration,
            TaskWorkerRuntimeProperties properties) {
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
                properties.getMaximumReconcileSize());
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    TaskWorkerExecutionLoop taskWorkerExecutionLoop(
            TaskClaimScheduler claimScheduler,
            TaskWorkerExecutionHandler executionHandler,
            TaskWorkerStartupReconciler startupReconciler,
            RuntimeWorkerLifecycle workerLifecycle,
            TaskWorkerLoadTracker loadTracker,
            TaskWorkerLoopSpec spec) {
        return new TaskWorkerExecutionLoop(
                claimScheduler,
                executionHandler,
                startupReconciler,
                workerLifecycle,
                loadTracker,
                spec);
    }

    @Bean
    TaskWorkerHealthIndicator taskWorkerHealthIndicator(TaskWorkerExecutionLoop workerLoop) {
        return new TaskWorkerHealthIndicator(workerLoop);
    }
}
