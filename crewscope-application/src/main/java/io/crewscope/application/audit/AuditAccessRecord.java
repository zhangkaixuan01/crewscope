package io.crewscope.application.audit;

import io.crewscope.domain.audit.AuditOutcome;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.UUID;

/** Safe append-only fact describing use of the Audit Explorer itself. */
public record AuditAccessRecord(
        Operation operation,
        OrganizationId organizationId,
        TeamId teamId,
        Principal actor,
        UUID correlationId,
        AuditOutcome outcome,
        int rowCount,
        UtcTimestamp occurredAt) {

    public enum Operation {
        QUERY,
        EXPORT
    }

    public AuditAccessRecord {
        operation = Objects.requireNonNull(operation, "operation");
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        actor = Objects.requireNonNull(actor, "actor");
        correlationId = AggregateId.requireValue(correlationId, "AuditAccessRecord.correlationId");
        outcome = Objects.requireNonNull(outcome, "outcome");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        if (rowCount < 0 || rowCount > AuditExportRequest.MAXIMUM_ROWS) {
            throw new IllegalArgumentException("Audit access row count is outside the bounded range");
        }
    }
}
