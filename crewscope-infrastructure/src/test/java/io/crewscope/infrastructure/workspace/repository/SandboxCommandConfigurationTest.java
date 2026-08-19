package io.crewscope.infrastructure.workspace.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.crewscope.application.artifact.ArtifactStore;
import io.crewscope.application.coding.CommandEvidenceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Verifies Worker-only M4-I07 wiring and fail-closed Tool preview limits. */
class SandboxCommandConfigurationTest {

    @Test
    void createsStructuredCommandFactoryWhenRequiredPortsExist() {
        runner()
                .withPropertyValues(
                        "crewscope.runtime.execution-profile=worker",
                        "crewscope.coding.command.max-tool-result-bytes=32768")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(SandboxCommandProperties.class)
                        .hasSingleBean(SandboxCommandUsageRegistry.class)
                        .hasSingleBean(BuildProfileCommandRunner.class)
                        .hasSingleBean(CommandLogArtifactWriter.class)
                        .hasSingleBean(CommandEvidenceWriter.class)
                        .hasSingleBean(SandboxCommandToolFactory.class));
    }

    @Test
    void pureServerAndMissingPortsDoNotCreateCommandBeans() {
        runner()
                .withPropertyValues("crewscope.runtime.execution-profile=server")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(SandboxCommandToolFactory.class));
        new ApplicationContextRunner()
                .withPropertyValues("crewscope.runtime.execution-profile=worker")
                .withUserConfiguration(SandboxCommandConfiguration.class)
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(SandboxCommandToolFactory.class));
    }

    @Test
    void rejectsUnboundedToolPreview() {
        runner()
                .withPropertyValues(
                        "crewscope.runtime.execution-profile=worker",
                        "crewscope.coding.command.max-tool-result-bytes=100")
                .run(context -> assertThat(context).hasFailed());
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withBean(ArtifactStore.class, () -> org.mockito.Mockito.mock(ArtifactStore.class))
                .withBean(
                        CommandEvidenceRepository.class,
                        () -> org.mockito.Mockito.mock(CommandEvidenceRepository.class))
                .withUserConfiguration(SandboxCommandConfiguration.class);
    }
}
