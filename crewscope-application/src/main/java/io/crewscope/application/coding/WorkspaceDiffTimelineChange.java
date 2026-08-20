package io.crewscope.application.coding;

import io.crewscope.domain.coding.DiffFileEntry;
import io.crewscope.domain.coding.DiffGeneration;
import io.crewscope.domain.coding.DiffPath;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Infrastructure-neutral immutable input for one live Diff timeline transition. */
public record WorkspaceDiffTimelineChange(
        UUID eventId,
        WorkItemScope scope,
        ExecutionWorkspaceId workspaceId,
        UUID streamEpoch,
        long sequence,
        DiffGeneration generation,
        String changeKind,
        List<DiffFileEntry> upserts,
        List<DiffPath> removals,
        RuntimeContentHash manifestHash,
        UtcTimestamp occurredAt) {

    public WorkspaceDiffTimelineChange {
        eventId = Objects.requireNonNull(eventId, "eventId");
        scope = Objects.requireNonNull(scope, "scope");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        streamEpoch = Objects.requireNonNull(streamEpoch, "streamEpoch");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        generation = Objects.requireNonNull(generation, "generation");
        changeKind = Objects.requireNonNull(changeKind, "changeKind");
        upserts = List.copyOf(Objects.requireNonNull(upserts, "upserts"));
        removals = List.copyOf(Objects.requireNonNull(removals, "removals"));
        manifestHash = Objects.requireNonNull(manifestHash, "manifestHash");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
