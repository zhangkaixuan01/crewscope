package io.crewscope.domain.audit;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Append-only, Team-scoped and browser-safe Audit query fact. */
public record AuditQueryEvent(
        AuditEventId id,
        OrganizationId organizationId,
        TeamId teamId,
        AuditEventCategory category,
        AuditOutcome outcome,
        AuditIdentityChain identity,
        AggregateReference subject,
        Optional<AuditProviderReference> providerReference,
        AuditCorrelationReference correlation,
        AuditRetentionLevel retentionLevel,
        UtcTimestamp occurredAt,
        AuditRedactedSummary summary) {

    public AuditQueryEvent {
        id = Objects.requireNonNull(id, "id");
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        category = Objects.requireNonNull(category, "category");
        outcome = Objects.requireNonNull(outcome, "outcome");
        identity = Objects.requireNonNull(identity, "identity");
        subject = Objects.requireNonNull(subject, "subject");
        providerReference = Objects.requireNonNull(providerReference, "providerReference");
        correlation = Objects.requireNonNull(correlation, "correlation");
        retentionLevel = Objects.requireNonNull(retentionLevel, "retentionLevel");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        summary = Objects.requireNonNull(summary, "summary");
        if (category != summary.category()) {
            throw new DomainValidationException(
                    "auditEvent.category", "must match the registered summary schema");
        }
    }
}
