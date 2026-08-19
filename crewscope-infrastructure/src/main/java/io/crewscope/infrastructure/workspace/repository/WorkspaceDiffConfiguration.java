package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.application.artifact.ArtifactStore;
import io.crewscope.application.coding.DiffArtifactRepository;
import io.crewscope.application.coding.ExecutionWorkspaceRepository;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/** Worker-only assembly for Diff watching, replay, reconciliation and final publication. */
@Configuration(proxyBeanMethods = false)
@Conditional(WorkerManagedRepositoryCondition.class)
@ConditionalOnBean({
    ArtifactStore.class,
    DiffArtifactRepository.class,
    ExecutionWorkspaceRepository.class,
    GitCommandExecutor.class,
    ManagedRepositoryResolver.class
})
@EnableConfigurationProperties({
    WorkspaceDiffProperties.class,
    CodingArtifactProperties.class
})
public class WorkspaceDiffConfiguration {

    @Bean
    @ConditionalOnMissingBean(WorkspaceDiffCursorCodec.class)
    WorkspaceDiffCursorCodec workspaceDiffCursorCodec(WorkspaceDiffProperties properties) {
        // Stable deployment key verifies old cursors; rebuilt streams rotate Epoch and RESET.
        return new WorkspaceDiffCursorCodec(properties.cursorSecretBytes());
    }

    @Bean
    @ConditionalOnMissingBean(WorkspaceDiffEventStore.class)
    WorkspaceDiffEventStore workspaceDiffEventStore(
            WorkspaceDiffProperties properties, WorkspaceDiffCursorCodec cursors) {
        return new WorkspaceDiffEventStore(properties, cursors, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(GitWorkspaceDiffReconciler.class)
    GitWorkspaceDiffReconciler gitWorkspaceDiffReconciler(
            GitCommandExecutor git, WorkspaceDiffProperties properties) {
        return new GitWorkspaceDiffReconciler(git, properties);
    }

    @Bean
    @ConditionalOnMissingBean(WorkspaceDiffWatcherFactory.class)
    WorkspaceDiffWatcherFactory workspaceDiffWatcherFactory(
            WorkspaceDiffProperties properties) {
        return new WorkspaceDiffWatcherFactory(properties, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(WorkspaceDiffMonitorFactory.class)
    WorkspaceDiffMonitorFactory workspaceDiffMonitorFactory(
            GitWorkspaceDiffReconciler reconciler,
            WorkspaceDiffEventStore events,
            WorkspaceDiffWatcherFactory watchers) {
        return new WorkspaceDiffMonitorFactory(reconciler, events, watchers);
    }

    @Bean
    @ConditionalOnMissingBean(PatchArtifactWriter.class)
    PatchArtifactWriter patchArtifactWriter(
            ArtifactStore artifactStore, CodingArtifactProperties properties) {
        return new PatchArtifactWriter(
                new CodingArtifactPublisher(artifactStore, properties));
    }

    @Bean
    @ConditionalOnMissingBean(WorkspaceDiffFinalizer.class)
    WorkspaceDiffFinalizer workspaceDiffFinalizer(
            ManagedRepositoryResolver repositories,
            GitCommandExecutor git,
            GitWorkspaceDiffReconciler reconciler,
            PatchArtifactWriter patches,
            DiffArtifactRepository diffs,
            ExecutionWorkspaceRepository workspaces) {
        return new WorkspaceDiffFinalizer(
                repositories,
                git,
                reconciler,
                patches,
                diffs,
                workspaces,
                Clock.systemUTC());
    }
}
