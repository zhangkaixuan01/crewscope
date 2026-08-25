package io.crewscope.application.audit;

import io.crewscope.domain.audit.AuditEventId;
import io.crewscope.domain.audit.AuditQueryEvent;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Scope-bound keyset position ordered by occurredAt and AuditEvent ID descending. */
public record AuditCursor(
        AuditCursorScope scope, UtcTimestamp occurredAt, AuditEventId eventId) {

    public AuditCursor {
        scope = Objects.requireNonNull(scope, "scope");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        eventId = Objects.requireNonNull(eventId, "eventId");
    }

    public static AuditCursor from(AuditCursorScope scope, AuditQueryEvent event) {
        AuditQueryEvent required = Objects.requireNonNull(event, "event");
        requireEventScope(Objects.requireNonNull(scope, "scope"), required);
        return new AuditCursor(scope, required.occurredAt(), required.id());
    }

    public AuditCursor requireScope(AuditCursorScope expectedScope) {
        if (!scope.equals(Objects.requireNonNull(expectedScope, "expectedScope"))) {
            throw new IllegalArgumentException(
                    "Audit cursor does not belong to the requested Team and filter scope");
        }
        return this;
    }

    /** Returns true when the event belongs after this cursor in newest-first keyset order. */
    public boolean precedes(AuditQueryEvent event) {
        AuditQueryEvent required = Objects.requireNonNull(event, "event");
        requireEventScope(scope, required);
        int timeOrder = required.occurredAt().compareTo(occurredAt);
        return timeOrder < 0
                || timeOrder == 0 && comparePostgresUuid(
                                required.id().value(), eventId.value())
                        < 0;
    }

    private static void requireEventScope(AuditCursorScope scope, AuditQueryEvent event) {
        if (!scope.organizationId().equals(event.organizationId())
                || !scope.teamId().equals(event.teamId())) {
            throw new IllegalArgumentException(
                    "Audit event does not belong to the cursor tenant scope");
        }
    }

    /** Matches PostgreSQL UUID's unsigned 16-byte lexical ordering for keyset tie breaking. */
    private static int comparePostgresUuid(java.util.UUID left, java.util.UUID right) {
        int most = Long.compareUnsigned(
                left.getMostSignificantBits(), right.getMostSignificantBits());
        return most != 0
                ? most
                : Long.compareUnsigned(
                        left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }
}
