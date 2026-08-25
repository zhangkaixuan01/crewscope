package io.crewscope.application.audit;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.time.Duration;
import java.util.Objects;

/** Explicit time-bounded and row-bounded request for a governance Audit export. */
public record AuditExportRequest(
        AuditCursorScope scope, AuditQueryFilter filter, int maximumRows) {

    public static final int MAXIMUM_ROWS = 10_000;
    public static final Duration MAXIMUM_TIME_RANGE = Duration.ofDays(31);

    public AuditExportRequest {
        scope = Objects.requireNonNull(scope, "scope");
        filter = Objects.requireNonNull(filter, "filter");
        if (!scope.filterFingerprint().equals(filter.fingerprint())) {
            throw new IllegalArgumentException(
                    "Audit export filter must match the request scope fingerprint");
        }
        if (maximumRows < 1 || maximumRows > MAXIMUM_ROWS) {
            throw new DomainValidationException(
                    "auditExport.maximumRows",
                    "must be between 1 and " + MAXIMUM_ROWS);
        }
        var from = filter.occurredFrom().orElseThrow(() -> new DomainValidationException(
                "auditExport.occurredFrom", "is required"));
        var before = filter.occurredBefore().orElseThrow(() -> new DomainValidationException(
                "auditExport.occurredBefore", "is required"));
        if (Duration.between(from.value(), before.value()).compareTo(MAXIMUM_TIME_RANGE) > 0) {
            throw new DomainValidationException(
                    "auditExport.occurredAt", "time range must not exceed 31 days");
        }
    }

    public static AuditExportRequest create(
            OrganizationId organizationId,
            TeamId teamId,
            AuditQueryFilter filter,
            int maximumRows) {
        return new AuditExportRequest(
                AuditCursorScope.of(organizationId, teamId, filter), filter, maximumRows);
    }
}
