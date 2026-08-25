package io.crewscope.application.audit;

import io.crewscope.domain.audit.AuditEventId;
import io.crewscope.domain.audit.AuditQueryEvent;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable bounded export rows; serialization and Artifact storage belong to M6-A03/I01. */
public record AuditExportBatch(
        AuditExportRequest request,
        UtcTimestamp generatedAt,
        List<AuditQueryEvent> events) {

    public AuditExportBatch {
        request = Objects.requireNonNull(request, "request");
        generatedAt = Objects.requireNonNull(generatedAt, "generatedAt");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        if (events.size() > request.maximumRows()) {
            throw new IllegalArgumentException("Audit export exceeds the requested row limit");
        }
        // Validate the complete export while preserving one strict global keyset order.
        AuditCursor previous = null;
        Set<AuditEventId> eventIds = new HashSet<>();
        for (AuditQueryEvent event : events) {
            AuditCursor current = AuditCursor.from(request.scope(), event);
            if (!request.filter().matches(event)
                    || previous != null && !previous.precedes(event)
                    || !eventIds.add(event.id())) {
                throw new IllegalArgumentException(
                        "Audit export rows must be unique, match the filter and use newest-first order");
            }
            previous = current;
        }
    }
}
