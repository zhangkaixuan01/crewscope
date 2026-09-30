package io.crewscope.server.config.application;

import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.action.ExternalObservationRepository;
import io.crewscope.application.credential.CredentialStore;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.github.GitHubDraftPullRequestPort;
import io.crewscope.application.github.GitHubConnectionApplicationService;
import io.crewscope.application.github.GitHubConnectionPolicySettings;
import io.crewscope.application.github.GitHubProviderPort;
import io.crewscope.application.github.GitHubPullRequestWebhookPort;
import io.crewscope.application.github.GitHubPushPort;
import io.crewscope.application.github.GitHubProviderRepository;
import io.crewscope.application.github.GitHubWebhookSecretResolver;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.provider.BuiltInProviderRegistration;
import io.crewscope.application.provider.ProviderBindingRepository;
import io.crewscope.application.provider.ProviderBootstrapLock;
import io.crewscope.application.provider.ProviderDefinitionRepository;
import io.crewscope.application.provider.ProviderImplementationRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.application.transaction.TransactionExecutor;
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
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;
import io.crewscope.server.config.runtime.WorkerCapableProfileCondition;

/** Explicit Spring composition for the GitHub Provider read boundary. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GitHubProviderProperties.class)
public class GitHubProviderApplicationConfiguration {

    @Bean
    @ConditionalOnBean({
        TeamRepository.class,
        TeamMembershipQuery.class,
        TeamRoleRepository.class,
        MemberRoleRepository.class,
        WorkspaceRepository.class,
        ProviderDefinitionRepository.class,
        ProviderImplementationRepository.class,
        ProviderBindingRepository.class,
        ProviderBootstrapLock.class,
        TransactionExecutor.class,
        DomainEventStore.class,
        OutboxRepository.class,
        CommandReceiptStore.class
    })
    GitHubConnectionApplicationService gitHubConnectionApplicationService(
            ConnectionRepository connections,
            ConnectionGrantRepository grants,
            CredentialStore credentials,
            GitHubProviderRepository githubRepository,
            GitHubProviderPort provider,
            TeamRepository teams,
            TeamMembershipQuery memberships,
            TeamRoleRepository roles,
            MemberRoleRepository memberRoles,
            WorkspaceRepository workspaces,
            ProviderDefinitionRepository definitions,
            ProviderImplementationRepository implementations,
            ProviderBindingRepository bindings,
            ProviderBootstrapLock providerBootstrapLock,
            GitHubSourceCodeProvider sourceCodeProvider,
            DomainEventStore eventStore,
            OutboxRepository outbox,
            CommandReceiptStore receipts,
            TransactionExecutor transactions,
            TimeProvider timeProvider,
            GitHubConnectionPolicySettings settings) {
        var descriptor = sourceCodeProvider.descriptor();
        BuiltInProviderRegistration registration = new BuiltInProviderRegistration(
                GitHubConnectionApplicationService.CONNECTOR_KEY,
                descriptor.type(),
                descriptor.interfaceVersion(),
                descriptor.displayName(),
                descriptor.implementationId(),
                "1.0.0",
                sourceCodeProvider.capabilities());
        return new GitHubConnectionApplicationService(
                connections,
                grants,
                credentials,
                githubRepository,
                provider,
                teams,
                memberships,
                roles,
                memberRoles,
                workspaces,
                definitions,
                implementations,
                bindings,
                providerBootstrapLock,
                registration,
                eventStore,
                outbox,
                receipts,
                transactions,
                timeProvider,
                settings);
    }

    @Bean
    GitHubConnectionPolicySettings gitHubConnectionPolicySettings(
            GitHubProviderProperties properties,
            ObjectProvider<GitHubWebhookSecretResolver> webhookSecretResolver) {
        return new GitHubConnectionPolicySettings(
                properties.getAllowedOwnerLogins(), properties.isAllowPrivateRepositories(),
                properties.isAllowInternalRepositories(), properties.isAllowBroadUserOauth(),
                webhookSecretResolver.getIfAvailable() != null);
    }

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
    // Order-robust twin of the ManagedRepository availability fact: component-scan
    // registration processes this class before the infrastructure configuration that
    // defines ManagedRepositoryResolver, so the presence condition below could never
    // see it and the GitHub write boundary silently vanished from real deployments
    // (M9b-Q02 defect 27). The worker-capable execution profile carries the same fact;
    // the resolver itself resolves by injection after all definitions are registered.
    @Conditional(WorkerCapableProfileCondition.class)
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
    // Same registration-order fact as gitHubPushPort: the provider repositories live in
    // the later-scanned infrastructure persistence configuration. The draft pull request
    // boundary is consumed only by the worker-capable action fleet, so its availability
    // follows the same execution profile.
    @Conditional(WorkerCapableProfileCondition.class)
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
