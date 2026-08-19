package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/** Worker-only Spring wiring for Worktree lifecycle and cross-process path locking. */
@Configuration(proxyBeanMethods = false)
@Conditional(WorkerManagedRepositoryCondition.class)
@EnableConfigurationProperties(ManagedWorktreeProperties.class)
public class ManagedWorktreeConfiguration {

    @Bean
    @ConditionalOnMissingBean(WorkspacePathLockManager.class)
    WorkspacePathLockManager workspacePathLockManager(ManagedWorktreeProperties properties) {
        return new WorkspacePathLockManager(
                properties.lockRootPath(), properties.getRequiredOwner());
    }

    @Bean
    @ConditionalOnMissingBean(WorktreeProvisioner.class)
    WorktreeProvisioner worktreeProvisioner(
            ManagedWorktreeProperties properties,
            ManagedRepositoryResolver repositoryResolver,
            GitCommandExecutor gitCommands,
            WorkspacePathLockManager lockManager) {
        return new WorktreeProvisioner(
                properties.rootPath(),
                properties.getRequiredOwner(),
                repositoryResolver,
                gitCommands,
                lockManager);
    }
}
