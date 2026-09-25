package io.crewscope.infrastructure.persistence.coding;

import io.crewscope.application.coding.ProjectExecutionDefaultsRepository;
import io.crewscope.domain.coding.BuildProfileReference;
import io.crewscope.domain.coding.ProjectExecutionDefaults;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workspace.AgentProfileId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JDBC persistence for the single project defaults row. */
@Repository
public final class JdbcProjectExecutionDefaultsRepositoryAdapter implements ProjectExecutionDefaultsRepository {
    private static final String SELECT = "SELECT * FROM crewscope.project_execution_defaults";
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcProjectExecutionDefaultsRepositoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProjectExecutionDefaults> find(
            OrganizationId organizationId, TeamId teamId, WorkProjectId projectId) {
        return jdbc.query(
                SELECT + " WHERE organization_id = :organizationId AND team_id = :teamId AND project_id = :projectId",
                scope(organizationId, teamId, projectId), this::map).stream().findFirst();
    }

    @Override
    @Transactional
    public ProjectExecutionDefaults replace(ProjectExecutionDefaults value, long expectedVersion) {
        Objects.requireNonNull(value, "value");
        if (expectedVersion < 0 || expectedVersion == Long.MAX_VALUE || value.version() != expectedVersion + 1) {
            throw new IllegalArgumentException("replacement version must increment expectedVersion exactly once");
        }
        MapSqlParameterSource p = parameters(value).addValue("expectedVersion", expectedVersion);
        int updated = jdbc.update(
                """
                UPDATE crewscope.project_execution_defaults SET
                    repository_binding_id = :repositoryBindingId,
                    repository_binding_version = :repositoryBindingVersion,
                    branch = :branch,
                    build_profile_key = :buildProfileKey,
                    build_profile_version = :buildProfileVersion,
                    build_profile_hash = :buildProfileHash,
                    agent_profile_id = :agentProfileId,
                    agent_profile_revision = :agentProfileRevision,
                    version = :version,
                    updated_at = CURRENT_TIMESTAMP
                WHERE organization_id = :organizationId AND team_id = :teamId
                  AND workspace_id = :workspaceId AND project_id = :projectId
                  AND version = :expectedVersion
                """, p);
        if (updated == 0 && expectedVersion == 0) {
            int inserted = jdbc.update(
                        """
                        INSERT INTO crewscope.project_execution_defaults (
                            organization_id, team_id, workspace_id, project_id, version,
                            repository_binding_id, repository_binding_version, branch,
                            build_profile_key, build_profile_version, build_profile_hash,
                            agent_profile_id, agent_profile_revision
                        ) VALUES (
                            :organizationId, :teamId, :workspaceId, :projectId, :version,
                            :repositoryBindingId, :repositoryBindingVersion, :branch,
                            :buildProfileKey, :buildProfileVersion, :buildProfileHash,
                            :agentProfileId, :agentProfileRevision
                        )
                        ON CONFLICT DO NOTHING
                        """, p);
            if (inserted == 1) return value;
            throwVersionConflict(value, expectedVersion);
        }
        if (updated == 0) throwVersionConflict(value, expectedVersion);
        return value;
    }

    private void throwVersionConflict(ProjectExecutionDefaults value, long expectedVersion) {
        Optional<ProjectExecutionDefaults> current = find(value.organizationId(), value.teamId(), value.projectId());
        if (current.isEmpty()) throw new AggregateNotFoundException("ProjectExecutionDefaults", value.projectId());
        throw new OptimisticLockConflictException(
                "ProjectExecutionDefaults", value.projectId(), expectedVersion, current.orElseThrow().version());
    }

    private static MapSqlParameterSource scope(OrganizationId organizationId, TeamId teamId, WorkProjectId projectId) {
        return new MapSqlParameterSource()
                .addValue("organizationId", organizationId.value())
                .addValue("teamId", teamId.value())
                .addValue("projectId", projectId.value());
    }

    private static MapSqlParameterSource parameters(ProjectExecutionDefaults value) {
        MapSqlParameterSource p = scope(value.organizationId(), value.teamId(), value.projectId())
                .addValue("workspaceId", value.workspaceId().value())
                .addValue("version", value.version())
                .addValue("repositoryBindingId", value.repositoryBindingId().map(RepositoryBindingId::value).orElse(null))
                .addValue("repositoryBindingVersion", value.repositoryBindingVersion().orElse(null))
                .addValue("branch", value.branch().map(RepositoryBranchName::value).orElse(null))
                .addValue("buildProfileKey", value.buildProfile().map(BuildProfileReference::key).orElse(null))
                .addValue("buildProfileVersion", value.buildProfile().map(BuildProfileReference::version).orElse(null))
                .addValue("buildProfileHash", value.buildProfile().map(reference -> reference.profileHash().value()).orElse(null))
                .addValue("agentProfileId", value.agentProfileId().map(AgentProfileId::value).orElse(null))
                .addValue("agentProfileRevision", value.agentProfileRevision().orElse(null));
        return p;
    }

    private ProjectExecutionDefaults map(ResultSet row, int ignored) throws SQLException {
        OrganizationId organizationId = new OrganizationId(row.getObject("organization_id", java.util.UUID.class));
        TeamId teamId = new TeamId(row.getObject("team_id", java.util.UUID.class));
        WorkspaceId workspaceId = new WorkspaceId(row.getObject("workspace_id", java.util.UUID.class));
        WorkProjectId projectId = new WorkProjectId(row.getObject("project_id", java.util.UUID.class));
        java.util.UUID bindingId = row.getObject("repository_binding_id", java.util.UUID.class);
        String branch = row.getString("branch");
        String profileKey = row.getString("build_profile_key");
        return new ProjectExecutionDefaults(
                organizationId, teamId, workspaceId, projectId, row.getLong("version"),
                Optional.ofNullable(bindingId).map(RepositoryBindingId::new),
                optionalLong(row, "repository_binding_version"),
                Optional.ofNullable(branch).map(RepositoryBranchName::new),
                profileKey == null ? Optional.empty() : Optional.of(new BuildProfileReference(
                        profileKey, row.getLong("build_profile_version"),
                        new TaskFactHash(row.getString("build_profile_hash").trim()))),
                Optional.ofNullable(row.getObject("agent_profile_id", java.util.UUID.class)).map(AgentProfileId::new),
                optionalLong(row, "agent_profile_revision"));
    }

    private static Optional<Long> optionalLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? Optional.empty() : Optional.of(value);
    }
}
