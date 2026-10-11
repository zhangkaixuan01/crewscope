package io.crewscope.server.api;

import io.crewscope.domain.shared.audit.AuditMetadata;
import java.time.Instant;
import java.util.UUID;

/** Shared audit column projection. */
public record AuditResponse(
        UUID createdByPrincipalId,
        Instant createdAt,
        UUID updatedByPrincipalId,
        Instant updatedAt) {
    static AuditResponse from(AuditMetadata value) {
        return new AuditResponse(
                value.createdBy().map(id -> id.value()).orElse(null),
                value.createdAt().value(),
                value.updatedBy().map(id -> id.value()).orElse(null),
                value.updatedAt().value());
    }
}
