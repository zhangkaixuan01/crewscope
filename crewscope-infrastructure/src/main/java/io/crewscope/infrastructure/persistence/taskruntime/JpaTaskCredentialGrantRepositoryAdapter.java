package io.crewscope.infrastructure.persistence.taskruntime;

import io.crewscope.application.task.TaskCredentialGrantRepository;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskCredentialGrant;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskProviderAuthorization;
import io.crewscope.domain.task.TaskTokenJtiHash;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** JPA aggregate adapter for TaskCredentialGrant and its normalized Tool/Provider scope rows. */
@Repository
public class JpaTaskCredentialGrantRepositoryAdapter implements TaskCredentialGrantRepository {

    private final TaskRuntimeJpaSupport support;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JpaTaskCredentialGrantRepositoryAdapter(
            TaskRuntimeJpaSupport support,
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.support = Objects.requireNonNull(support, "support");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    @Transactional
    public TaskCredentialGrant create(TaskCredentialGrant grant) {
        return persistNew(Objects.requireNonNull(grant, "grant"));
    }

    @Override
    @Transactional
    public TaskCredentialGrant recordUse(TaskCredentialGrant usedGrant) {
        return updateState(usedGrant);
    }

    @Override
    @Transactional
    public TaskCredentialGrant terminate(TaskCredentialGrant terminatedGrant) {
        return updateState(terminatedGrant);
    }

    @Override
    @Transactional
    public TaskCredentialGrant rotate(
            TaskCredentialGrant terminatedCurrent, TaskCredentialGrant replacement) {
        updateState(Objects.requireNonNull(terminatedCurrent, "terminatedCurrent"));
        // Flush the terminal status before the replacement hits the active partial unique index.
        support.entityManager.flush();
        return persistNew(Objects.requireNonNull(replacement, "replacement"));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TaskCredentialGrant> findByJtiHash(
            OrganizationId organizationId, TaskTokenJtiHash jtiHash) {
        return support.entityManager.createQuery(
                        """
                        SELECT row FROM TaskCredentialGrantEntity row
                        WHERE row.organizationId = :organizationId AND row.jtiHash = :jtiHash
                        """,
                        TaskCredentialGrantEntity.class)
                .setParameter("organizationId", organizationId.value())
                .setParameter("jtiHash", jtiHash.value())
                .getResultStream().findFirst().map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TaskCredentialGrant> findActiveByTaskExecution(
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            TaskExecutionId taskExecutionId) {
        return support.entityManager.createQuery(
                        """
                        SELECT row FROM TaskCredentialGrantEntity row
                        WHERE row.organizationId = :organizationId
                          AND row.runtimeEnvironment = :environment
                          AND row.taskExecutionId = :executionId
                          AND row.status = 'ACTIVE'
                        """,
                        TaskCredentialGrantEntity.class)
                .setParameter("organizationId", organizationId.value())
                .setParameter("environment", environment.value())
                .setParameter("executionId", taskExecutionId.value())
                .getResultStream().findFirst().map(this::toDomain);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<TaskCredentialGrant> findExpired(
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            UtcTimestamp authoritativeNow,
            int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
        @SuppressWarnings("unchecked")
        List<TaskCredentialGrantEntity> rows = support.entityManager.createNativeQuery(
                        """
                        SELECT * FROM crewscope.task_credential_grant
                        WHERE organization_id = :organizationId
                          AND runtime_environment = :environment
                          AND status = 'ACTIVE' AND expires_at <= :authoritativeNow
                        ORDER BY expires_at, id
                        FOR UPDATE SKIP LOCKED
                        LIMIT :limit
                        """,
                        TaskCredentialGrantEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(
                        organizationId, "organizationId").value())
                .setParameter("environment", Objects.requireNonNull(
                        environment, "environment").value())
                .setParameter("authoritativeNow", Objects.requireNonNull(
                        authoritativeNow, "authoritativeNow").toOffsetDateTime())
                .setParameter("limit", limit)
                .getResultList();
        return rows.stream().map(this::toDomain).toList();
    }

    private TaskCredentialGrant persistNew(TaskCredentialGrant grant) {
        TaskCredentialGrantEntity row = support.mapper.toEntity(grant);
        support.entityManager.persist(row);
        support.entityManager.flush();
        grant.scope().allowedTools().stream().sorted().forEach(tool -> jdbc.update(
                """
                INSERT INTO crewscope.task_credential_grant_tool (grant_id, tool_key)
                VALUES (:grantId, :tool)
                """,
                Map.of("grantId", grant.id().value(), "tool", tool)));
        for (TaskProviderAuthorization provider : grant.scope().providerAuthorizations()) {
            HashMap<String, Object> parameters = new HashMap<>();
            parameters.put("organizationId", grant.scope().workItemScope().organizationId().value());
            parameters.put("teamId", grant.scope().workItemScope().teamId().value());
            parameters.put("workspaceId", grant.scope().workItemScope().workspaceId().value());
            parameters.put("grantId", grant.id().value());
            parameters.put("bindingId", provider.bindingId().value());
            parameters.put("bindingVersion", provider.bindingVersion());
            parameters.put("connectionGrantId", provider.connectionGrantId()
                    .map(value -> value.value()).orElse(null));
            parameters.put("connectionGrantVersion", provider.connectionGrantVersion().orElse(null));
            parameters.put("capabilities", objectMapper.writeValueAsString(provider.capabilities()
                    .values().stream().map(Object::toString).sorted().toList()));
            parameters.put("resources", objectMapper.writeValueAsString(provider.resources()
                    .resources().stream().sorted().toList()));
            jdbc.update(
                    """
                    INSERT INTO crewscope.task_credential_grant_provider (
                        organization_id, team_id, workspace_id, grant_id,
                        provider_binding_id, provider_binding_version,
                        connection_grant_id, connection_grant_version,
                        capabilities, resources
                    ) VALUES (
                        :organizationId, :teamId, :workspaceId, :grantId,
                        :bindingId, :bindingVersion, :connectionGrantId,
                        :connectionGrantVersion, CAST(:capabilities AS jsonb),
                        CAST(:resources AS jsonb)
                    )
                    """,
                    parameters);
        }
        return toDomain(row);
    }

    private TaskCredentialGrant updateState(TaskCredentialGrant grant) {
        TaskCredentialGrant value = Objects.requireNonNull(grant, "grant");
        long expected = TaskRuntimeJpaSupport.expected(value.version(), "taskCredentialGrant.version");
        TaskCredentialGrantEntity row = support.findScoped(
                        TaskCredentialGrantEntity.class,
                        value.scope().workItemScope().organizationId(), value.id().value())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "TaskCredentialGrant", value.id()));
        TaskRuntimeJpaSupport.requireVersion(
                "TaskCredentialGrant", value.id(), expected, row.version);
        support.mapper.copyState(row, value);
        support.entityManager.flush();
        return toDomain(row);
    }

    private TaskCredentialGrant toDomain(TaskCredentialGrantEntity row) {
        Set<String> tools = Set.copyOf(jdbc.queryForList(
                """
                SELECT tool_key FROM crewscope.task_credential_grant_tool
                WHERE grant_id = :grantId ORDER BY tool_key
                """,
                Map.of("grantId", row.id), String.class));
        Set<TaskProviderAuthorization> providers = jdbc.query(
                        """
                        SELECT provider_binding_id, provider_binding_version,
                               connection_grant_id, connection_grant_version,
                               capabilities::text, resources::text
                        FROM crewscope.task_credential_grant_provider
                        WHERE organization_id = :organizationId AND grant_id = :grantId
                        ORDER BY provider_binding_id
                        """,
                        Map.of("organizationId", row.organizationId, "grantId", row.id),
                        (result, index) -> support.mapper.providerAuthorization(
                                result.getObject("provider_binding_id", java.util.UUID.class),
                                result.getLong("provider_binding_version"),
                                result.getObject("connection_grant_id", java.util.UUID.class),
                                nullableLong(result, "connection_grant_version"),
                                stringList(result.getString("capabilities")),
                                stringList(result.getString("resources"))))
                .stream().collect(Collectors.toUnmodifiableSet());
        return support.mapper.toDomain(row, tools, providers);
    }

    private Long nullableLong(java.sql.ResultSet result, String column) throws java.sql.SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(String value) {
        return List.copyOf(objectMapper.readValue(value, List.class));
    }
}
