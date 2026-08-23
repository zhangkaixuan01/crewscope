package io.crewscope.server.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.crewscope.application.credential.CredentialStore;
import io.crewscope.application.github.GitHubProviderPort;
import io.crewscope.application.github.GitHubProviderRepository;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.domain.provider.ProviderConnectionRequirement;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.infrastructure.github.GitHubProviderAdapter;
import io.crewscope.integration.provider.sourcecode.GitHubSourceCodeProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

/** Explicit Spring and fixed Provider contract for M5-I08. */
class GitHubProviderApplicationConfigurationM5I08Test {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GitHubProviderApplicationConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(TimeProvider.class, () -> mock(TimeProvider.class))
            .withBean(ConnectionRepository.class, () -> mock(ConnectionRepository.class))
            .withBean(ConnectionGrantRepository.class, () -> mock(ConnectionGrantRepository.class))
            .withBean(CredentialStore.class, () -> mock(CredentialStore.class))
            .withBean(GitHubProviderRepository.class, () -> mock(GitHubProviderRepository.class));

    @Test
    void wiresTheHttpsAdapterAndFixedConnectionRequiredProviderContract() {
        runner.run(context -> {
            context.assertThat()
                    .hasNotFailed()
                    .hasSingleBean(GitHubProviderPort.class)
                    .hasSingleBean(GitHubProviderAdapter.class)
                    .hasSingleBean(GitHubSourceCodeProvider.class);
            GitHubSourceCodeProvider provider = context.getBean(GitHubSourceCodeProvider.class);
            assertThat(provider.connectionRequirement())
                    .isEqualTo(ProviderConnectionRequirement.REQUIRED);
            assertThat(provider.connectorKey()).contains("github-source-code");
            assertThat(provider.capabilities().values())
                    .extracting(value -> value.value())
                    .containsExactlyInAnyOrder(
                            "source.repository.catalog",
                            "source.repository.read",
                            "source.repository.push",
                            "source.pull-request.create");
        });
    }

    @Test
    void allowsHttpOnlyForAnExplicitLoopbackTestEndpoint() {
        runner.withPropertyValues(
                        "crewscope.provider.github.api-base-uri=http://127.0.0.1:18080",
                        "crewscope.provider.github.allow-loopback-http=true")
                .run(context -> context.assertThat().hasNotFailed());

        runner.withPropertyValues(
                        "crewscope.provider.github.api-base-uri=http://127.0.0.1:18080",
                        "crewscope.provider.github.allow-loopback-http=false")
                .run(context -> context.assertThat().hasFailed());
        runner.withPropertyValues(
                        "crewscope.provider.github.api-base-uri=http://github.example",
                        "crewscope.provider.github.allow-loopback-http=true")
                .run(context -> context.assertThat().hasFailed());
    }

    @Test
    void rejectsTimeoutsAndCredentialWindowsAboveTheirSecurityCeilings() {
        runner.withPropertyValues(
                        "crewscope.provider.github.request-timeout=3m",
                        "crewscope.provider.github.credential-handle-ttl=6m")
                .run(context -> context.assertThat().hasFailed());
    }
}
