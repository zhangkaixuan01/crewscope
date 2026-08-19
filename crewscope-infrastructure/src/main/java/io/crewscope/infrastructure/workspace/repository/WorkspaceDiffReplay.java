package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.coding.DiffManifest;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Bounded replay response; unavailable history carries a complete authoritative RESET snapshot. */
public record WorkspaceDiffReplay(
        List<WorkspaceDiffEvent> events,
        boolean hasMore,
        boolean resetRequired,
        Optional<DiffManifest> resetManifest) {

    public WorkspaceDiffReplay {
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        resetManifest = Objects.requireNonNull(resetManifest, "resetManifest");
        if (resetRequired != resetManifest.isPresent() || (resetRequired && !events.isEmpty())) {
            throw new IllegalArgumentException("reset response shape is inconsistent");
        }
    }

    static WorkspaceDiffReplay reset(DiffManifest manifest) {
        return new WorkspaceDiffReplay(List.of(), false, true, Optional.of(manifest));
    }
}
