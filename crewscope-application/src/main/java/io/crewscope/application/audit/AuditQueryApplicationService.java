package io.crewscope.application.audit;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Authorized application boundary for Audit Explorer pages and bounded governance exports. */
public final class AuditQueryApplicationService {

    private final AuditQueryPort queries;
    private final AuditAuthorization authorization;
    private final TimeProvider timeProvider;

    public AuditQueryApplicationService(
            AuditQueryPort queries,
            AuditAuthorization authorization,
            TimeProvider timeProvider) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    public AuditPage query(TeamAccessContext context, AuditQuery query) {
        AuditQuery required = Objects.requireNonNull(query, "query");
        UtcTimestamp now = timeProvider.now();
        authorization.requireRead(
                context,
                required.cursorScope().organizationId(),
                required.cursorScope().teamId(),
                now);
        AuditPage result = Objects.requireNonNull(queries.find(required), "AuditQueryPort.find result");
        if (!result.query().equals(required)) {
            throw new IllegalStateException("Audit query adapter returned another request scope");
        }
        return result;
    }

    public AuditExportBatch export(TeamAccessContext context, AuditExportRequest request) {
        AuditExportRequest required = Objects.requireNonNull(request, "request");
        UtcTimestamp now = timeProvider.now();
        authorization.requireExport(
                context,
                required.scope().organizationId(),
                required.scope().teamId(),
                now);
        AuditExportBatch result = Objects.requireNonNull(
                queries.export(required), "AuditQueryPort.export result");
        if (!result.request().equals(required)) {
            throw new IllegalStateException("Audit export adapter returned another request scope");
        }
        return result;
    }
}
