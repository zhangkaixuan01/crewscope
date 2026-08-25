package io.crewscope.application.audit;

import io.crewscope.domain.audit.AuditEventId;
import io.crewscope.domain.audit.AuditQueryEvent;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Validated newest-first keyset page returned by the Audit query Port. */
public record AuditPage(AuditQuery query, List<AuditQueryEvent> events, boolean hasMore) {

    public AuditPage {
        query = Objects.requireNonNull(query, "query");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        if (events.size() > query.limit()) {
            throw new IllegalArgumentException("Audit page exceeds the requested limit");
        }
        if (hasMore && events.isEmpty()) {
            throw new IllegalArgumentException("Audit page with hasMore must contain a cursor row");
        }
        validateEvents(query, events);
    }

    public Optional<AuditCursor> nextCursor() {
        return hasMore && !events.isEmpty()
                ? Optional.of(AuditCursor.from(
                        query.cursorScope(), events.get(events.size() - 1)))
                : Optional.empty();
    }

    private static void validateEvents(AuditQuery query, List<AuditQueryEvent> events) {
        AuditCursor previous = query.after().orElse(null);
        Set<AuditEventId> ids = new HashSet<>();
        for (AuditQueryEvent event : events) {
            AuditCursor current = AuditCursor.from(query.cursorScope(), event);
            if (!query.filter().matches(event)) {
                throw new IllegalArgumentException(
                        "Audit page contains an event outside the normalized filter");
            }
            if (previous != null && !previous.precedes(event)) {
                throw new IllegalArgumentException(
                        "Audit page must be strictly ordered by occurredAt and Event ID descending");
            }
            if (!ids.add(event.id())) {
                throw new IllegalArgumentException("Audit page must not repeat an event identity");
            }
            previous = current;
        }
    }
}
