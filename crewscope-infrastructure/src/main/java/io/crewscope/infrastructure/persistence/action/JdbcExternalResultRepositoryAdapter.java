package io.crewscope.infrastructure.persistence.action;

import io.crewscope.application.action.ExternalObservationRepository;
import io.crewscope.application.action.ExternalResultRepository;
import io.crewscope.domain.action.ActionBundleDigest;
import io.crewscope.domain.action.ActionBundleId;
import io.crewscope.domain.action.ActionDigest;
import io.crewscope.domain.action.ActionEvidenceReference;
import io.crewscope.domain.action.ExternalObjectStatus;
import io.crewscope.domain.action.ExternalObjectType;
import io.crewscope.domain.action.ExternalObservation;
import io.crewscope.domain.action.ExternalObservationKey;
import io.crewscope.domain.action.ExternalResult;
import io.crewscope.domain.action.ExternalResultId;
import io.crewscope.domain.action.ExternalResultIdentity;
import io.crewscope.domain.action.ExternalResultSource;
import io.crewscope.domain.action.PlannedActionId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL append-only Observation history and optimistic monotonic ExternalResult projection. */
@Repository
public class JdbcExternalResultRepositoryAdapter
        implements ExternalObservationRepository, ExternalResultRepository {

    private static final String RESULT_SELECT = """
            SELECT result.*, bundle.bundle_digest
            FROM crewscope.external_result result
            JOIN crewscope.action_bundle bundle ON bundle.id = result.action_bundle_id
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public JdbcExternalResultRepositoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    @Transactional
    public boolean appendIfAbsent(
            OrganizationId organizationId, ExternalObservation observation) {
        OrganizationId organization = Objects.requireNonNull(organizationId, "organizationId");
        ExternalObservation value = Objects.requireNonNull(observation, "observation");
        MapSqlParameterSource p = observationParameters(value)
                .addValue("id", UUID.randomUUID())
                .addValue("organizationId", organization.value());
        int inserted = named.update(
                """
                INSERT INTO crewscope.external_observation (
                    id, organization_id, team_id, workspace_id, project_id,
                    action_bundle_id, action_id, action_digest, observation_key,
                    connection_id, connection_version, external_object_type, external_id,
                    external_business_key, external_status, provider_version,
                    provider_updated_at, source, evidence_code, evidence_hash,
                    evidence_artifact_id, observed_at, created_at, created_by_principal_id
                )
                SELECT
                    :id, dispatch.organization_id, dispatch.team_id,
                    dispatch.workspace_id, dispatch.project_id,
                    dispatch.action_bundle_id, dispatch.action_id, dispatch.action_digest,
                    :observationKey, :connectionId, bundle.connection_version,
                    :objectType, :externalId,
                    :businessKey, :status, :providerVersion, :providerUpdatedAt,
                    :source, :evidenceCode, :evidenceHash, :evidenceArtifact,
                    :observedAt, :observedAt, dispatch.updated_by_principal_id
                FROM crewscope.action_dispatch dispatch
                JOIN crewscope.action_bundle bundle ON bundle.id = dispatch.action_bundle_id
                WHERE dispatch.organization_id = :organizationId
                  AND dispatch.action_id = :actionId
                  AND dispatch.action_digest = :actionDigest
                ON CONFLICT DO NOTHING
                """,
                p);
        if (inserted == 0 && !exists(organization, value.observationKey())) {
            throw new IllegalStateException(
                    "External Observation could not be bound to the exact Action");
        }
        return inserted == 1;
    }

    @Override
    public boolean exists(
            OrganizationId organizationId, ExternalObservationKey observationKey) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM crewscope.external_observation
                WHERE organization_id = ? AND observation_key = ?
                """,
                Integer.class,
                Objects.requireNonNull(organizationId, "organizationId").value(),
                Objects.requireNonNull(observationKey, "observationKey").value().value());
        return count != null && count == 1;
    }

    @Override
    public List<ExternalObservation> findObservationsByAction(
            OrganizationId organizationId, PlannedActionId actionId) {
        return jdbc.query(
                """
                SELECT * FROM crewscope.external_observation
                WHERE organization_id = ? AND action_id = ?
                ORDER BY observed_at, id
                """,
                this::observation,
                Objects.requireNonNull(organizationId, "organizationId").value(),
                Objects.requireNonNull(actionId, "actionId").value());
    }

    @Override
    @Transactional
    public ExternalResult insert(ExternalResult result) {
        ExternalResult value = Objects.requireNonNull(result, "result");
        int inserted = named.update(
                """
                INSERT INTO crewscope.external_result (
                    id, organization_id, team_id, workspace_id, project_id,
                    action_bundle_id, action_id, action_digest,
                    connection_id, external_object_type, external_id,
                    external_business_key, external_status, provider_version,
                    provider_updated_at, last_source, last_observation_key,
                    last_evidence_code, last_evidence_hash, last_evidence_artifact_id,
                    observed_at, version, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id
                ) VALUES (
                    :id, :organizationId, :teamId, :workspaceId, :projectId,
                    :bundleId, :actionId, :actionDigest,
                    :connectionId, :objectType, :externalId,
                    :businessKey, :status, :providerVersion,
                    :providerUpdatedAt, :lastSource, :observationKey,
                    :evidenceCode, :evidenceHash, :evidenceArtifact,
                    :observedAt, :version, :createdAt, :createdBy,
                    :updatedAt, :updatedBy
                )
                ON CONFLICT DO NOTHING
                """,
                resultParameters(value));
        if (inserted == 1) {
            return findById(value.scope().organizationId(), value.id()).orElseThrow();
        }
        return findByAction(value.scope().organizationId(), value.actionId())
                .orElseThrow(() -> new IllegalStateException(
                        "External Result uniqueness is owned by another external object"));
    }

    @Override
    @Transactional
    public ExternalResult update(ExternalResult result) {
        ExternalResult value = Objects.requireNonNull(result, "result");
        int changed = named.update(
                """
                UPDATE crewscope.external_result SET
                    external_status = :status,
                    provider_version = :providerVersion,
                    provider_updated_at = :providerUpdatedAt,
                    last_source = :lastSource,
                    last_observation_key = :observationKey,
                    last_evidence_code = :evidenceCode,
                    last_evidence_hash = :evidenceHash,
                    last_evidence_artifact_id = :evidenceArtifact,
                    observed_at = :observedAt,
                    version = :version,
                    updated_at = :updatedAt,
                    updated_by_principal_id = :updatedBy
                WHERE organization_id = :organizationId AND id = :id
                  AND version = :expectedVersion
                """,
                resultParameters(value).addValue("expectedVersion", value.version() - 1));
        if (changed != 1) {
            throw new OptimisticLockConflictException(
                    "ExternalResult", value.id(), value.version() - 1, value.version());
        }
        return findById(value.scope().organizationId(), value.id()).orElseThrow();
    }

    @Override
    public Optional<ExternalResult> findById(
            OrganizationId organizationId, ExternalResultId id) {
        return one(jdbc.query(
                RESULT_SELECT + " WHERE result.organization_id = ? AND result.id = ?",
                this::result,
                Objects.requireNonNull(organizationId, "organizationId").value(),
                Objects.requireNonNull(id, "id").value()));
    }

    @Override
    public Optional<ExternalResult> findByIdentity(
            OrganizationId organizationId, ExternalResultIdentity identity) {
        ExternalResultIdentity value = Objects.requireNonNull(identity, "identity");
        return one(jdbc.query(
                RESULT_SELECT + """
                 WHERE result.organization_id = ? AND result.connection_id = ?
                   AND result.external_object_type = ?
                   AND (result.external_id = ? OR result.external_business_key = ?)
                """,
                this::result,
                Objects.requireNonNull(organizationId, "organizationId").value(),
                value.connectionId().value(), value.objectType().name(),
                value.externalId(), value.businessKey()));
    }

    @Override
    public Optional<ExternalResult> findByAction(
            OrganizationId organizationId, PlannedActionId actionId) {
        return one(jdbc.query(
                RESULT_SELECT + " WHERE result.organization_id = ? AND result.action_id = ?",
                this::result,
                Objects.requireNonNull(organizationId, "organizationId").value(),
                Objects.requireNonNull(actionId, "actionId").value()));
    }

    private ExternalObservation observation(ResultSet row, int ignored) throws SQLException {
        return new ExternalObservation(
                new ExternalObservationKey(hash(row, "observation_key")),
                new PlannedActionId(uuid(row, "action_id")),
                new ActionDigest(hash(row, "action_digest")),
                identity(row),
                ExternalObjectStatus.valueOf(row.getString("external_status")),
                optionalLong(row, "provider_version"),
                optionalTimestamp(row, "provider_updated_at"),
                ExternalResultSource.valueOf(row.getString("source")),
                evidence(row, "evidence_code", "evidence_hash", "evidence_artifact_id"),
                timestamp(row, "observed_at"));
    }

    private ExternalResult result(ResultSet row, int ignored) throws SQLException {
        return ExternalResult.reconstitute(
                new ExternalResultId(uuid(row, "id")),
                scope(row),
                new ActionBundleId(uuid(row, "action_bundle_id")),
                new ActionBundleDigest(hash(row, "bundle_digest")),
                new PlannedActionId(uuid(row, "action_id")),
                new ActionDigest(hash(row, "action_digest")),
                identity(row),
                ExternalObjectStatus.valueOf(row.getString("external_status")),
                optionalLong(row, "provider_version"),
                optionalTimestamp(row, "provider_updated_at"),
                ExternalResultSource.valueOf(row.getString("last_source")),
                new ExternalObservationKey(hash(row, "last_observation_key")),
                evidence(
                        row,
                        "last_evidence_code",
                        "last_evidence_hash",
                        "last_evidence_artifact_id"),
                timestamp(row, "observed_at"),
                row.getLong("version"),
                audit(row));
    }

    private static MapSqlParameterSource observationParameters(ExternalObservation value) {
        return new MapSqlParameterSource()
                .addValue("actionId", value.actionId().value())
                .addValue("actionDigest", value.actionDigest().toString())
                .addValue("observationKey", value.observationKey().value().value())
                .addValue("connectionId", value.identity().connectionId().value())
                .addValue("objectType", value.identity().objectType().name())
                .addValue("externalId", value.identity().externalId())
                .addValue("businessKey", value.identity().businessKey())
                .addValue("status", value.status().name())
                .addValue("providerVersion", value.providerVersion().orElse(null))
                .addValue("providerUpdatedAt", value.providerUpdatedAt()
                        .map(UtcTimestamp::toOffsetDateTime).orElse(null))
                .addValue("source", value.source().name())
                .addValue("evidenceCode", value.evidence().code())
                .addValue("evidenceHash", value.evidence().evidenceHash().value())
                .addValue("evidenceArtifact", value.evidence().artifactId()
                        .map(ArtifactId::value).orElse(null))
                .addValue("observedAt", value.observedAt().toOffsetDateTime());
    }

    private static MapSqlParameterSource resultParameters(ExternalResult value) {
        return scope(new MapSqlParameterSource(), value.scope())
                .addValue("id", value.id().value())
                .addValue("bundleId", value.bundleId().value())
                .addValue("actionId", value.actionId().value())
                .addValue("actionDigest", value.actionDigest().toString())
                .addValue("connectionId", value.identity().connectionId().value())
                .addValue("objectType", value.identity().objectType().name())
                .addValue("externalId", value.identity().externalId())
                .addValue("businessKey", value.identity().businessKey())
                .addValue("status", value.status().name())
                .addValue("providerVersion", value.providerVersion().orElse(null))
                .addValue("providerUpdatedAt", value.providerUpdatedAt()
                        .map(UtcTimestamp::toOffsetDateTime).orElse(null))
                .addValue("lastSource", value.lastSource().name())
                .addValue("observationKey", value.lastObservationKey().value().value())
                .addValue("evidenceCode", value.lastEvidence().code())
                .addValue("evidenceHash", value.lastEvidence().evidenceHash().value())
                .addValue("evidenceArtifact", value.lastEvidence().artifactId()
                        .map(ArtifactId::value).orElse(null))
                .addValue("observedAt", value.observedAt().toOffsetDateTime())
                .addValue("version", value.version())
                .addValue("createdAt", value.audit().createdAt().toOffsetDateTime())
                .addValue("createdBy", value.audit().createdBy().orElseThrow().value())
                .addValue("updatedAt", value.audit().updatedAt().toOffsetDateTime())
                .addValue("updatedBy", value.audit().updatedBy().orElseThrow().value());
    }

    private static ExternalResultIdentity identity(ResultSet row) throws SQLException {
        return new ExternalResultIdentity(
                new ConnectionId(uuid(row, "connection_id")),
                ExternalObjectType.valueOf(row.getString("external_object_type")),
                row.getString("external_id"),
                row.getString("external_business_key"));
    }

    private static ActionEvidenceReference evidence(
            ResultSet row, String code, String hash, String artifact) throws SQLException {
        return new ActionEvidenceReference(
                row.getString(code),
                hash(row, hash),
                optionalUuid(row, artifact).map(ArtifactId::new));
    }

    private static WorkItemScope scope(ResultSet row) throws SQLException {
        return new WorkItemScope(
                new OrganizationId(uuid(row, "organization_id")),
                new TeamId(uuid(row, "team_id")),
                new WorkspaceId(uuid(row, "workspace_id")),
                new WorkProjectId(uuid(row, "project_id")));
    }

    private static MapSqlParameterSource scope(
            MapSqlParameterSource values, WorkItemScope scope) {
        return values.addValue("organizationId", scope.organizationId().value())
                .addValue("teamId", scope.teamId().value())
                .addValue("workspaceId", scope.workspaceId().value())
                .addValue("projectId", scope.projectId().value());
    }

    private static AuditMetadata audit(ResultSet row) throws SQLException {
        return new AuditMetadata(
                Optional.of(new PrincipalId(uuid(row, "created_by_principal_id"))),
                timestamp(row, "created_at"),
                Optional.of(new PrincipalId(uuid(row, "updated_by_principal_id"))),
                timestamp(row, "updated_at"));
    }

    private static Optional<Long> optionalLong(ResultSet row, String column) throws SQLException {
        return Optional.ofNullable(row.getObject(column, Long.class));
    }

    private static Optional<UtcTimestamp> optionalTimestamp(
            ResultSet row, String column) throws SQLException {
        return Optional.ofNullable(row.getObject(column, OffsetDateTime.class))
                .map(UtcTimestamp::from);
    }

    private static UtcTimestamp timestamp(ResultSet row, String column) throws SQLException {
        return UtcTimestamp.from(row.getObject(column, OffsetDateTime.class));
    }

    private static TaskFactHash hash(ResultSet row, String column) throws SQLException {
        return new TaskFactHash(row.getString(column));
    }

    private static UUID uuid(ResultSet row, String column) throws SQLException {
        return row.getObject(column, UUID.class);
    }

    private static Optional<UUID> optionalUuid(ResultSet row, String column) throws SQLException {
        return Optional.ofNullable(row.getObject(column, UUID.class));
    }

    private static <T> Optional<T> one(List<T> values) {
        if (values.size() > 1) {
            throw new IllegalStateException("Expected at most one external result row");
        }
        return values.stream().findFirst();
    }
}
