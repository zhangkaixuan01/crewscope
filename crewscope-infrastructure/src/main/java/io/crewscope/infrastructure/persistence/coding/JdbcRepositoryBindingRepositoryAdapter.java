package io.crewscope.infrastructure.persistence.coding;

import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JDBC adapter for the mutable RepositoryBinding aggregate and its project-scoped key. */
@Repository
public class JdbcRepositoryBindingRepositoryAdapter implements RepositoryBindingRepository {

    private static final String SELECT = "SELECT * FROM crewscope.repository_binding";

    private final NamedParameterJdbcTemplate jdbc;
    private final CodingPersistenceMapper mapper;

    public JdbcRepositoryBindingRepositoryAdapter(
            NamedParameterJdbcTemplate jdbc, CodingPersistenceMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public RepositoryBinding create(RepositoryBinding binding) {
        RepositoryBinding required = Objects.requireNonNull(binding, "binding");
        if (required.version() != 0) {
            throw new DomainValidationException(
                    "repositoryBinding.version", "must be zero when created");
        }
        try {
            jdbc.update(
                    """
                    INSERT INTO crewscope.repository_binding (
                        id, organization_id, team_id, workspace_id, project_id,
                        repository_kind, repository_key, default_branch, status, version,
                        created_at, created_by_principal_id, updated_at, updated_by_principal_id
                    ) VALUES (
                        :id, :organizationId, :teamId, :workspaceId, :projectId,
                        :kind, :repositoryKey, :defaultBranch, :status, :version,
                        :createdAt, :createdBy, :updatedAt, :updatedBy
                    )
                    """,
                    parameters(required));
        } catch (DataIntegrityViolationException failure) {
            throw CodingPersistenceConflictMapper.repositoryBinding(failure, required);
        }
        return findById(
                        required.scope().organizationId(),
                        required.scope().teamId(),
                        required.scope().workProjectId(),
                        required.id())
                .orElseThrow(() -> new AggregateNotFoundException("RepositoryBinding", required.id()));
    }

    @Override
    @Transactional
    public RepositoryBinding update(RepositoryBinding binding) {
        RepositoryBinding required = Objects.requireNonNull(binding, "binding");
        long expectedVersion = required.version() - 1;
        if (expectedVersion < 0) {
            throw new DomainValidationException(
                    "repositoryBinding.version", "must contain one uncommitted mutation");
        }
        PrincipalId updatedBy = required.audit().updatedBy().orElseThrow(() ->
                new DomainValidationException(
                        "repositoryBinding.updatedByPrincipalId", "must identify a Principal"));
        MapSqlParameterSource parameters = parameters(required)
                .addValue("expectedVersion", expectedVersion)
                .addValue("updatedBy", updatedBy.value());
        int affected = jdbc.update(
                """
                UPDATE crewscope.repository_binding
                   SET default_branch = :defaultBranch,
                       status = :status,
                       version = :version,
                       updated_at = :updatedAt,
                       updated_by_principal_id = :updatedBy
                 WHERE organization_id = :organizationId
                   AND team_id = :teamId
                   AND workspace_id = :workspaceId
                   AND project_id = :projectId
                   AND id = :id
                   AND version = :expectedVersion
                """,
                parameters);
        if (affected == 0) {
            throwVersionConflict(required, expectedVersion);
        }
        return findById(
                        required.scope().organizationId(),
                        required.scope().teamId(),
                        required.scope().workProjectId(),
                        required.id())
                .orElseThrow(() -> new AggregateNotFoundException("RepositoryBinding", required.id()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RepositoryBinding> findById(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            RepositoryBindingId bindingId) {
        return first(
                SELECT + """
                 WHERE organization_id = :organizationId
                   AND team_id = :teamId
                   AND project_id = :projectId
                   AND id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("organizationId", Objects.requireNonNull(organizationId).value())
                        .addValue("teamId", Objects.requireNonNull(teamId).value())
                        .addValue("projectId", Objects.requireNonNull(workProjectId).value())
                        .addValue("id", Objects.requireNonNull(bindingId).value()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RepositoryBinding> findByKey(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            RepositoryKey repositoryKey) {
        return first(
                SELECT + """
                 WHERE organization_id = :organizationId
                   AND team_id = :teamId
                   AND project_id = :projectId
                   AND repository_key = :repositoryKey
                """,
                new MapSqlParameterSource()
                        .addValue("organizationId", Objects.requireNonNull(organizationId).value())
                        .addValue("teamId", Objects.requireNonNull(teamId).value())
                        .addValue("projectId", Objects.requireNonNull(workProjectId).value())
                        .addValue("repositoryKey", Objects.requireNonNull(repositoryKey).value()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RepositoryBinding> findByWorkProject(
            OrganizationId organizationId, TeamId teamId, WorkProjectId workProjectId) {
        return jdbc.query(
                SELECT + """
                 WHERE organization_id = :organizationId
                   AND team_id = :teamId
                   AND project_id = :projectId
                 ORDER BY updated_at DESC, id DESC
                """,
                new MapSqlParameterSource()
                        .addValue("organizationId", Objects.requireNonNull(organizationId).value())
                        .addValue("teamId", Objects.requireNonNull(teamId).value())
                        .addValue("projectId", Objects.requireNonNull(workProjectId).value()),
                (row, ignored) -> mapper.repositoryBinding(row));
    }

    private Optional<RepositoryBinding> first(String sql, MapSqlParameterSource parameters) {
        return jdbc.query(sql, parameters, (row, ignored) -> mapper.repositoryBinding(row))
                .stream()
                .findFirst();
    }

    private void throwVersionConflict(RepositoryBinding binding, long expectedVersion) {
        List<Long> versions = jdbc.query(
                """
                SELECT version FROM crewscope.repository_binding
                 WHERE organization_id = :organizationId
                   AND team_id = :teamId
                   AND workspace_id = :workspaceId
                   AND project_id = :projectId
                   AND id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("organizationId", binding.scope().organizationId().value())
                        .addValue("teamId", binding.scope().teamId().value())
                        .addValue("workspaceId", binding.scope().workspaceId().value())
                        .addValue("projectId", binding.scope().workProjectId().value())
                        .addValue("id", binding.id().value()),
                (row, ignored) -> row.getLong("version"));
        if (versions.isEmpty()) {
            throw new AggregateNotFoundException("RepositoryBinding", binding.id());
        }
        throw new OptimisticLockConflictException(
                "RepositoryBinding", binding.id(), expectedVersion, versions.get(0));
    }

    private static MapSqlParameterSource parameters(RepositoryBinding binding) {
        PrincipalId createdBy = binding.audit().createdBy().orElseThrow(() ->
                new DomainValidationException(
                        "repositoryBinding.createdByPrincipalId", "must identify a Principal"));
        PrincipalId updatedBy = binding.audit().updatedBy().orElse(createdBy);
        return new MapSqlParameterSource()
                .addValue("id", binding.id().value())
                .addValue("organizationId", binding.scope().organizationId().value())
                .addValue("teamId", binding.scope().teamId().value())
                .addValue("workspaceId", binding.scope().workspaceId().value())
                .addValue("projectId", binding.scope().workProjectId().value())
                .addValue("kind", binding.kind().name())
                .addValue("repositoryKey", binding.repositoryKey().value())
                .addValue("defaultBranch", binding.defaultBranch().value())
                .addValue("status", binding.status().name())
                .addValue("version", binding.version())
                .addValue("createdAt", CodingJdbcValue.timestamp(binding.audit().createdAt()))
                .addValue("createdBy", createdBy.value())
                .addValue("updatedAt", CodingJdbcValue.timestamp(binding.audit().updatedAt()))
                .addValue("updatedBy", updatedBy.value());
    }
}
