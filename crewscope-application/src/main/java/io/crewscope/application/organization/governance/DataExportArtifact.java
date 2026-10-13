package io.crewscope.application.organization.governance;

import io.crewscope.domain.organization.governance.DataExportItemId;
import io.crewscope.domain.organization.governance.DataExportJobId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.UUID;

/**
 * One immutable JSON export artifact row produced while settling a batch item. Artifacts stay
 * in the database on purpose: the single-node database is the backup surface, so the export
 * travels with pg_dump and no DB/filesystem dual-write consistency face exists.
 */
public record DataExportArtifact(
        UUID id,
        OrganizationId organizationId,
        DataExportJobId jobId,
        DataExportItemId itemId,
        int artifactSequence,
        String content,
        int byteSize,
        String checksum,
        PrincipalId createdByPrincipalId,
        UtcTimestamp createdAt) {

    /** Hard ceiling mirroring the V69 CHECK — one artifact never exceeds 4 MB. */
    public static final int MAXIMUM_BYTES = 4 * 1024 * 1024;

    public DataExportArtifact {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(checksum, "checksum");
        Objects.requireNonNull(createdByPrincipalId, "createdByPrincipalId");
        Objects.requireNonNull(createdAt, "createdAt");
        if (byteSize < 1 || byteSize > MAXIMUM_BYTES) {
            throw new IllegalArgumentException(
                    "Data export artifact size is outside the bounded range");
        }
        if (artifactSequence < 0) {
            throw new IllegalArgumentException("Data export artifact sequence must not be negative");
        }
    }
}
