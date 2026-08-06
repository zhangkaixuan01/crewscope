package io.crewscope.domain.shared.audit;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/**
 * Current-row provenance for mutable business facts.
 *
 * <p>Unknown actors are supported only for migrated and repaired data. New commands create and
 * modify metadata with a trusted Principal; the full initiator and Agent chain remains in
 * DomainEvent and AuditEvent.
 */
public record AuditMetadata(
        Optional<PrincipalId> createdBy,
        UtcTimestamp createdAt,
        Optional<PrincipalId> updatedBy,
        UtcTimestamp updatedAt) {

    public AuditMetadata {
        createdBy = Objects.requireNonNull(createdBy, "createdBy");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedBy = Objects.requireNonNull(updatedBy, "updatedBy");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.compareTo(createdAt) < 0) {
            throw new DomainValidationException(
                    "audit.updatedAt", "must not be before audit.createdAt");
        }
    }

    /** Creates provenance for a new command-owned business fact. */
    public static AuditMetadata createdBy(PrincipalId actor, UtcTimestamp occurredAt) {
        PrincipalId requiredActor = Objects.requireNonNull(actor, "actor");
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        return new AuditMetadata(
                Optional.of(requiredActor),
                requiredTime,
                Optional.of(requiredActor),
                requiredTime);
    }

    /** Returns provenance for a committed modification while preserving the original creator. */
    public AuditMetadata modifiedBy(PrincipalId actor, UtcTimestamp occurredAt) {
        PrincipalId requiredActor = Objects.requireNonNull(actor, "actor");
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        if (requiredTime.compareTo(updatedAt) < 0) {
            throw new DomainValidationException(
                    "audit.updatedAt", "must not be before the current audit.updatedAt");
        }
        return new AuditMetadata(
                createdBy, createdAt, Optional.of(requiredActor), requiredTime);
    }
}
