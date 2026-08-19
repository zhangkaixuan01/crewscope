package io.crewscope.infrastructure.workspace.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.coding.AllowedPathSet;
import io.crewscope.domain.coding.ExecutionWorkspaceFingerprint;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real WatchService coverage for debounce, AllowedPaths and restart hints. */
class WorkspaceDiffWatcherM4I08Test {

    @TempDir Path temporaryDirectory;

    @Test
    void emitsPathFreeRestartAndDebouncedAllowedChangeHints() throws Exception {
        Path worktree = Files.createDirectories(temporaryDirectory.resolve("worktree"));
        Files.createDirectories(worktree.resolve("src"));
        Files.createDirectories(worktree.resolve("outside"));
        LinkedBlockingQueue<WorkspaceDiffHint> hints = new LinkedBlockingQueue<>();
        WorkspaceDiffProperties properties = new WorkspaceDiffProperties();
        properties.setDebounce(Duration.ofMillis(50));
        properties.setReconcileInterval(Duration.ofMinutes(10));

        try (WorkspaceDiffWatcher watcher = WorkspaceDiffWatcher.start(
                key(), worktree, AllowedPathSet.of("src"), properties, Clock.systemUTC(), hints::add)) {
            assertEquals(
                    WorkspaceDiffHintKind.FULL_RECONCILE,
                    hints.poll(2, TimeUnit.SECONDS).kind());
            Files.writeString(worktree.resolve("src/A.java"), "class A {}\n", StandardCharsets.UTF_8);
            WorkspaceDiffHint changed = hints.poll(5, TimeUnit.SECONDS);
            assertTrue(changed != null, () -> "Watcher failure: " + watcher.lastFailure());
            assertEquals(WorkspaceDiffHintKind.CHANGED, changed.kind());
            assertEquals(key().scope().getClass(), changed.streamKey().scope().getClass());

            Files.writeString(worktree.resolve("outside/secret.txt"), "ignored\n");
            assertTrue(hints.poll(250, TimeUnit.MILLISECONDS) == null);
            assertFalse(watcher.lastFailure().isPresent());
        }
    }

    private static WorkspaceDiffStreamKey key() {
        return new WorkspaceDiffStreamKey(
                new WorkItemScope(
                        OrganizationId.from("00000000-0000-0000-0000-000000008001"),
                        TeamId.from("00000000-0000-0000-0000-000000008002"),
                        WorkspaceId.from("00000000-0000-0000-0000-000000008003"),
                        WorkProjectId.from("00000000-0000-0000-0000-000000008004")),
                ExecutionWorkspaceId.from("00000000-0000-0000-0000-000000008005"),
                new ExecutionWorkspaceFingerprint("8".repeat(64)),
                0);
    }
}
