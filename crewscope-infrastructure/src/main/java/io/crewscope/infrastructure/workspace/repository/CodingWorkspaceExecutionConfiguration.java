package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.application.coding.BuildProfileCatalog;
import io.crewscope.application.coding.CodingTargetSnapshotRepository;
import io.crewscope.application.coding.CodingTaskTimelinePublisher;
import io.crewscope.application.coding.ExecutionWorkspaceRepository;
import io.crewscope.application.coding.WorkspacePolicyOverlayRepository;
import io.crewscope.application.coding.WorkspacePolicyRepository;
import io.crewscope.application.coding.CommandEvidenceRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.infrastructure.runtime.RuntimeWorkerRegistrationSpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/** Worker-only composition of the durable Coding Workspace execution lifecycle. */
@Configuration(proxyBeanMethods = false)
@Conditional(WorkerManagedRepositoryCondition.class)
@EnableConfigurationProperties({
    CodingWorkspaceExecutionProperties.class,
    CodingEvaluationJudgeProperties.class
})
public class CodingWorkspaceExecutionConfiguration {

    @Bean
    @ConditionalOnMissingBean(CodingWorktreePreparationHook.class)
    CodingWorktreePreparationHook codingWorktreePreparationHook(
            CodingEvaluationJudgeProperties properties) {
        return properties.judgeTestsRootPath()
                .<CodingWorktreePreparationHook>map(path ->
                        new FilesystemCodingEvaluationJudgeHook(
                                path, properties.requiredRepositoryKey()))
                .orElse(CodingWorktreePreparationHook.NONE);
    }

    @Bean
    @ConditionalOnMissingBean(CodingSpecialistToolSessionFactory.class)
    CodingSpecialistToolSessionFactory codingSpecialistToolSessionFactory(
            RepositoryInspectionProperties inspectionProperties,
            CodingFilesystemProperties filesystemProperties,
            SandboxCommandProperties commandProperties,
            GitCommandExecutor git,
            CodingFilesystemUsageRegistry filesystemUsages,
            CommandEvidenceRepository commandEvidence,
            SandboxCommandUsageRegistry commandUsages,
            BuildProfileCommandRunner commandRunner,
            CommandEvidenceWriter commandWriter,
            TestEvidencePublisher testEvidencePublisher) {
        return new CodingSpecialistToolSessionFactory(
                inspectionProperties,
                filesystemProperties,
                commandProperties,
                git,
                filesystemUsages,
                commandEvidence,
                commandUsages,
                commandRunner,
                commandWriter,
                testEvidencePublisher);
    }

    @Bean
    @ConditionalOnMissingBean(CodingWorkspaceRuntimeRegistry.class)
    CodingWorkspaceRuntimeRegistry codingWorkspaceRuntimeRegistry() {
        return new CodingWorkspaceRuntimeRegistry();
    }

    @Bean
    @ConditionalOnMissingBean(CodingWorkspaceExecutionLifecycle.class)
    CodingWorkspaceExecutionLifecycle codingWorkspaceExecutionLifecycle(
            CodingTargetSnapshotRepository targets,
            ExecutionWorkspaceRepository workspaces,
            WorkspacePolicyRepository policies,
            WorkspacePolicyOverlayRepository overlays,
            BuildProfileCatalog buildProfiles,
            ManagedRepositoryResolver repositories,
            WorktreeProvisioner worktrees,
            TaskExecutionSandboxFactory sandboxes,
            WorkspaceDiffMonitorFactory diffMonitors,
            WorkspaceDiffFinalizer diffFinalizer,
            CodingWorktreePreparationHook worktreePreparation,
            CodingWorkspaceRuntimeRegistry registry,
            CodingFilesystemUsageRegistry filesystemUsages,
            SandboxCommandUsageRegistry commandUsages,
            CodingTaskTimelinePublisher timeline,
            TransactionExecutor transactions,
            AuthoritativeTimeProvider timeProvider,
            RuntimeWorkerRegistrationSpec registration,
            PrincipalRepository principals,
            CodingWorkspaceExecutionProperties properties) {
        return new DurableCodingWorkspaceExecutionLifecycle(
                targets,
                workspaces,
                policies,
                overlays,
                buildProfiles,
                repositories,
                worktrees,
                sandboxes,
                diffMonitors,
                diffFinalizer,
                worktreePreparation,
                registry,
                filesystemUsages,
                commandUsages,
                transactions,
                timeProvider,
                registration,
                principals,
                properties,
                timeline);
    }
}
