package io.crewscope.domain.organization.governance;

import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/**
 * One organization's controlled data export (S01 freeze 3.4): the job pins the requested scope at
 * creation, processes batches in their own short transactions, and freezes the reconciliation
 * manifest (families x counts x checksums x skipped reasons) exactly when every item reaches a
 * terminal state. There is no FAILED status — an unexpected error leaves items PENDING and the
 * next process call resumes at the first one, mirroring the handover job re-entry contract.
 */
public final class DataExportJob {

    private final DataExportJobId id;
    private final OrganizationId organizationId;
    private final PrincipalId requestedByPrincipalId;
    private final String commandId;
    private final boolean includeVectors;
    private final DataExportJobStatus status;
    private final Optional<String> manifest;
    private final long version;
    private final AuditMetadata audit;

    private DataExportJob(
            DataExportJobId id,
            OrganizationId organizationId,
            PrincipalId requestedByPrincipalId,
            String commandId,
            boolean includeVectors,
            DataExportJobStatus status,
            Optional<String> manifest,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.requestedByPrincipalId =
                Objects.requireNonNull(requestedByPrincipalId, "requestedByPrincipalId");
        this.commandId = requireCommandId(commandId);
        this.includeVectors = includeVectors;
        this.status = Objects.requireNonNull(status, "status");
        this.manifest = requireManifest(status, manifest);
        this.version = requireVersion(version);
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** Queues a new export whose batches are pinned by the caller from live row counts. */
    public static DataExportJob create(
            DataExportJobId id,
            OrganizationId organizationId,
            PrincipalId requestedByPrincipalId,
            String commandId,
            boolean includeVectors,
            UtcTimestamp occurredAt) {
        Objects.requireNonNull(occurredAt, "occurredAt");
        return new DataExportJob(
                id,
                organizationId,
                requestedByPrincipalId,
                commandId,
                includeVectors,
                DataExportJobStatus.PENDING,
                Optional.empty(),
                0,
                AuditMetadata.createdBy(requestedByPrincipalId, occurredAt));
    }

    /** Reconstitutes a committed job without replaying lifecycle behavior. */
    public static DataExportJob reconstitute(
            DataExportJobId id,
            OrganizationId organizationId,
            PrincipalId requestedByPrincipalId,
            String commandId,
            boolean includeVectors,
            DataExportJobStatus status,
            Optional<String> manifest,
            long version,
            AuditMetadata audit) {
        return new DataExportJob(
                id,
                organizationId,
                requestedByPrincipalId,
                commandId,
                includeVectors,
                status,
                manifest,
                version,
                audit);
    }

    /** Claims a PENDING job for processing; an interrupted RUNNING job may also be reclaimed. */
    public DataExportJob markRunning(PrincipalId actor, UtcTimestamp occurredAt) {
        if (status != DataExportJobStatus.PENDING && status != DataExportJobStatus.RUNNING) {
            throw new InvalidStateTransitionException(
                    "DataExportJob", id, status, DataExportJobStatus.RUNNING);
        }
        return transitioned(DataExportJobStatus.RUNNING, manifest, actor, occurredAt);
    }

    /**
     * Seals the job after every item reached a terminal state and freezes the manifest; the
     * manifest travels with the job row as the reconciliation surface for GET /data-exports/{id}.
     */
    public DataExportJob markCompleted(String frozenManifest, PrincipalId actor, UtcTimestamp occurredAt) {
        String requiredManifest = Objects.requireNonNull(frozenManifest, "frozenManifest");
        if (requiredManifest.isBlank()) {
            throw new DomainValidationException("dataExportJob.manifest", "must not be blank");
        }
        if (status != DataExportJobStatus.PENDING && status != DataExportJobStatus.RUNNING) {
            throw new InvalidStateTransitionException(
                    "DataExportJob", id, status, DataExportJobStatus.COMPLETED);
        }
        return transitioned(
                DataExportJobStatus.COMPLETED, Optional.of(requiredManifest), actor, occurredAt);
    }

    /** Stops a job that still has unprocessed items; already DONE artifacts stay for audit. */
    public DataExportJob markCancelled(PrincipalId actor, UtcTimestamp occurredAt) {
        if (status != DataExportJobStatus.PENDING && status != DataExportJobStatus.RUNNING) {
            throw new InvalidStateTransitionException(
                    "DataExportJob", id, status, DataExportJobStatus.CANCELLED);
        }
        return transitioned(DataExportJobStatus.CANCELLED, manifest, actor, occurredAt);
    }

    public boolean isTerminal() {
        return status == DataExportJobStatus.COMPLETED || status == DataExportJobStatus.CANCELLED;
    }

    public DataExportJobId id() {
        return id;
    }

    public OrganizationId organizationId() {
        return organizationId;
    }

    public PrincipalId requestedByPrincipalId() {
        return requestedByPrincipalId;
    }

    public String commandId() {
        return commandId;
    }

    public boolean includeVectors() {
        return includeVectors;
    }

    public DataExportJobStatus status() {
        return status;
    }

    public Optional<String> manifest() {
        return manifest;
    }

    public long version() {
        return version;
    }

    public AuditMetadata audit() {
        return audit;
    }

    private DataExportJob transitioned(
            DataExportJobStatus target,
            Optional<String> targetManifest,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        return new DataExportJob(
                id,
                organizationId,
                requestedByPrincipalId,
                commandId,
                includeVectors,
                target,
                targetManifest,
                version + 1,
                audit.modifiedBy(actor, occurredAt));
    }

    private static String requireCommandId(String commandId) {
        String required = Objects.requireNonNull(commandId, "commandId");
        if (required.isBlank()) {
            throw new DomainValidationException("dataExportJob.commandId", "must not be blank");
        }
        return required;
    }

    private static Optional<String> requireManifest(
            DataExportJobStatus status, Optional<String> manifest) {
        Optional<String> required = Objects.requireNonNull(manifest, "manifest");
        // Exactly COMPLETED carries the frozen manifest; nothing else may stage one.
        if ((status == DataExportJobStatus.COMPLETED) != required.isPresent()) {
            throw new DomainValidationException(
                    "dataExportJob.manifest",
                    status == DataExportJobStatus.COMPLETED
                            ? "is required for a completed job"
                            : "is only allowed for a completed job");
        }
        return required;
    }

    private static long requireVersion(long value) {
        if (value < 0) {
            throw new DomainValidationException("dataExportJob.version", "must not be negative");
        }
        return value;
    }
}
