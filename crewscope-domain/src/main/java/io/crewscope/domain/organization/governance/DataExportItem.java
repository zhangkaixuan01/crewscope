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
 * One keyset-positioned export batch of one resource family, pinned with its start cursor and
 * the live row count observed at job creation. markDone freezes the batch checksum that feeds
 * the job manifest; markSkipped records a data-plane fact (never a job-blocking conflict).
 */
public final class DataExportItem {

    private final DataExportItemId id;
    private final OrganizationId organizationId;
    private final DataExportJobId jobId;
    private final String resourceFamily;
    private final int batchSequence;
    private final Optional<String> sortCursor;
    private final int rowCount;
    private final DataExportItemState state;
    private final Optional<String> checksum;
    private final Optional<String> errorCode;
    private final Optional<UtcTimestamp> processedAt;
    private final long version;
    private final AuditMetadata audit;

    private DataExportItem(
            DataExportItemId id,
            OrganizationId organizationId,
            DataExportJobId jobId,
            String resourceFamily,
            int batchSequence,
            Optional<String> sortCursor,
            int rowCount,
            DataExportItemState state,
            Optional<String> checksum,
            Optional<String> errorCode,
            Optional<UtcTimestamp> processedAt,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.jobId = Objects.requireNonNull(jobId, "jobId");
        this.resourceFamily = requireFamily(resourceFamily);
        this.batchSequence = requireBatchSequence(batchSequence);
        this.sortCursor = Objects.requireNonNull(sortCursor, "sortCursor");
        this.rowCount = requireRowCount(rowCount);
        this.state = Objects.requireNonNull(state, "state");
        this.checksum = requireChecksum(state, checksum);
        this.errorCode = requireErrorCode(state, errorCode);
        this.processedAt = requireProcessedAt(state, processedAt);
        this.version = requireVersion(version);
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** Queues the first (or a continuation) batch of one family at its keyset start cursor. */
    public static DataExportItem create(
            DataExportItemId id,
            DataExportJobId jobId,
            OrganizationId organizationId,
            String resourceFamily,
            int batchSequence,
            Optional<String> sortCursor,
            int rowCount,
            PrincipalId createdByPrincipalId,
            UtcTimestamp occurredAt) {
        return new DataExportItem(
                id,
                organizationId,
                jobId,
                resourceFamily,
                batchSequence,
                sortCursor,
                rowCount,
                DataExportItemState.PENDING,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                AuditMetadata.createdBy(createdByPrincipalId, occurredAt));
    }

    /** Reconstitutes a committed item without replaying lifecycle behavior. */
    public static DataExportItem reconstitute(
            DataExportItemId id,
            OrganizationId organizationId,
            DataExportJobId jobId,
            String resourceFamily,
            int batchSequence,
            Optional<String> sortCursor,
            int rowCount,
            DataExportItemState state,
            Optional<String> checksum,
            Optional<String> errorCode,
            Optional<UtcTimestamp> processedAt,
            long version,
            AuditMetadata audit) {
        return new DataExportItem(
                id,
                organizationId,
                jobId,
                resourceFamily,
                batchSequence,
                sortCursor,
                rowCount,
                state,
                checksum,
                errorCode,
                processedAt,
                version,
                audit);
    }

    /** Freezes the batch checksum that the manifest aggregates per family. */
    public DataExportItem markDone(String checksum, PrincipalId actor, UtcTimestamp occurredAt) {
        String required = Objects.requireNonNull(checksum, "checksum");
        if (required.isBlank()) {
            throw new DomainValidationException("dataExportItem.checksum", "must not be blank");
        }
        return transitioned(
                DataExportItemState.DONE,
                Optional.of(required),
                Optional.empty(),
                actor,
                occurredAt);
    }

    /** Records a data-plane fact (e.g. VECTOR_STORE_DISABLED) reported into the manifest. */
    public DataExportItem markSkipped(String code, PrincipalId actor, UtcTimestamp occurredAt) {
        return transitioned(
                DataExportItemState.SKIPPED,
                Optional.empty(),
                Optional.of(requireCode(code)),
                actor,
                occurredAt);
    }

    public boolean isPending() {
        return state == DataExportItemState.PENDING;
    }

    public DataExportItemId id() {
        return id;
    }

    public OrganizationId organizationId() {
        return organizationId;
    }

    public DataExportJobId jobId() {
        return jobId;
    }

    public String resourceFamily() {
        return resourceFamily;
    }

    public int batchSequence() {
        return batchSequence;
    }

    public Optional<String> sortCursor() {
        return sortCursor;
    }

    public int rowCount() {
        return rowCount;
    }

    public DataExportItemState state() {
        return state;
    }

    public Optional<String> checksum() {
        return checksum;
    }

    public Optional<String> errorCode() {
        return errorCode;
    }

    public Optional<UtcTimestamp> processedAt() {
        return processedAt;
    }

    public long version() {
        return version;
    }

    public AuditMetadata audit() {
        return audit;
    }

    private DataExportItem transitioned(
            DataExportItemState target,
            Optional<String> targetChecksum,
            Optional<String> code,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        if (state != DataExportItemState.PENDING) {
            throw new InvalidStateTransitionException("DataExportItem", id, state, target);
        }
        return new DataExportItem(
                id,
                organizationId,
                jobId,
                resourceFamily,
                batchSequence,
                sortCursor,
                rowCount,
                target,
                targetChecksum,
                code,
                Optional.of(occurredAt),
                version + 1,
                audit.modifiedBy(actor, occurredAt));
    }

    private static String requireFamily(String resourceFamily) {
        String required = Objects.requireNonNull(resourceFamily, "resourceFamily");
        if (required.isBlank()) {
            throw new DomainValidationException(
                    "dataExportItem.resourceFamily", "must not be blank");
        }
        return required;
    }

    private static int requireBatchSequence(int value) {
        if (value < 0) {
            throw new DomainValidationException(
                    "dataExportItem.batchSequence", "must not be negative");
        }
        return value;
    }

    private static int requireRowCount(int value) {
        if (value < 0) {
            throw new DomainValidationException("dataExportItem.rowCount", "must not be negative");
        }
        return value;
    }

    private static String requireCode(String code) {
        String required = Objects.requireNonNull(code, "code");
        if (required.isBlank()) {
            throw new DomainValidationException("dataExportItem.errorCode", "must not be blank");
        }
        return required;
    }

    private static Optional<String> requireChecksum(
            DataExportItemState state, Optional<String> checksum) {
        Optional<String> required = Objects.requireNonNull(checksum, "checksum");
        // Only DONE carries a checksum; SKIPPED never produced data to hash.
        if ((state == DataExportItemState.DONE) != required.isPresent()) {
            throw new DomainValidationException(
                    "dataExportItem.checksum",
                    state == DataExportItemState.DONE
                            ? "is required for a done item"
                            : "is only allowed for a done item");
        }
        return required;
    }

    private static Optional<String> requireErrorCode(
            DataExportItemState state, Optional<String> errorCode) {
        Optional<String> required = Objects.requireNonNull(errorCode, "errorCode");
        // SKIPPED must explain itself; DONE and PENDING carry no code.
        if ((state == DataExportItemState.SKIPPED) != required.isPresent()) {
            throw new DomainValidationException(
                    "dataExportItem.errorCode",
                    state == DataExportItemState.SKIPPED
                            ? "is required for a skipped item"
                            : "is only allowed for a skipped item");
        }
        return required;
    }

    private static Optional<UtcTimestamp> requireProcessedAt(
            DataExportItemState state, Optional<UtcTimestamp> processedAt) {
        Optional<UtcTimestamp> required = Objects.requireNonNull(processedAt, "processedAt");
        if ((state != DataExportItemState.PENDING) != required.isPresent()) {
            throw new DomainValidationException(
                    "dataExportItem.processedAt",
                    state != DataExportItemState.PENDING
                            ? "is required for a processed item"
                            : "is only allowed for a processed item");
        }
        return required;
    }

    private static long requireVersion(long value) {
        if (value < 0) {
            throw new DomainValidationException("dataExportItem.version", "must not be negative");
        }
        return value;
    }
}
