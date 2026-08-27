package io.crewscope.application.correlation;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.UUID;

/** Scope-bound newest-first keyset position for a correlation graph. */
public record CorrelationCursor(
        OrganizationId organizationId,
        TeamId teamId,
        UUID correlationId,
        UtcTimestamp occurredAt,
        UUID eventId,
        CorrelationEventSource source) {

    public CorrelationCursor {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        eventId = Objects.requireNonNull(eventId, "eventId");
        source = Objects.requireNonNull(source, "source");
    }
}
