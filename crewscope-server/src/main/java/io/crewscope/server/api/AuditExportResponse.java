package io.crewscope.server.api;

import io.crewscope.application.audit.AuditExportBatch;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Downloadable bounded Audit export artifact using the same reviewed event DTO. */
public record AuditExportResponse(
        Instant generatedAt,
        int rowCount,
        int maximumRows,
        List<AuditEventResponse> events) {

    static AuditExportResponse from(AuditExportBatch batch) {
        AuditExportBatch value = Objects.requireNonNull(batch, "batch");
        List<AuditEventResponse> rows = value.events().stream()
                .map(AuditEventResponse::from)
                .toList();
        return new AuditExportResponse(
                value.generatedAt().value(), rows.size(), value.request().maximumRows(), rows);
    }
}
