package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Path-free scheduling hint; raw filesystem paths never cross the Watcher boundary. */
public record WorkspaceDiffHint(
        WorkspaceDiffStreamKey streamKey,
        WorkspaceDiffHintKind kind,
        UtcTimestamp observedAt) {

    public WorkspaceDiffHint {
        streamKey = Objects.requireNonNull(streamKey, "streamKey");
        kind = Objects.requireNonNull(kind, "kind");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
    }
}
