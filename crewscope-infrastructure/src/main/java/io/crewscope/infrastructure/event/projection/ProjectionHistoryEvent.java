package io.crewscope.infrastructure.event.projection;

import io.crewscope.application.event.publication.EventPublication;
import java.util.Objects;

/** Canonical publication plus its durable history keyset position. */
public record ProjectionHistoryEvent(
        EventPublication publication, ProjectionHistoryCursor cursor) {

    public ProjectionHistoryEvent {
        publication = Objects.requireNonNull(publication, "publication");
        cursor = Objects.requireNonNull(cursor, "cursor");
    }
}
