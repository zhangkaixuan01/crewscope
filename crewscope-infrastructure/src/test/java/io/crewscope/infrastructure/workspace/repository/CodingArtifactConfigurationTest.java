package io.crewscope.infrastructure.workspace.repository;

import static org.mockito.Mockito.mock;

import io.crewscope.application.artifact.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Server/worker-neutral assembly and deployment-bound validation for M4-I09. */
class CodingArtifactConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CodingArtifactConfiguration.class)
            .withBean(ArtifactStore.class, () -> mock(ArtifactStore.class));

    @Test
    void createsReaderLifecycleAndSharedPublisher() {
        runner.run(context -> context
                .assertThat()
                .hasNotFailed()
                .hasSingleBean(CodingArtifactPublisher.class)
                .hasSingleBean(CodingArtifactReader.class)
                .hasSingleBean(CodingArtifactLifecycle.class)
                .hasSingleBean(CodingArtifactProperties.class));
    }

    @Test
    void rejectsRangeLargerThanArtifactBudget() {
        runner.withPropertyValues(
                        "crewscope.coding.artifact.maximum-artifact-bytes=1024",
                        "crewscope.coding.artifact.maximum-range-bytes=2048")
                .run(context -> context.assertThat().hasFailed());
    }
}
