package io.crewscope.application.organization.governance;

import io.crewscope.domain.audit.AuditOutcome;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.UUID;

/**
 * Safe append-only fact describing one governance-plane action: artifact downloads (including
 * denials) and deletion step settlements. Transport- and runtime-security facts (the
 * JdbcAuditAccessRecorder positioning) — they never ride the DomainEvent chain because there is
 * no aggregate behind a download or a step settlement. Retention sweeps self-evidence inside the
 * retention gateway's own transaction instead.
 */
public record GovernanceAuditRecord(
        Operation operation,
        OrganizationId organizationId,
        Principal actor,
        UUID correlationId,
        AuditOutcome outcome,
        String targetRef,
        int rowCount,
        String detail,
        UtcTimestamp occurredAt) {

    public enum Operation {
        EXPORT_DOWNLOAD,
        DELETION_STEP
    }

    public GovernanceAuditRecord {
        operation = Objects.requireNonNull(operation, "operation");
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        actor = Objects.requireNonNull(actor, "actor");
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        outcome = Objects.requireNonNull(outcome, "outcome");
        targetRef = Objects.requireNonNull(targetRef, "targetRef");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        if (rowCount < 0) {
            throw new IllegalArgumentException("Governance audit row count must not be negative");
        }
    }
}
