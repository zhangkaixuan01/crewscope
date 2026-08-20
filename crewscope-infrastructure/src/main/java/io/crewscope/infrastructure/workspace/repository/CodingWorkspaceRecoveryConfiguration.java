package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.application.coding.ExecutionWorkspaceRepository;
import io.crewscope.application.coding.CodingTaskTimelinePublisher;
import io.crewscope.application.coding.WorkspacePolicyRepository;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.infrastructure.runtime.DurableTaskWorkerStartupReconciler;
import io.crewscope.infrastructure.runtime.RuntimeWorkerRegistrationSpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Worker-only composition for M4 recovery marking and physical startup reconciliation. */
@Configuration(proxyBeanMethods = false)
@Conditional(WorkerManagedRepositoryCondition.class)
@EnableConfigurationProperties(CodingWorkspaceStartupProperties.class)
public class CodingWorkspaceRecoveryConfiguration {

    @Bean
    @ConditionalOnMissingBean(CodingWorkspaceRecoveryMarker.class)
    CodingWorkspaceRecoveryMarker codingWorkspaceRecoveryMarker(
            ExecutionWorkspaceRepository workspaces,
            RuntimeWorkerRegistrationSpec registration,
            CodingTaskTimelinePublisher timeline) {
        return new CodingWorkspaceRecoveryMarker(workspaces, registration.actor(), timeline);
    }

    @Bean
    @Primary
    @ConditionalOnBean({
        DurableTaskWorkerStartupReconciler.class,
        WorkspacePolicyRepository.class,
        WorktreeProvisioner.class,
        WorkspaceDiffMonitorFactory.class,
        DockerSandboxControl.class,
        CodingArtifactLifecycle.class
    })
    CodingWorkspaceStartupReconciler codingWorkspaceStartupReconciler(
            DurableTaskWorkerStartupReconciler taskReconciler,
            ExecutionWorkspaceRepository workspaces,
            WorkspacePolicyRepository policies,
            WorktreeProvisioner worktrees,
            WorkspaceDiffMonitorFactory diffMonitors,
            DockerSandboxControl docker,
            CodingArtifactLifecycle artifacts,
            TransactionExecutor transactions,
            AuthoritativeTimeProvider timeProvider,
            RuntimeWorkerRegistrationSpec registration,
            CodingWorkspaceStartupProperties properties,
            CodingTaskTimelinePublisher timeline) {
        return new CodingWorkspaceStartupReconciler(
                taskReconciler,
                workspaces,
                policies,
                worktrees,
                diffMonitors,
                docker,
                artifacts,
                transactions,
                timeProvider,
                registration,
                properties,
                timeline);
    }
}
