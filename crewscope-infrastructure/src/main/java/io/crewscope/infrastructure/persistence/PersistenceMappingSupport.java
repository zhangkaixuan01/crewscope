package io.crewscope.infrastructure.persistence;

import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.audit.LifecycleMetadata;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Shared conversions for persistence-only scalar audit columns. */
public final class PersistenceMappingSupport {

    private PersistenceMappingSupport() {}

    public static Optional<PrincipalId> optionalPrincipal(UUID value) {
        return Optional.ofNullable(value).map(PrincipalId::new);
    }

    public static AuditMetadata audit(
            UUID createdBy, Instant createdAt, UUID updatedBy, Instant updatedAt) {
        return new AuditMetadata(
                optionalPrincipal(createdBy),
                UtcTimestamp.from(createdAt),
                optionalPrincipal(updatedBy),
                UtcTimestamp.from(updatedAt));
    }

    public static LifecycleMetadata lifecycle(Instant createdAt, Instant updatedAt) {
        return new LifecycleMetadata(UtcTimestamp.from(createdAt), UtcTimestamp.from(updatedAt));
    }
}
