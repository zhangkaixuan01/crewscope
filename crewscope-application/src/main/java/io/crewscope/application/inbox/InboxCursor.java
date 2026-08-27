package io.crewscope.application.inbox;

import io.crewscope.domain.inbox.InboxItemId;
import io.crewscope.domain.inbox.InboxPriority;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Stable member-queue position bound to one projection generation. */
public record InboxCursor(
        ProjectionGeneration generation,
        InboxPriority priority,
        Optional<UtcTimestamp> deadline,
        UtcTimestamp openedAt,
        InboxItemId inboxItemId) {

    public InboxCursor {
        generation = Objects.requireNonNull(generation, "generation");
        priority = Objects.requireNonNull(priority, "priority");
        deadline = Objects.requireNonNull(deadline, "deadline");
        openedAt = Objects.requireNonNull(openedAt, "openedAt");
        inboxItemId = Objects.requireNonNull(inboxItemId, "inboxItemId");
    }

    public static InboxCursor from(InboxItemView view) {
        InboxItemView source = Objects.requireNonNull(view, "view");
        return new InboxCursor(
                source.item().projectionGeneration(),
                source.item().source().priority(),
                source.item().source().deadline(),
                source.item().source().openedAt(),
                source.item().id());
    }
}
