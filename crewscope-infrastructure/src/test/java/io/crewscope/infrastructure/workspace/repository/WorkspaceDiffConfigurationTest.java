package io.crewscope.infrastructure.workspace.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.crewscope.application.artifact.ArtifactStore;
import io.crewscope.application.coding.DiffArtifactRepository;
import io.crewscope.application.coding.ExecutionWorkspaceRepository;
import io.crewscope.application.coding.CodingTaskTimelinePublisher;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Verifies Worker-only M4-I08 assembly and fail-closed replay/preview limits. */
class WorkspaceDiffConfigurationTest {

    @Test
    void createsWatcherReconcilerEventStoreMonitorAndFinalizer() {
        runner()
                .withPropertyValues(
                        "crewscope.runtime.execution-profile=worker",
                        "crewscope.coding.diff.patch-preview-bytes=4096",
                        "crewscope.coding.diff.patch-preview-lines=100",
                        "crewscope.coding.diff.retained-events=32",
                        "crewscope.coding.diff.maximum-replay-events=16")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(WorkspaceDiffProperties.class)
                        .hasSingleBean(WorkspaceDiffEventStore.class)
                        .hasSingleBean(GitWorkspaceDiffReconciler.class)
                        .hasSingleBean(WorkspaceDiffWatcherFactory.class)
                        .hasSingleBean(WorkspaceDiffMonitorFactory.class)
                        .hasSingleBean(PatchArtifactWriter.class)
                        .hasSingleBean(WorkspaceDiffFinalizer.class));
    }

    @Test
    void pureServerAndMissingPortsDoNotCreateDiffBeans() {
        runner()
                .withPropertyValues("crewscope.runtime.execution-profile=server")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(WorkspaceDiffFinalizer.class));
        new ApplicationContextRunner()
                .withPropertyValues("crewscope.runtime.execution-profile=worker")
                .withUserConfiguration(WorkspaceDiffConfiguration.class)
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(WorkspaceDiffFinalizer.class));
    }

    @Test
    void rejectsReplayLimitAboveRetention() {
        runner()
                .withPropertyValues(
                        "crewscope.runtime.execution-profile=worker",
                        "crewscope.coding.diff.retained-events=10",
                        "crewscope.coding.diff.maximum-replay-events=11")
                .run(context -> assertThat(context).hasFailed());
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withBean(ArtifactStore.class, () -> mock(ArtifactStore.class))
                .withBean(DiffArtifactRepository.class, () -> mock(DiffArtifactRepository.class))
                .withBean(
                        ExecutionWorkspaceRepository.class,
                        () -> mock(ExecutionWorkspaceRepository.class))
                .withBean(GitCommandExecutor.class, () -> mock(GitCommandExecutor.class))
                .withBean(
                        ManagedRepositoryResolver.class,
                        () -> mock(ManagedRepositoryResolver.class))
                .withBean(CodingTaskTimelinePublisher.class, () -> CodingTaskTimelinePublisher.NO_OP)
                .withBean(
                        CodingRuntimeArtifactRegistrar.class,
                        () -> mock(CodingRuntimeArtifactRegistrar.class))
                .withBean(TransactionExecutor.class, () -> mock(TransactionExecutor.class))
                .withUserConfiguration(WorkspaceDiffConfiguration.class);
    }
}
