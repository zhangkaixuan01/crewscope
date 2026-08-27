package io.crewscope.application.audit;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.audit.AuditOutcome;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.UUID;

/** Authorized application boundary for Audit Explorer pages and bounded governance exports. */
public final class AuditQueryApplicationService {

    private final AuditQueryPort queries;
    private final AuditAuthorization authorization;
    private final AuditAccessRecorder accessRecorder;
    private final TimeProvider timeProvider;

    public AuditQueryApplicationService(
            AuditQueryPort queries,
            AuditAuthorization authorization,
            AuditAccessRecorder accessRecorder,
            TimeProvider timeProvider) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.accessRecorder = Objects.requireNonNull(accessRecorder, "accessRecorder");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    public AuditPage query(TeamAccessContext context, AuditQuery query) {
        return query(context, UUID.randomUUID(), query);
    }

    /** Executes and audits one Explorer request using the transport correlation identity. */
    public AuditPage query(
            TeamAccessContext context, UUID correlationId, AuditQuery query) {
        AuditQuery required = Objects.requireNonNull(query, "query");
        UtcTimestamp now = timeProvider.now();
        AuditPage result;
        try {
            authorization.requireRead(
                    context,
                    required.cursorScope().organizationId(),
                    required.cursorScope().teamId(),
                    now);
            result = Objects.requireNonNull(
                    queries.find(required), "AuditQueryPort.find result");
            if (!result.query().equals(required)) {
                throw new IllegalStateException("Audit query adapter returned another request scope");
            }
        } catch (RuntimeException failure) {
            recordFailure(
                    context,
                    correlationId,
                    AuditAccessRecord.Operation.QUERY,
                    required.cursorScope().organizationId(),
                    required.cursorScope().teamId(),
                    failure,
                    now);
            throw failure;
        }
        record(
                context,
                correlationId,
                AuditAccessRecord.Operation.QUERY,
                required.cursorScope().organizationId(),
                required.cursorScope().teamId(),
                AuditOutcome.SUCCEEDED,
                result.events().size(),
                now);
        return result;
    }

    public AuditExportBatch export(TeamAccessContext context, AuditExportRequest request) {
        return export(context, UUID.randomUUID(), request);
    }

    /** Generates and audits one bounded governance export. */
    public AuditExportBatch export(
            TeamAccessContext context, UUID correlationId, AuditExportRequest request) {
        AuditExportRequest required = Objects.requireNonNull(request, "request");
        UtcTimestamp now = timeProvider.now();
        AuditExportBatch result;
        try {
            authorization.requireExport(
                    context,
                    required.scope().organizationId(),
                    required.scope().teamId(),
                    now);
            result = Objects.requireNonNull(
                    queries.export(required), "AuditQueryPort.export result");
            if (!result.request().equals(required)) {
                throw new IllegalStateException("Audit export adapter returned another request scope");
            }
        } catch (RuntimeException failure) {
            recordFailure(
                    context,
                    correlationId,
                    AuditAccessRecord.Operation.EXPORT,
                    required.scope().organizationId(),
                    required.scope().teamId(),
                    failure,
                    now);
            throw failure;
        }
        record(
                context,
                correlationId,
                AuditAccessRecord.Operation.EXPORT,
                required.scope().organizationId(),
                required.scope().teamId(),
                AuditOutcome.SUCCEEDED,
                result.events().size(),
                now);
        return result;
    }

    /** Performs the pre-decode check used to avoid turning a signed Cursor into an oracle. */
    public void requireRead(
            TeamAccessContext context,
            UUID correlationId,
            OrganizationId organizationId,
            TeamId teamId) {
        UtcTimestamp now = timeProvider.now();
        try {
            authorization.requireRead(context, organizationId, teamId, now);
        } catch (RuntimeException failure) {
            // A denied pre-decode check is still an Audit Explorer access attempt.
            recordFailure(
                    context,
                    correlationId,
                    AuditAccessRecord.Operation.QUERY,
                    organizationId,
                    teamId,
                    failure,
                    now);
            throw failure;
        }
    }

    private void recordFailure(
            TeamAccessContext context,
            UUID correlationId,
            AuditAccessRecord.Operation operation,
            OrganizationId organizationId,
            TeamId teamId,
            RuntimeException failure,
            UtcTimestamp occurredAt) {
        AuditOutcome outcome = failure instanceof PolicyDeniedException
                ? AuditOutcome.DENIED
                : AuditOutcome.FAILED;
        try {
            record(
                    context,
                    correlationId,
                    operation,
                    organizationId,
                    teamId,
                    outcome,
                    0,
                    occurredAt);
        } catch (RuntimeException recordingFailure) {
            // Preserve the primary authorization or query error while retaining logging evidence.
            failure.addSuppressed(recordingFailure);
        }
    }

    private void record(
            TeamAccessContext context,
            UUID correlationId,
            AuditAccessRecord.Operation operation,
            OrganizationId organizationId,
            TeamId teamId,
            AuditOutcome outcome,
            int rowCount,
            UtcTimestamp occurredAt) {
        accessRecorder.record(new AuditAccessRecord(
                operation,
                organizationId,
                teamId,
                Objects.requireNonNull(context, "context").actor(),
                correlationId,
                outcome,
                rowCount,
                occurredAt));
    }
}
