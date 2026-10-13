package io.crewscope.infrastructure.persistence.organization;

import io.crewscope.application.organization.governance.DataExportArtifact;
import io.crewscope.application.organization.governance.OrganizationDataExportJobRepository;
import io.crewscope.domain.organization.governance.DataExportItem;
import io.crewscope.domain.organization.governance.DataExportItemId;
import io.crewscope.domain.organization.governance.DataExportItemState;
import io.crewscope.domain.organization.governance.DataExportJob;
import io.crewscope.domain.organization.governance.DataExportJobId;
import io.crewscope.domain.organization.governance.DataExportJobStatus;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC adapter for the V69 export-job tables. Job updates commit under the previous version as
 * the optimistic lock predicate; item updates commit per item in their own short transaction, so
 * an interrupted process run leaves exactly the unsettled items PENDING. Artifact content is
 * stored as TEXT on purpose — the checksum pins the exact newline-framed bytes.
 */
@Repository
public class JdbcOrganizationDataExportJobRepositoryAdapter
        implements OrganizationDataExportJobRepository {

    private static final String JOB_COLUMNS =
            "id, organization_id, requested_by_principal_id, command_id, status, include_vectors,"
                    + " manifest, version, created_at, created_by_principal_id,"
                    + " updated_at, updated_by_principal_id";

    private static final String ITEM_COLUMNS =
            "id, organization_id, job_id, resource_family, batch_sequence, sort_cursor, row_count,"
                    + " checksum, state, error_code, processed_at, version, created_at,"
                    + " created_by_principal_id, updated_at, updated_by_principal_id";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcOrganizationDataExportJobRepositoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional
    public DataExportJob createJob(DataExportJob job, List<DataExportItem> items) {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(items, "items");
        jdbc.update(
                "INSERT INTO crewscope.organization_data_export_job ("
                        + "id, organization_id, requested_by_principal_id, command_id, status,"
                        + " include_vectors, manifest, version, created_at, created_by_principal_id,"
                        + " updated_at, updated_by_principal_id)"
                        + " VALUES (:id, :organizationId, :requestedBy, :commandId, :status,"
                        + " :includeVectors, CAST(:manifest AS JSONB), :version, :createdAt,"
                        + " :createdBy, :updatedAt, :updatedBy)",
                jobParameters(job));
        for (DataExportItem item : items) {
            jdbc.update(
                    "INSERT INTO crewscope.organization_data_export_item ("
                            + "id, organization_id, job_id, resource_family, batch_sequence,"
                            + " sort_cursor, row_count, checksum, state, error_code, processed_at,"
                            + " version, created_at, created_by_principal_id, updated_at,"
                            + " updated_by_principal_id)"
                            + " VALUES (:id, :organizationId, :jobId, :resourceFamily,"
                            + " :batchSequence, :sortCursor, :rowCount, :checksum, :state,"
                            + " :errorCode, :processedAt, :version, :createdAt, :createdBy,"
                            + " :updatedAt, :updatedBy)",
                    itemParameters(item));
        }
        return job;
    }

    @Override
    @Transactional
    public Optional<DataExportJob> lockJobById(OrganizationId organizationId, DataExportJobId jobId) {
        return queryJob(organizationId, jobId, " FOR UPDATE");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DataExportJob> findJobById(OrganizationId organizationId, DataExportJobId jobId) {
        return queryJob(organizationId, jobId, "");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DataExportJob> findJobByCommandId(
            OrganizationId organizationId, String commandId) {
        List<DataExportJob> matches = jdbc.query(
                "SELECT " + JOB_COLUMNS + " FROM crewscope.organization_data_export_job"
                        + " WHERE organization_id = :organizationId AND command_id = :commandId",
                new MapSqlParameterSource()
                        .addValue("organizationId", organizationId.value())
                        .addValue("commandId", commandId),
                JdbcOrganizationDataExportJobRepositoryAdapter::mapJob);
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }

    @Override
    @Transactional
    public DataExportJob updateJob(DataExportJob job) {
        Objects.requireNonNull(job, "job");
        // The previous version is the lock predicate — one writer per committed state.
        int updated = jdbc.update(
                "UPDATE crewscope.organization_data_export_job SET"
                        + " status = :status, manifest = CAST(:manifest AS JSONB),"
                        + " version = :version, updated_at = :updatedAt,"
                        + " updated_by_principal_id = :updatedBy"
                        + " WHERE id = :id AND organization_id = :organizationId"
                        + " AND version = :version - 1",
                jobParameters(job));
        if (updated == 0) {
            throw new OptimisticLockConflictException(
                    "ORGANIZATION_DATA_EXPORT_JOB",
                    job.id().value().toString(),
                    job.version(),
                    job.version() - 1);
        }
        return job;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DataExportItem> findItems(OrganizationId organizationId, DataExportJobId jobId) {
        return jdbc.query(
                "SELECT " + ITEM_COLUMNS + " FROM crewscope.organization_data_export_item"
                        + " WHERE organization_id = :organizationId AND job_id = :jobId"
                        + " ORDER BY resource_family, batch_sequence",
                new MapSqlParameterSource()
                        .addValue("organizationId", organizationId.value())
                        .addValue("jobId", jobId.value()),
                JdbcOrganizationDataExportJobRepositoryAdapter::mapItem);
    }

    @Override
    @Transactional
    public Optional<DataExportItem> lockItemById(OrganizationId organizationId, DataExportItemId itemId) {
        List<DataExportItem> matches = jdbc.query(
                "SELECT " + ITEM_COLUMNS + " FROM crewscope.organization_data_export_item"
                        + " WHERE organization_id = :organizationId AND id = :itemId FOR UPDATE",
                new MapSqlParameterSource()
                        .addValue("organizationId", organizationId.value())
                        .addValue("itemId", itemId.value()),
                JdbcOrganizationDataExportJobRepositoryAdapter::mapItem);
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }

    @Override
    @Transactional
    public DataExportItem updateItem(DataExportItem item) {
        Objects.requireNonNull(item, "item");
        int updated = jdbc.update(
                "UPDATE crewscope.organization_data_export_item SET"
                        + " checksum = :checksum, state = :state, error_code = :errorCode,"
                        + " processed_at = :processedAt, version = :version,"
                        + " updated_at = :updatedAt, updated_by_principal_id = :updatedBy"
                        + " WHERE id = :id AND organization_id = :organizationId"
                        + " AND version = :version - 1",
                itemParameters(item));
        if (updated == 0) {
            throw new OptimisticLockConflictException(
                    "ORGANIZATION_DATA_EXPORT_ITEM",
                    item.id().value().toString(),
                    item.version(),
                    item.version() - 1);
        }
        return item;
    }

    @Override
    @Transactional
    public DataExportArtifact createArtifact(DataExportArtifact artifact) {
        Objects.requireNonNull(artifact, "artifact");
        jdbc.update(
                "INSERT INTO crewscope.organization_data_export_artifact ("
                        + "id, organization_id, job_id, item_id, artifact_sequence, content,"
                        + " byte_size, checksum, created_at, created_by_principal_id)"
                        + " VALUES (:id, :organizationId, :jobId, :itemId, :artifactSequence,"
                        + " :content, :byteSize, :checksum, :createdAt, :createdBy)",
                new MapSqlParameterSource()
                        .addValue("id", artifact.id())
                        .addValue("organizationId", artifact.organizationId().value())
                        .addValue("jobId", artifact.jobId().value())
                        .addValue("itemId", artifact.itemId().value())
                        .addValue("artifactSequence", artifact.artifactSequence())
                        .addValue("content", artifact.content())
                        .addValue("byteSize", artifact.byteSize())
                        .addValue("checksum", artifact.checksum())
                        .addValue("createdAt", artifact.createdAt().toOffsetDateTime())
                        .addValue("createdBy", artifact.createdByPrincipalId().value()));
        return artifact;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DataExportArtifact> findArtifacts(OrganizationId organizationId, DataExportJobId jobId) {
        return jdbc.query(
                "SELECT id, organization_id, job_id, item_id, artifact_sequence, content,"
                        + " byte_size, checksum, created_at, created_by_principal_id"
                        + " FROM crewscope.organization_data_export_artifact"
                        + " WHERE organization_id = :organizationId AND job_id = :jobId"
                        + " ORDER BY item_id, artifact_sequence",
                new MapSqlParameterSource()
                        .addValue("organizationId", organizationId.value())
                        .addValue("jobId", jobId.value()),
                (rs, rowNum) -> new DataExportArtifact(
                        rs.getObject("id", UUID.class),
                        new OrganizationId(rs.getObject("organization_id", UUID.class)),
                        new DataExportJobId(rs.getObject("job_id", UUID.class)),
                        new DataExportItemId(rs.getObject("item_id", UUID.class)),
                        rs.getInt("artifact_sequence"),
                        rs.getString("content"),
                        rs.getInt("byte_size"),
                        rs.getString("checksum"),
                        new PrincipalId(rs.getObject("created_by_principal_id", UUID.class)),
                        UtcTimestamp.from(rs.getObject("created_at", java.time.OffsetDateTime.class))));
    }

    private Optional<DataExportJob> queryJob(
            OrganizationId organizationId, DataExportJobId jobId, String lockClause) {
        List<DataExportJob> matches = jdbc.query(
                "SELECT " + JOB_COLUMNS + " FROM crewscope.organization_data_export_job"
                        + " WHERE organization_id = :organizationId AND id = :jobId" + lockClause,
                new MapSqlParameterSource()
                        .addValue("organizationId", organizationId.value())
                        .addValue("jobId", jobId.value()),
                JdbcOrganizationDataExportJobRepositoryAdapter::mapJob);
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }

    private static MapSqlParameterSource jobParameters(DataExportJob job) {
        return new MapSqlParameterSource()
                .addValue("id", job.id().value())
                .addValue("organizationId", job.organizationId().value())
                .addValue("requestedBy", job.requestedByPrincipalId().value())
                .addValue("commandId", job.commandId())
                .addValue("status", job.status().name())
                .addValue("includeVectors", job.includeVectors())
                .addValue("manifest", job.manifest().orElse(null))
                .addValue("version", job.version())
                .addValue("createdAt", job.audit().createdAt().toOffsetDateTime())
                .addValue("createdBy", job.audit().createdBy().map(PrincipalId::value).orElse(null))
                .addValue("updatedAt", job.audit().updatedAt().toOffsetDateTime())
                .addValue("updatedBy", job.audit().updatedBy().map(PrincipalId::value).orElse(null));
    }

    private static MapSqlParameterSource itemParameters(DataExportItem item) {
        return new MapSqlParameterSource()
                .addValue("id", item.id().value())
                .addValue("organizationId", item.organizationId().value())
                .addValue("jobId", item.jobId().value())
                .addValue("resourceFamily", item.resourceFamily())
                .addValue("batchSequence", item.batchSequence())
                .addValue("sortCursor", item.sortCursor().orElse(null))
                .addValue("rowCount", item.rowCount())
                .addValue("checksum", item.checksum().orElse(null))
                .addValue("state", item.state().name())
                .addValue("errorCode", item.errorCode().orElse(null))
                .addValue("processedAt", item.processedAt().map(UtcTimestamp::toOffsetDateTime).orElse(null))
                .addValue("version", item.version())
                .addValue("createdAt", item.audit().createdAt().toOffsetDateTime())
                .addValue("createdBy", item.audit().createdBy().map(PrincipalId::value).orElse(null))
                .addValue("updatedAt", item.audit().updatedAt().toOffsetDateTime())
                .addValue("updatedBy", item.audit().updatedBy().map(PrincipalId::value).orElse(null));
    }

    private static DataExportJob mapJob(ResultSet rs, int rowNum) throws SQLException {
        return DataExportJob.reconstitute(
                new DataExportJobId(rs.getObject("id", UUID.class)),
                new OrganizationId(rs.getObject("organization_id", UUID.class)),
                new PrincipalId(rs.getObject("requested_by_principal_id", UUID.class)),
                rs.getString("command_id"),
                rs.getBoolean("include_vectors"),
                DataExportJobStatus.valueOf(rs.getString("status")),
                Optional.ofNullable(rs.getString("manifest")),
                rs.getLong("version"),
                new AuditMetadata(
                        Optional.of(new PrincipalId(
                                rs.getObject("created_by_principal_id", UUID.class))),
                        UtcTimestamp.from(rs.getObject("created_at", java.time.OffsetDateTime.class)),
                        Optional.of(new PrincipalId(
                                rs.getObject("updated_by_principal_id", UUID.class))),
                        UtcTimestamp.from(rs.getObject("updated_at", java.time.OffsetDateTime.class))));
    }

    private static DataExportItem mapItem(ResultSet rs, int rowNum) throws SQLException {
        return DataExportItem.reconstitute(
                new DataExportItemId(rs.getObject("id", UUID.class)),
                new OrganizationId(rs.getObject("organization_id", UUID.class)),
                new DataExportJobId(rs.getObject("job_id", UUID.class)),
                rs.getString("resource_family"),
                rs.getInt("batch_sequence"),
                Optional.ofNullable(rs.getString("sort_cursor")),
                rs.getInt("row_count"),
                DataExportItemState.valueOf(rs.getString("state")),
                Optional.ofNullable(rs.getString("checksum")),
                Optional.ofNullable(rs.getString("error_code")),
                Optional.ofNullable(
                        rs.getObject("processed_at", java.time.OffsetDateTime.class))
                        .map(UtcTimestamp::from),
                rs.getLong("version"),
                new AuditMetadata(
                        Optional.of(new PrincipalId(
                                rs.getObject("created_by_principal_id", UUID.class))),
                        UtcTimestamp.from(rs.getObject("created_at", java.time.OffsetDateTime.class)),
                        Optional.of(new PrincipalId(
                                rs.getObject("updated_by_principal_id", UUID.class))),
                        UtcTimestamp.from(rs.getObject("updated_at", java.time.OffsetDateTime.class))));
    }
}
