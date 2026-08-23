package io.crewscope.server.config.application;

import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.action.ExternalObservationRepository;
import io.crewscope.application.credential.CredentialStore;
import io.crewscope.application.github.GitHubDraftPullRequestPort;
import io.crewscope.application.github.GitHubProviderPort;
import io.crewscope.application.github.GitHubPullRequestWebhookPort;
import io.crewscope.application.github.GitHubPushPort;
import io.crewscope.application.github.GitHubProviderRepository;
import io.crewscope.application.github.GitHubWebhookSecretResolver;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.provider.ProviderBindingRepository;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.infrastructure.github.GitHubDraftPullRequestAdapter;
import io.crewscope.infrastructure.github.GitHubProviderAdapter;
import io.crewscope.infrastructure.github.GitHubPullRequestWebhookAdapter;
import io.crewscope.infrastructure.github.GitHubPushAdapter;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import io.crewscope.infrastructure.workspace.repository.ManagedRepositoryResolver;
import io.crewscope.integration.provider.sourcecode.GitHubSourceCodeProvider;
import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Explicit Spring composition for the GitHub Provider read boundary. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GitHubProviderProperties.class)
public class GitHubProviderApplicationConfiguration {

    @Bean
    GitHubSourceCodeProvider gitHubSourceCodeProvider() {
        return new GitHubSourceCodeProvider();
    }

    @Bean
    @ConditionalOnMissingBean(GitHubProviderPort.class)
    GitHubProviderPort gitHubProviderPort(
            ObjectMapper objectMapper,
            TimeProvider timeProvider,
            ConnectionRepository connectionRepository,
            ConnectionGrantRepository grantRepository,
            CredentialStore credentialStore,
            GitHubProviderRepository repository,
            GitHubProviderProperties properties) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(properties.validatedConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new GitHubProviderAdapter(
                client,
                objectMapper,
                properties.validatedApiBaseUri(),
                properties.validatedRequestTimeout(),
                properties.validatedCatalogTtl(),
                properties.validatedCredentialHandleTtl(),
                timeProvider,
                connectionRepository,
                grantRepository,
                credentialStore,
                repository,
                properties.isAllowLoopbackHttp());
    }

    @Bean
    @ConditionalOnBean(ManagedRepositoryResolver.class)
    @ConditionalOnMissingBean(GitHubPushPort.class)
    GitHubPushPort gitHubPushPort(
            GitHubProviderPort provider,
            ProviderBindingRepository providerBindingRepository,
            RepositoryBindingRepository repositoryBindingRepository,
            ManagedRepositoryResolver sourceRepositoryResolver,
            GitCommandExecutor gitCommands,
            TimeProvider timeProvider,
            ConnectionRepository connectionRepository,
            ConnectionGrantRepository grantRepository,
            CredentialStore credentialStore,
            GitHubProviderProperties properties) {
        return new GitHubPushAdapter(
                provider,
                providerBindingRepository,
                repositoryBindingRepository,
                sourceRepositoryResolver,
                gitCommands,
                properties.validatedMirrorRoot(),
                properties.validatedAskPassRoot(),
                properties.validatedRequiredOwner(),
                properties.validatedGitBaseUri(),
                properties.validatedCredentialHandleTtl(),
                timeProvider,
                connectionRepository,
                grantRepository,
                credentialStore);
    }

    @Bean
    @ConditionalOnBean({ProviderBindingRepository.class, RepositoryBindingRepository.class})
    @ConditionalOnMissingBean(GitHubDraftPullRequestPort.class)
    GitHubDraftPullRequestPort gitHubDraftPullRequestPort(
            GitHubProviderPort provider,
            ObjectMapper objectMapper,
            TimeProvider timeProvider,
            ProviderBindingRepository providerBindingRepository,
            RepositoryBindingRepository repositoryBindingRepository,
            ConnectionRepository connectionRepository,
            ConnectionGrantRepository grantRepository,
            CredentialStore credentialStore,
            GitHubProviderProperties properties) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(properties.validatedConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new GitHubDraftPullRequestAdapter(
                provider,
                providerBindingRepository,
                repositoryBindingRepository,
                connectionRepository,
                grantRepository,
                credentialStore,
                client,
                objectMapper,
                properties.validatedApiBaseUri(),
                properties.validatedWebBaseUri(),
                properties.validatedRequestTimeout(),
                properties.validatedCredentialHandleTtl(),
                timeProvider,
                properties.isAllowLoopbackHttp());
    }

    @Bean
    @ConditionalOnBean({GitHubWebhookSecretResolver.class, ExternalObservationRepository.class})
    @ConditionalOnMissingBean(GitHubPullRequestWebhookPort.class)
    GitHubPullRequestWebhookPort gitHubPullRequestWebhookPort(
            ObjectMapper objectMapper,
            GitHubWebhookSecretResolver secretResolver,
            ExternalObservationRepository observations) {
        return new GitHubPullRequestWebhookAdapter(
                objectMapper, secretResolver, observations);
    }
}
