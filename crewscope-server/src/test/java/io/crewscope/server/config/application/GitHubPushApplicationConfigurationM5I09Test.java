package io.crewscope.server.config.application;

import static org.mockito.Mockito.mock;

import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.credential.CredentialStore;
import io.crewscope.application.github.GitHubProviderPort;
import io.crewscope.application.github.GitHubProviderRepository;
import io.crewscope.application.github.GitHubPushPort;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.provider.ProviderBindingRepository;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.infrastructure.github.GitHubPushAdapter;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import io.crewscope.infrastructure.workspace.repository.ManagedRepositoryResolver;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

/** Explicit Worker-side Spring composition for the M5-I09 Git Push boundary. */
class GitHubPushApplicationConfigurationM5I09Test {

    @TempDir Path temporaryDirectory;

    @Test
    void wiresPushOnlyWhenTheManagedRepositoryWorkerBoundaryExists() {
        runner()
                .withBean(ManagedRepositoryResolver.class, () -> mock(ManagedRepositoryResolver.class))
                .withBean(GitCommandExecutor.class, () -> mock(GitCommandExecutor.class))
                .withBean(ProviderBindingRepository.class, () -> mock(ProviderBindingRepository.class))
                .withBean(RepositoryBindingRepository.class, () -> mock(RepositoryBindingRepository.class))
                .withPropertyValues(
                        "crewscope.provider.github.mirror-root="
                                + temporaryDirectory.resolve("mirrors"),
                        "crewscope.provider.github.ask-pass-root="
                                + temporaryDirectory.resolve("credentials"))
                .run(context -> context.assertThat()
                        .hasNotFailed()
                        .hasSingleBean(GitHubPushPort.class)
                        .hasSingleBean(GitHubPushAdapter.class));

        runner().run(context -> context.assertThat()
                .hasNotFailed()
                .doesNotHaveBean(GitHubPushPort.class));
    }

    @Test
    void rejectsCredentialBearingOrNonHttpsGitOrigins() {
        runner()
                .withBean(ManagedRepositoryResolver.class, () -> mock(ManagedRepositoryResolver.class))
                .withBean(GitCommandExecutor.class, () -> mock(GitCommandExecutor.class))
                .withBean(ProviderBindingRepository.class, () -> mock(ProviderBindingRepository.class))
                .withBean(RepositoryBindingRepository.class, () -> mock(RepositoryBindingRepository.class))
                .withPropertyValues(
                        "crewscope.provider.github.mirror-root="
                                + temporaryDirectory.resolve("bad-mirrors"),
                        "crewscope.provider.github.git-base-uri=https://token@github.com")
                .run(context -> context.assertThat().hasFailed());
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(GitHubProviderApplicationConfiguration.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(TimeProvider.class, () -> mock(TimeProvider.class))
                .withBean(ConnectionRepository.class, () -> mock(ConnectionRepository.class))
                .withBean(ConnectionGrantRepository.class, () -> mock(ConnectionGrantRepository.class))
                .withBean(CredentialStore.class, () -> mock(CredentialStore.class))
                .withBean(GitHubProviderRepository.class, () -> mock(GitHubProviderRepository.class))
                .withBean(GitHubProviderPort.class, () -> mock(GitHubProviderPort.class));
    }
}
