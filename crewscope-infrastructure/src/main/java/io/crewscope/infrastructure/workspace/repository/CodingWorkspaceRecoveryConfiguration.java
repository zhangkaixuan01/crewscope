package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.application.coding.ExecutionWorkspaceRepository;
import io.crewscope.application.coding.CodingTaskTimelinePublisher;
import io.crewscope.application.coding.WorkspacePolicyRepository;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.infrastructure.runtime.DurableTaskWorkerStartupReconciler;
import io.crewscope.infrastructure.runtime.RuntimeWorkerRegistrationSpec;
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
    @ConditionalOnMissingBean(CodingWorkspaceStartupReconciler.class)
    // No cross-class @ConditionalOnBean here: three prerequisites (WorktreeProvisioner,
    // WorkspaceDiffMonitorFactory, DockerSandboxControl) are @Bean methods of sibling
    // infrastructure configurations processed later in scan order, so the guard was
    // permanently false and the reconciler never assembled in real deployments (M9b-Q02
    // defect 27 family, surfaced by the M10-Q00 fail-fast wiring of the server-side
    // runtime operations adapter). Every prerequisite shares this class's
    // WorkerManagedRepositoryCondition predicate (or registers unconditionally), so
    // constructor injection resolves them whenever this configuration is active and
    // fails fast on any genuinely missing collaborator.
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
