package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.application.coding.RepositoryBindingPreflightPort;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/** Spring wiring for managed repository resolution and immutable baseline Preflight. */
@Configuration(proxyBeanMethods = false)
@Conditional(WorkerManagedRepositoryCondition.class)
@EnableConfigurationProperties(ManagedRepositoryProperties.class)
public class ManagedRepositoryConfiguration {

    @Bean
    @ConditionalOnMissingBean(ManagedRepositoryResolver.class)
    ManagedRepositoryResolver managedRepositoryResolver(
            ManagedRepositoryProperties properties, GitCommandExecutor gitCommands) {
        return new ManagedRepositoryResolver(
                properties.managedRootPath(), properties.getRequiredOwner(), gitCommands);
    }

    @Bean
    @ConditionalOnMissingBean(BaselinePreflight.class)
    BaselinePreflight baselinePreflight(
            ManagedRepositoryResolver repositoryResolver, GitCommandExecutor gitCommands) {
        return new BaselinePreflight(repositoryResolver, gitCommands);
    }

    @Bean
    @ConditionalOnMissingBean(RepositoryBindingPreflightPort.class)
    RepositoryBindingPreflightPort repositoryBindingPreflightPort(
            BaselinePreflight baselinePreflight) {
        return new ManagedRepositoryBindingPreflightAdapter(baselinePreflight);
    }
}
