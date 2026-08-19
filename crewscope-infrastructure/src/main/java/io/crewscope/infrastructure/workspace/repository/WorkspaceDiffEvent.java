package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.coding.DiffFileEntry;
import io.crewscope.domain.coding.DiffGeneration;
import io.crewscope.domain.coding.DiffPath;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable replay envelope for one Workspace Diff projection transition. */
public record WorkspaceDiffEvent(
        WorkItemScope scope,
        io.crewscope.domain.coding.ExecutionWorkspaceId workspaceId,
        UUID streamEpoch,
        long sequence,
        DiffGeneration generation,
        UUID eventId,
        WorkspaceDiffEventKind kind,
        String cursor,
        List<DiffFileEntry> upserts,
        List<DiffPath> removals,
        RuntimeContentHash manifestHash,
        UtcTimestamp occurredAt) {

    public WorkspaceDiffEvent {
        scope = Objects.requireNonNull(scope, "scope");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        streamEpoch = Objects.requireNonNull(streamEpoch, "streamEpoch");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        generation = Objects.requireNonNull(generation, "generation");
        eventId = Objects.requireNonNull(eventId, "eventId");
        kind = Objects.requireNonNull(kind, "kind");
        if (cursor == null || cursor.isBlank() || cursor.length() > 1_024) {
            throw new IllegalArgumentException("cursor must be bounded and non-blank");
        }
        upserts = List.copyOf(Objects.requireNonNull(upserts, "upserts"));
        removals = List.copyOf(Objects.requireNonNull(removals, "removals"));
        requireUniquePaths(upserts, removals);
        manifestHash = Objects.requireNonNull(manifestHash, "manifestHash");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }

    private static void requireUniquePaths(
            List<DiffFileEntry> upserts, List<DiffPath> removals) {
        Set<DiffPath> paths = new HashSet<>();
        upserts.forEach(entry -> {
            DiffFileEntry required = Objects.requireNonNull(entry, "upsert");
            if (!paths.add(required.path())) {
                throw new IllegalArgumentException("upsert paths must be unique");
            }
        });
        removals.forEach(path -> {
            if (!paths.add(Objects.requireNonNull(path, "removal"))) {
                throw new IllegalArgumentException("upsert and removal paths must be disjoint");
            }
        });
    }
}
