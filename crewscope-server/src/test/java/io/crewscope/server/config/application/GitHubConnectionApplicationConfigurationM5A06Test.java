package io.crewscope.server.config.application;

import static org.mockito.Mockito.mock;

import io.crewscope.application.credential.CredentialStore;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.github.GitHubConnectionApplicationService;
import io.crewscope.application.github.GitHubProviderRepository;
import io.crewscope.application.github.GitHubWebhookSecretResolver;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
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
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

/** M5-A06 explicit composition and optional Webhook status dependency tests. */
class GitHubConnectionApplicationConfigurationM5A06Test {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GitHubProviderApplicationConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(TimeProvider.class, () -> mock(TimeProvider.class))
            .withBean(ConnectionRepository.class, () -> mock(ConnectionRepository.class))
            .withBean(ConnectionGrantRepository.class, () -> mock(ConnectionGrantRepository.class))
            .withBean(CredentialStore.class, () -> mock(CredentialStore.class))
            .withBean(GitHubProviderRepository.class, () -> mock(GitHubProviderRepository.class))
            .withBean(TeamRepository.class, () -> mock(TeamRepository.class))
            .withBean(TeamMembershipQuery.class, () -> mock(TeamMembershipQuery.class))
            .withBean(TeamRoleRepository.class, () -> mock(TeamRoleRepository.class))
            .withBean(MemberRoleRepository.class, () -> mock(MemberRoleRepository.class))
            .withBean(WorkspaceRepository.class, () -> mock(WorkspaceRepository.class))
            .withBean(ProviderDefinitionRepository.class,
                    () -> mock(ProviderDefinitionRepository.class))
            .withBean(ProviderImplementationRepository.class,
                    () -> mock(ProviderImplementationRepository.class))
            .withBean(ProviderBindingRepository.class, () -> mock(ProviderBindingRepository.class))
            .withBean(ProviderBootstrapLock.class, () -> mock(ProviderBootstrapLock.class))
            .withBean(DomainEventStore.class, () -> mock(DomainEventStore.class))
            .withBean(OutboxRepository.class, () -> mock(OutboxRepository.class))
            .withBean(CommandReceiptStore.class, () -> mock(CommandReceiptStore.class))
            .withBean(TransactionExecutor.class, () -> mock(TransactionExecutor.class));

    @Test
    void wiresApplicationBoundaryWhenAllPersistenceAndAuthorityPortsExist() {
        runner.run(context -> context.assertThat()
                .hasNotFailed()
                .hasSingleBean(GitHubConnectionApplicationService.class));
    }

    @Test
    void remainsComposableWithTheOptionalWebhookSecretResolver() {
        runner.withBean(GitHubWebhookSecretResolver.class,
                        () -> mock(GitHubWebhookSecretResolver.class))
                .run(context -> context.assertThat()
                        .hasNotFailed()
                        .hasSingleBean(GitHubConnectionApplicationService.class));
    }
}
