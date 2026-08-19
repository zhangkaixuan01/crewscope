package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/** Worker-only Spring wiring for controlled AgentScope Coding filesystem mutations. */
@Configuration(proxyBeanMethods = false)
@Conditional(WorkerManagedRepositoryCondition.class)
@EnableConfigurationProperties(CodingFilesystemProperties.class)
public class CodingFilesystemConfiguration {

    @Bean
    @ConditionalOnMissingBean(CodingFilesystemUsageRegistry.class)
    CodingFilesystemUsageRegistry codingFilesystemUsageRegistry() {
        return new CodingFilesystemUsageRegistry();
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
