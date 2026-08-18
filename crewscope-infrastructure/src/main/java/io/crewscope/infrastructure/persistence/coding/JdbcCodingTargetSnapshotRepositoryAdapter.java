package io.crewscope.infrastructure.persistence.coding;

import io.crewscope.application.coding.CodingTargetSnapshotRepository;
import io.crewscope.domain.coding.CodingTargetSnapshot;
import io.crewscope.domain.coding.CodingTargetSnapshotId;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JDBC adapter for immutable, revisioned CodingTargetSnapshot facts. */
@Repository
public class JdbcCodingTargetSnapshotRepositoryAdapter
        implements CodingTargetSnapshotRepository {

    private static final String SELECT = "SELECT * FROM crewscope.coding_target_snapshot";

    private final NamedParameterJdbcTemplate jdbc;
    private final CodingPersistenceMapper mapper;

    public JdbcCodingTargetSnapshotRepositoryAdapter(
            NamedParameterJdbcTemplate jdbc, CodingPersistenceMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public CodingTargetSnapshot create(CodingTargetSnapshot snapshot) {
        CodingTargetSnapshot required = Objects.requireNonNull(snapshot, "snapshot");
        try {
            jdbc.update(
                    """
                    INSERT INTO crewscope.coding_target_snapshot (
                        id, organization_id, team_id, workspace_id, project_id, task_id,
                        task_brief_hash, revision, parent_snapshot_id, change_reason,
                        repository_binding_id, repository_binding_version, repository_kind,
                        repository_key, baseline_ref, baseline_commit, allowed_paths,
                        build_profile_key, build_profile_version, build_profile_hash,
                        acceptance_criteria, snapshot_hash, created_at, created_by_principal_id
                    ) VALUES (
                        :id, :organizationId, :teamId, :workspaceId, :projectId, :taskId,
                        :taskBriefHash, :revision, :parentSnapshotId, :changeReason,
                        :repositoryBindingId, :repositoryBindingVersion, :repositoryKind,
                        :repositoryKey, :baselineRef, :baselineCommit, CAST(:allowedPaths AS jsonb),
                        :buildProfileKey, :buildProfileVersion, :buildProfileHash,
                        CAST(:acceptanceCriteria AS jsonb), :snapshotHash, :createdAt, :createdBy
                    )
                    """,
                    parameters(required));
        } catch (DataIntegrityViolationException failure) {
            throw CodingPersistenceConflictMapper.codingTarget(failure, required);
        }
        return findById(
                        required.scope().organizationId(),
                        required.scope().teamId(),
                        required.scope().projectId(),
                        required.id())
                .orElseThrow(() -> new AggregateNotFoundException("CodingTargetSnapshot", required.id()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CodingTargetSnapshot> findById(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            CodingTargetSnapshotId snapshotId) {
        return query(
                        """
                        WHERE organization_id = :organizationId
                          AND team_id = :teamId
                          AND project_id = :projectId
                          AND id = :id
                        """,
                        scopeParameters(organizationId, teamId, workProjectId)
                                .addValue("id", Objects.requireNonNull(snapshotId).value()))
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CodingTargetSnapshot> findLatestByTask(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            TaskId taskId) {
        return query(
                        """
                        WHERE organization_id = :organizationId
                          AND team_id = :teamId
                          AND project_id = :projectId
                          AND task_id = :taskId
                        ORDER BY revision DESC
                        LIMIT 1
                        """,
                        scopeParameters(organizationId, teamId, workProjectId)
                                .addValue("taskId", Objects.requireNonNull(taskId).value()))
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CodingTargetSnapshot> findByTask(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            TaskId taskId) {
        return query(
                """
                WHERE organization_id = :organizationId
                  AND team_id = :teamId
                  AND project_id = :projectId
                  AND task_id = :taskId
                ORDER BY revision ASC
                """,
                scopeParameters(organizationId, teamId, workProjectId)
                        .addValue("taskId", Objects.requireNonNull(taskId).value()));
    }

    private List<CodingTargetSnapshot> query(
            String predicate, MapSqlParameterSource parameters) {
        return jdbc.query(
                SELECT + " " + predicate,
                parameters,
                (row, ignored) -> mapper.codingTarget(row));
    }

    private static MapSqlParameterSource scopeParameters(
            OrganizationId organizationId, TeamId teamId, WorkProjectId workProjectId) {
        return new MapSqlParameterSource()
                .addValue("organizationId", Objects.requireNonNull(organizationId).value())
                .addValue("teamId", Objects.requireNonNull(teamId).value())
                .addValue("projectId", Objects.requireNonNull(workProjectId).value());
    }

    private MapSqlParameterSource parameters(CodingTargetSnapshot snapshot) {
        return new MapSqlParameterSource()
                .addValue("id", snapshot.id().value())
                .addValue("organizationId", snapshot.scope().organizationId().value())
                .addValue("teamId", snapshot.scope().teamId().value())
                .addValue("workspaceId", snapshot.scope().workspaceId().value())
                .addValue("projectId", snapshot.scope().projectId().value())
                .addValue("taskId", snapshot.taskId().value())
                .addValue("taskBriefHash", snapshot.taskBriefHash().value())
                .addValue("revision", snapshot.revision())
                .addValue("parentSnapshotId", snapshot.parentSnapshotId()
                        .map(CodingTargetSnapshotId::value).orElse(null))
                .addValue("changeReason", snapshot.changeReason().name())
                .addValue("repositoryBindingId", snapshot.repositoryBindingId().value())
                .addValue("repositoryBindingVersion", snapshot.repositoryBindingVersion())
                .addValue("repositoryKind", snapshot.repositoryKind().name())
                .addValue("repositoryKey", snapshot.repositoryKey().value())
                .addValue("baselineRef", snapshot.baselineRef().value())
                .addValue("baselineCommit", snapshot.baselineCommit().value())
                .addValue("allowedPaths", mapper.json(snapshot.allowedPaths().values()))
                .addValue("buildProfileKey", snapshot.buildProfile().key())
                .addValue("buildProfileVersion", snapshot.buildProfile().version())
                .addValue("buildProfileHash", snapshot.buildProfile().profileHash().value())
                .addValue("acceptanceCriteria", mapper.json(snapshot.acceptanceCriteria()))
                .addValue("snapshotHash", snapshot.snapshotHash().value())
                .addValue("createdAt", CodingJdbcValue.timestamp(snapshot.createdAt()))
                .addValue("createdBy", snapshot.createdByPrincipalId().value());
    }
}
