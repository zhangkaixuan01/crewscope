package io.crewscope.server.config.application;

import io.crewscope.application.coding.RepositoryBindingAccessPolicy;
import io.crewscope.application.github.GitHubConnectionPolicySettings;
import io.crewscope.application.github.GitHubProviderRepository;
import io.crewscope.application.github.GitHubRepositoryImportApplicationService;
import io.crewscope.application.github.GitHubRepositoryImportAuthorizationService;
import io.crewscope.application.github.GitHubRepositoryImportJobRepository;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.provider.ProviderBindingRepository;
import io.crewscope.domain.shared.time.TimeProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** API-safe composition for durable GitHub repository import requests. */
@Configuration(proxyBeanMethods = false)
public class GitHubRepositoryImportApplicationConfiguration {

    @Bean
    GitHubRepositoryImportAuthorizationService gitHubRepositoryImportAuthorizationService(
            GitHubProviderRepository githubRepositories,
            ConnectionRepository connections,
            ConnectionGrantRepository grants,
            ProviderBindingRepository providerBindings,
            RepositoryBindingAccessPolicy accessPolicy,
            GitHubConnectionPolicySettings policySettings,
            TimeProvider timeProvider) {
        return new GitHubRepositoryImportAuthorizationService(
                githubRepositories,
                connections,
                grants,
                providerBindings,
                accessPolicy,
                policySettings,
                timeProvider);
    }

    @Bean
    GitHubRepositoryImportApplicationService gitHubRepositoryImportApplicationService(
            GitHubRepositoryImportJobRepository jobs,
            GitHubRepositoryImportAuthorizationService authorization,
            RepositoryBindingAccessPolicy accessPolicy,
            TimeProvider timeProvider) {
        return new GitHubRepositoryImportApplicationService(
                jobs, authorization, accessPolicy, timeProvider);
    }
}
