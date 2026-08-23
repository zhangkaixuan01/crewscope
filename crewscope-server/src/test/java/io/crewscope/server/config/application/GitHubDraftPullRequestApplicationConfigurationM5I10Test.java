package io.crewscope.server.config.application;

import static org.mockito.Mockito.mock;

import io.crewscope.application.action.ExternalObservationRepository;
import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.credential.CredentialStore;
import io.crewscope.application.github.GitHubDraftPullRequestPort;
import io.crewscope.application.github.GitHubProviderPort;
import io.crewscope.application.github.GitHubProviderRepository;
import io.crewscope.application.github.GitHubPullRequestWebhookPort;
import io.crewscope.application.github.GitHubWebhookSecretResolver;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.provider.ProviderBindingRepository;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.infrastructure.github.GitHubDraftPullRequestAdapter;
import io.crewscope.infrastructure.github.GitHubPullRequestWebhookAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

/** Explicit Spring composition for M5-I10 Draft PR and inbound Webhook boundaries. */
class GitHubDraftPullRequestApplicationConfigurationM5I10Test {

    @Test
    void wiresDraftPullRequestWhenExactAuthorityRepositoriesExist() {
        runner()
                .withBean(ProviderBindingRepository.class,
                        () -> mock(ProviderBindingRepository.class))
                .withBean(RepositoryBindingRepository.class,
                        () -> mock(RepositoryBindingRepository.class))
                .run(context -> context.assertThat()
                        .hasNotFailed()
                        .hasSingleBean(GitHubDraftPullRequestPort.class)
                        .hasSingleBean(GitHubDraftPullRequestAdapter.class)
                        .doesNotHaveBean(GitHubPullRequestWebhookPort.class));

        runner().run(context -> context.assertThat()
                .hasNotFailed()
                .doesNotHaveBean(GitHubDraftPullRequestPort.class));
    }

    @Test
    void wiresWebhookOnlyWithSecretAndDurableObservationBoundaries() {
        runner()
                .withBean(GitHubWebhookSecretResolver.class,
                        () -> mock(GitHubWebhookSecretResolver.class))
                .withBean(ExternalObservationRepository.class,
                        () -> mock(ExternalObservationRepository.class))
                .run(context -> context.assertThat()
                        .hasNotFailed()
                        .hasSingleBean(GitHubPullRequestWebhookPort.class)
                        .hasSingleBean(GitHubPullRequestWebhookAdapter.class));

        runner()
                .withBean(GitHubWebhookSecretResolver.class,
                        () -> mock(GitHubWebhookSecretResolver.class))
                .run(context -> context.assertThat()
                        .hasNotFailed()
                        .doesNotHaveBean(GitHubPullRequestWebhookPort.class));
    }

    @Test
    void rejectsNonHttpsOrCredentialBearingWebOrigins() {
        runner()
                .withBean(ProviderBindingRepository.class,
                        () -> mock(ProviderBindingRepository.class))
                .withBean(RepositoryBindingRepository.class,
                        () -> mock(RepositoryBindingRepository.class))
                .withPropertyValues(
                        "crewscope.provider.github.web-base-uri=https://token@github.com")
                .run(context -> context.assertThat().hasFailed());
        runner()
                .withBean(ProviderBindingRepository.class,
                        () -> mock(ProviderBindingRepository.class))
                .withBean(RepositoryBindingRepository.class,
                        () -> mock(RepositoryBindingRepository.class))
                .withPropertyValues(
                        "crewscope.provider.github.web-base-uri=http://github.com")
                .run(context -> context.assertThat().hasFailed());
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(GitHubProviderApplicationConfiguration.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(TimeProvider.class, () -> mock(TimeProvider.class))
                .withBean(ConnectionRepository.class, () -> mock(ConnectionRepository.class))
                .withBean(ConnectionGrantRepository.class,
                        () -> mock(ConnectionGrantRepository.class))
                .withBean(CredentialStore.class, () -> mock(CredentialStore.class))
                .withBean(GitHubProviderRepository.class,
                        () -> mock(GitHubProviderRepository.class))
                .withBean(GitHubProviderPort.class, () -> mock(GitHubProviderPort.class));
    }
}
