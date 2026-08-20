package io.crewscope.domain.coding.event;

import io.crewscope.domain.coding.DiffFileEntry;
import io.crewscope.domain.coding.DiffPath;
import io.crewscope.domain.shared.DomainEvent;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Safe RESET/DELTA payload persisted in the Task timeline. */
public record WorkspaceDiffChanged(
        UUID workspaceId,
        UUID taskExecutionId,
        int attempt,
        UUID streamEpoch,
        long sequence,
        long diffGeneration,
        String changeKind,
        String manifestHash,
        List<FileChange> upserts,
        List<String> removals) implements DomainEvent {

    public WorkspaceDiffChanged {
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        streamEpoch = Objects.requireNonNull(streamEpoch, "streamEpoch");
        if (attempt < 1 || sequence < 1 || diffGeneration < 1) {
            throw new IllegalArgumentException("Diff event counters must be positive");
        }
        changeKind = Objects.requireNonNull(changeKind, "changeKind");
        manifestHash = Objects.requireNonNull(manifestHash, "manifestHash");
        upserts = List.copyOf(Objects.requireNonNull(upserts, "upserts"));
        removals = List.copyOf(Objects.requireNonNull(removals, "removals"));
    }

    public static List<FileChange> files(List<DiffFileEntry> entries) {
        return Objects.requireNonNull(entries, "entries").stream()
                .map(FileChange::from)
                .toList();
    }

    /** Patch content remains behind the separately authorized Artifact API. */
    public record FileChange(
            String path,
            Optional<String> oldPath,
            String changeType,
            long additions,
            long deletions,
            boolean binary,
            boolean patchTruncated,
            String patchSha256) {

        public FileChange {
            path = Objects.requireNonNull(path, "path");
            oldPath = Objects.requireNonNull(oldPath, "oldPath");
            changeType = Objects.requireNonNull(changeType, "changeType");
            if (additions < 0 || deletions < 0) {
                throw new IllegalArgumentException("Diff line statistics must not be negative");
            }
            patchSha256 = Objects.requireNonNull(patchSha256, "patchSha256");
        }

        static FileChange from(DiffFileEntry entry) {
            DiffFileEntry value = Objects.requireNonNull(entry, "entry");
            return new FileChange(
                    value.path().value(),
                    value.oldPath().map(DiffPath::value),
                    value.kind().name(),
                    value.additions(),
                    value.deletions(),
                    value.binary(),
                    value.patchTruncated(),
                    value.patchSha256().value());
        }
    }
}
