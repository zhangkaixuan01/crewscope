package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import io.crewscope.application.coding.WorkspaceWriteBudgetStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;

/** Worker-only Spring wiring for controlled AgentScope Coding filesystem mutations. */
@Configuration(proxyBeanMethods = false)
@Conditional(WorkerManagedRepositoryCondition.class)
@EnableConfigurationProperties(CodingFilesystemProperties.class)
public class CodingFilesystemConfiguration {

    @Bean
    @ConditionalOnMissingBean(CodingFilesystemUsageRegistry.class)
    CodingFilesystemUsageRegistry codingFilesystemUsageRegistry(
            ObjectProvider<WorkspaceWriteBudgetStore> writeBudgetStores) {
        WorkspaceWriteBudgetStore store = writeBudgetStores.getIfAvailable(
                io.crewscope.infrastructure.persistence.coding.InMemoryWorkspaceWriteBudgetStore::new);
        return new CodingFilesystemUsageRegistry(store);
    }

    @Bean
    @ConditionalOnMissingBean(CodingFilesystemToolFactory.class)
    CodingFilesystemToolFactory codingFilesystemToolFactory(
            CodingFilesystemProperties properties,
            GitCommandExecutor gitCommands,
            CodingFilesystemUsageRegistry usages) {
        return new CodingFilesystemToolFactory(properties, gitCommands, usages);
    }
}
