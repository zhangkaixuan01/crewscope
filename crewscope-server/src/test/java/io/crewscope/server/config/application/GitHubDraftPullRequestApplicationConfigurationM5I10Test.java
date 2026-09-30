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
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import io.crewscope.infrastructure.workspace.repository.ManagedRepositoryResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

/** Explicit Spring composition for M5-I10 Draft PR and inbound Webhook boundaries. */
class GitHubDraftPullRequestApplicationConfigurationM5I10Test {

    @Test
    void wiresDraftPullRequestWhenExactAuthorityRepositoriesExist() {
        runner()
                .run(context -> context.assertThat()
                        .hasNotFailed()
                        .hasSingleBean(GitHubDraftPullRequestPort.class)
                        .hasSingleBean(GitHubDraftPullRequestAdapter.class)
                        .doesNotHaveBean(GitHubPullRequestWebhookPort.class));

        // Absence is a deployment-profile fact now (M9b-Q02 defect 27): the port's
        // repository collaborators live in a later-scanned configuration class, so a
        // presence condition could never see them and silently dropped the boundary
        // from real deployments. Worker-capable compositions fail fast instead.
        runner()
                .withPropertyValues("crewscope.runtime.execution-profile=api")
                .run(context -> context.assertThat()
                        .hasNotFailed()
                        .doesNotHaveBean(GitHubDraftPullRequestPort.class));
        runnerWithoutPushResolver().run(context -> context.assertThat().hasFailed());
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
                .withPropertyValues(
                        "crewscope.provider.github.web-base-uri=https://token@github.com")
                .run(context -> context.assertThat().hasFailed());
        runner()
                .withPropertyValues(
                        "crewscope.provider.github.web-base-uri=http://github.com")
                .run(context -> context.assertThat().hasFailed());
    }

    private ApplicationContextRunner runner() {
        // The push boundary lives in the same configuration class and follows the same
        // worker-capable profile, so its collaborators must be resolvable too.
        return runnerWithoutPushResolver()
                .withBean(ManagedRepositoryResolver.class,
                        () -> mock(ManagedRepositoryResolver.class));
    }

    private ApplicationContextRunner runnerWithoutPushResolver() {
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
                .withBean(GitHubProviderPort.class, () -> mock(GitHubProviderPort.class))
                .withBean(GitCommandExecutor.class, () -> mock(GitCommandExecutor.class))
                .withBean(ProviderBindingRepository.class,
                        () -> mock(ProviderBindingRepository.class))
                .withBean(RepositoryBindingRepository.class,
                        () -> mock(RepositoryBindingRepository.class));
    }
}
