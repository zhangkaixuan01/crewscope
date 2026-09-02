package io.crewscope.server.config.application;

import io.crewscope.application.coding.RepositoryBindingApplicationService;
import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.credential.CredentialStore;
import io.crewscope.application.github.GitHubProviderPort;
import io.crewscope.application.github.GitHubRepositoryImportAuthorizationService;
import io.crewscope.application.github.GitHubRepositoryImportJobRepository;
import io.crewscope.application.github.GitHubRepositoryImportPort;
import io.crewscope.application.github.GitHubRepositoryImportWorker;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.infrastructure.github.GitHubRepositoryImportAdapter;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import io.crewscope.infrastructure.workspace.repository.ManagedRepositoryProperties;
import io.crewscope.server.config.runtime.WorkerCapableProfileCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/** Worker-only composition for repository I/O and leased import execution. */
@Configuration(proxyBeanMethods = false)
@Conditional(WorkerCapableProfileCondition.class)
@EnableConfigurationProperties(GitHubRepositoryImportWorkerProperties.class)
public class GitHubRepositoryImportWorkerConfiguration {

    @Bean
    @ConditionalOnMissingBean(GitHubRepositoryImportPort.class)
    GitHubRepositoryImportPort gitHubRepositoryImportPort(
            ConnectionRepository connections,
            ConnectionGrantRepository grants,
            CredentialStore credentials,
            GitCommandExecutor gitCommands,
            ManagedRepositoryProperties managedProperties,
            GitHubProviderProperties properties,
            TimeProvider timeProvider) {
        return new GitHubRepositoryImportAdapter(
                connections,
                grants,
                credentials,
                gitCommands,
                managedProperties.managedRootPath(),
                properties.validatedAskPassRoot(),
                managedProperties.getRequiredOwner(),
                properties.validatedGitBaseUri(),
                timeProvider,
                properties.validatedCredentialHandleTtl());
    }

    @Bean
    GitHubRepositoryImportWorker gitHubRepositoryImportWorker(
            GitHubRepositoryImportJobRepository jobs,
            GitHubRepositoryImportAuthorizationService authorization,
            GitHubProviderPort provider,
            GitHubRepositoryImportPort importer,
            RepositoryBindingApplicationService bindings,
            RepositoryBindingRepository bindingRepository,
            PrincipalRepository principals,
            TimeProvider timeProvider,
            GitHubRepositoryImportWorkerProperties properties) {
        properties.validatedPollInterval();
        return new GitHubRepositoryImportWorker(
                jobs,
                authorization,
                provider,
                importer,
                bindings,
                bindingRepository,
                principals,
                timeProvider,
                properties.validatedWorkerId(),
                properties.validatedLeaseDuration());
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "crewscope.github-import.worker",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    GitHubRepositoryImportWorkerScheduler gitHubRepositoryImportWorkerScheduler(
            GitHubRepositoryImportWorker worker) {
        return new GitHubRepositoryImportWorkerScheduler(worker);
    }
}
