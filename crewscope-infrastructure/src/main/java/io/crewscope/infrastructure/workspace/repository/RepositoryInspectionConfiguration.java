package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/** Worker-only Spring wiring for guarded AgentScope repository inspection sessions. */
@Configuration(proxyBeanMethods = false)
@Conditional(WorkerManagedRepositoryCondition.class)
@EnableConfigurationProperties(RepositoryInspectionProperties.class)
public class RepositoryInspectionConfiguration {

    @Bean
    @ConditionalOnMissingBean(RepositoryInspectionToolFactory.class)
    RepositoryInspectionToolFactory repositoryInspectionToolFactory(
            RepositoryInspectionProperties properties, GitCommandExecutor gitCommands) {
        return new RepositoryInspectionToolFactory(properties, gitCommands);
    }
}
