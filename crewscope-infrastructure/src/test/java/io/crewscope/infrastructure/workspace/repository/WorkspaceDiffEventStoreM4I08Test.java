package io.crewscope.infrastructure.workspace.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.crewscope.domain.coding.DiffFileEntry;
import io.crewscope.domain.coding.DiffFileKind;
import io.crewscope.domain.coding.DiffGeneration;
import io.crewscope.domain.coding.DiffManifest;
import io.crewscope.domain.coding.ExecutionWorkspaceFingerprint;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** M4-I08 replay, cursor, retention and epoch convergence coverage. */
class WorkspaceDiffEventStoreM4I08Test {

    private WorkspaceDiffEventStore store;
    private WorkspaceDiffStreamKey key;

    @BeforeEach
    void setUp() {
        WorkspaceDiffProperties properties = new WorkspaceDiffProperties();
        properties.setRetainedEvents(2);
        properties.setMaximumReplayEvents(2);
        store = new WorkspaceDiffEventStore(
                properties,
                new WorkspaceDiffCursorCodec("m4-i08-diff-cursor-secret-32bytes".getBytes()),
                Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC));
        key = new WorkspaceDiffStreamKey(
                new WorkItemScope(
                        OrganizationId.generate(),
                        TeamId.generate(),
                        WorkspaceId.generate(),
                        WorkProjectId.generate()),
                ExecutionWorkspaceId.generate(),
                new ExecutionWorkspaceFingerprint("a".repeat(64)),
                0);
    }

    @Test
    void replaysDirectSuccessorAndSuppressesDuplicateAuthority() {
        DiffManifest first = manifest(1, entry("src/A.java", "a"));
        WorkspaceDiffEvent reset = store.restart(key, first);
        DiffManifest second = manifest(2, entry("src/A.java", "b"));
        WorkspaceDiffEvent delta = store.reconcile(key, second).orElseThrow();

        WorkspaceDiffReplay replay = store.replay(key, reset.cursor(), 10);
        assertEquals(List.of(delta), replay.events());
        assertFalse(replay.hasMore());
        assertFalse(replay.resetRequired());
        assertTrue(store.reconcile(key, second).isEmpty());
    }

    @Test
    void retentionGapEpochRotationAndRecoveryGenerationRequireReset() {
        DiffManifest first = manifest(1, entry("src/A.java", "a"));
        WorkspaceDiffEvent reset = store.restart(key, first);
        DiffManifest second = manifest(2, entry("src/A.java", "b"));
        store.reconcile(key, second).orElseThrow();
        DiffManifest third = manifest(3, entry("src/A.java", "c"));
        store.reconcile(key, third).orElseThrow();

        WorkspaceDiffReplay expired = store.replay(key, reset.cursor(), 2);
        assertTrue(expired.resetRequired());
        assertEquals(third.contentHash(), expired.resetManifest().orElseThrow().contentHash());

        WorkspaceDiffEvent replacement = store.restart(key, third);
        WorkspaceDiffReplay oldEpoch = store.replay(key, storeCursor(reset), 2);
        assertTrue(oldEpoch.resetRequired());
        assertFalse(replacement.streamEpoch().equals(reset.streamEpoch()));

        WorkspaceDiffStreamKey recovered = new WorkspaceDiffStreamKey(
                key.scope(), key.workspaceId(), new ExecutionWorkspaceFingerprint("b".repeat(64)), 1);
        WorkspaceDiffEvent recoveredReset = store.restart(recovered, third);
        assertEquals(WorkspaceDiffEventKind.RESET, recoveredReset.kind());
        assertTrue(store.latest(key).isEmpty());
    }

    @Test
    void rejectsTamperedCursorAndNonSuccessorGeneration() {
        DiffManifest first = manifest(1, entry("src/A.java", "a"));
        WorkspaceDiffEvent reset = store.restart(key, first);
        String cursor = reset.cursor();
        char replacement = cursor.endsWith("A") ? 'B' : 'A';
        String tampered = cursor.substring(0, cursor.length() - 1) + replacement;
        WorkspaceDiffException invalid = assertThrows(
                WorkspaceDiffException.class,
                () -> store.replay(key, tampered, 1));
        assertEquals(WorkspaceDiffError.INVALID_CURSOR, invalid.error());

        WorkspaceDiffException generation = assertThrows(
                WorkspaceDiffException.class,
                () -> store.reconcile(key, manifest(3, entry("src/A.java", "b"))));
        assertEquals(WorkspaceDiffError.INVALID_CONTEXT, generation.error());
    }

    @Test
    void concurrentDuplicateReconcilePublishesOneDelta() {
        DiffManifest first = manifest(1, entry("src/A.java", "a"));
        store.restart(key, first);
        DiffManifest second = manifest(2, entry("src/A.java", "b"));

        CompletableFuture<Optional<WorkspaceDiffEvent>> left = CompletableFuture.supplyAsync(
                () -> store.reconcile(key, second));
        CompletableFuture<Optional<WorkspaceDiffEvent>> right = CompletableFuture.supplyAsync(
                () -> store.reconcile(key, second));
        long published = List.of(left.join(), right.join()).stream()
                .filter(Optional::isPresent)
                .count();

        assertEquals(1, published);
        assertEquals(second.contentHash(), store.latest(key).orElseThrow().contentHash());
    }

    @Test
    void durablePublicationFailureDoesNotAdvanceReplayAuthority() {
        io.crewscope.application.coding.CodingTaskTimelinePublisher timeline =
                mock(io.crewscope.application.coding.CodingTaskTimelinePublisher.class);
        doThrow(new IllegalStateException("database unavailable"))
                .doNothing()
                .when(timeline)
                .workspaceDiffChanged(any());
        WorkspaceDiffProperties properties = new WorkspaceDiffProperties();
        WorkspaceDiffEventStore durable = new WorkspaceDiffEventStore(
                properties,
                new WorkspaceDiffCursorCodec("m4-a05-diff-cursor-secret-32bytes".getBytes()),
                Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC),
                timeline);
        DiffManifest first = manifest(1, entry("src/A.java", "a"));

        assertThrows(IllegalStateException.class, () -> durable.restart(key, first));
        assertTrue(durable.latest(key).isEmpty());

        WorkspaceDiffEvent retried = durable.restart(key, first);
        assertEquals(1, retried.sequence());
        verify(timeline, times(2)).workspaceDiffChanged(any());
    }

    private static String storeCursor(WorkspaceDiffEvent event) {
        return event.cursor();
    }

    private static DiffManifest manifest(long generation, DiffFileEntry... files) {
        return DiffManifest.capture(new DiffGeneration(generation), List.of(files));
    }

    private static DiffFileEntry entry(String path, String content) {
        String patch = "diff --git a/" + path + " b/" + path + "\n+" + content + "\n";
        return DiffFileEntry.text(
                path,
                Optional.empty(),
                DiffFileKind.MODIFIED,
                1,
                0,
                false,
                RuntimeContentHash.sha256(patch),
                Optional.of(patch));
    }
}
