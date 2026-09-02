package io.crewscope.server.config.application;

import static org.mockito.Mockito.mock;

import io.crewscope.application.coding.RepositoryBindingAccessPolicy;
import io.crewscope.application.github.GitHubConnectionPolicySettings;
import io.crewscope.application.github.GitHubProviderRepository;
import io.crewscope.application.github.GitHubRepositoryImportApplicationService;
import io.crewscope.application.github.GitHubRepositoryImportJobRepository;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.provider.ProviderBindingRepository;
import io.crewscope.domain.shared.time.TimeProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Prevents release profiles from silently omitting the GitHub repository import service. */
class GitHubRepositoryImportApplicationConfigurationM8Q02Test {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GitHubRepositoryImportApplicationConfiguration.class)
            .withBean(GitHubRepositoryImportJobRepository.class,
                    () -> mock(GitHubRepositoryImportJobRepository.class))
            .withBean(GitHubProviderRepository.class, () -> mock(GitHubProviderRepository.class))
            .withBean(ConnectionRepository.class, () -> mock(ConnectionRepository.class))
            .withBean(ConnectionGrantRepository.class, () -> mock(ConnectionGrantRepository.class))
            .withBean(ProviderBindingRepository.class, () -> mock(ProviderBindingRepository.class))
            .withBean(RepositoryBindingAccessPolicy.class,
                    () -> mock(RepositoryBindingAccessPolicy.class))
            .withBean(GitHubConnectionPolicySettings.class,
                    () -> mock(GitHubConnectionPolicySettings.class))
            .withBean(TimeProvider.class, () -> mock(TimeProvider.class));

    @Test
    void alwaysWiresTheImportServiceWhenItsRequiredPortsExist() {
        runner.run(context -> context.assertThat()
                .hasNotFailed()
                .hasSingleBean(GitHubRepositoryImportApplicationService.class));
    }
}
