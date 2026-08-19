package io.crewscope.infrastructure.workspace.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Verifies Worker-only M4-I06 wiring and fail-closed parser ceilings. */
class CodingFilesystemConfigurationTest {

    @Test
    void createsFilesystemFactoryAndSharedUsageRegistryForWorker() {
        runner()
                .withPropertyValues(
                        "crewscope.runtime.execution-profile=worker",
                        "crewscope.coding.filesystem.max-tool-content-bytes=262144",
                        "crewscope.coding.filesystem.max-patch-hunks=100")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(CodingFilesystemProperties.class)
                        .hasSingleBean(CodingFilesystemUsageRegistry.class)
                        .hasSingleBean(CodingFilesystemToolFactory.class));
    }

    @Test
    void pureServerProfileDoesNotCreateHostMutationBeans() {
        runner()
                .withPropertyValues("crewscope.runtime.execution-profile=server")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(CodingFilesystemToolFactory.class)
                        .doesNotHaveBean(CodingFilesystemUsageRegistry.class));
    }

    @Test
    void rejectsUnboundedFilesystemProperties() {
        runner()
                .withPropertyValues(
                        "crewscope.runtime.execution-profile=worker",
                        "crewscope.coding.filesystem.max-tool-content-bytes=100",
                        "crewscope.coding.filesystem.max-patch-hunks=0")
                .run(context -> assertThat(context).hasFailed());
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withBean(
                        GitCommandExecutor.class,
                        () -> org.mockito.Mockito.mock(GitCommandExecutor.class))
                .withUserConfiguration(CodingFilesystemConfiguration.class);
    }
}
