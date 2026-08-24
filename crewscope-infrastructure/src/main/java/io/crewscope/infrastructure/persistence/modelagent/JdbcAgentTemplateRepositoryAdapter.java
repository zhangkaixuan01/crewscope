package io.crewscope.infrastructure.persistence.modelagent;

import io.crewscope.application.agent.AgentTemplateRepository;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.AgentTemplateKey;
import io.crewscope.domain.agent.AgentTemplatePublisherScope;
import io.crewscope.domain.agent.AgentTemplateVersion;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL adapter for append-only Agent template definitions and lifecycle state. */
@Repository
public class JdbcAgentTemplateRepositoryAdapter implements AgentTemplateRepository {

    private static final String SELECT = "SELECT * FROM crewscope.agent_template_version";

    private final NamedParameterJdbcTemplate jdbc;
    private final ModelAgentPersistenceMapper mapper;

    public JdbcAgentTemplateRepositoryAdapter(
            NamedParameterJdbcTemplate jdbc, ModelAgentPersistenceMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public AgentTemplateDefinition append(AgentTemplateDefinition definition) {
        AgentTemplateDefinition required = Objects.requireNonNull(definition, "definition");
        String stream = required.publisherScope().organizationId() + ":"
                + publisherId(required.publisherScope()) + ":" + required.templateVersion().key();
        ModelAgentJdbcGuard.lock(jdbc, "crewscope:agent-template:" + stream);
        Long latest = jdbc.query(
                        """
                        SELECT template_version FROM crewscope.agent_template_version
                         WHERE organization_id = :organizationId
                           AND publisher_type = :publisherType AND publisher_id = :publisherId
                           AND template_key = :templateKey
                         ORDER BY template_version DESC LIMIT 1
                        """,
                        scopeParameters(required.publisherScope())
                                .addValue("templateKey", required.templateVersion().key().value()),
                        (row, ignored) -> row.getLong(1))
                .stream().findFirst().orElse(null);
        ModelAgentJdbcGuard.requireNextRevision(
                "agentTemplate.version", required.templateVersion().version(), latest);
        try {
            jdbc.update(
                    """
                    INSERT INTO crewscope.agent_template_version (
                        organization_id, publisher_type, publisher_id, publisher_team_id,
                        template_key, template_version, previous_template_version,
                        runtime_role, allowed_ownership_types, allowed_execution_scopes,
                        declared_capabilities, required_model_capabilities, capability_hash,
                        system_prompt_baseline, allowed_tools, approved_skill_keys,
                        structured_output_schema, structured_output_schema_hash,
                        member_configurable_slots, administrator_configurable_slots,
                        policy_hash, content_hash, status, lifecycle_version,
                        created_at, created_by_principal_id, updated_at, updated_by_principal_id
                    ) VALUES (
                        :organizationId, :publisherType, :publisherId, :publisherTeamId,
                        :templateKey, :templateVersion, :previousTemplateVersion,
                        :runtimeRole, :allowedOwnershipTypes, :allowedExecutionScopes,
                        :declaredCapabilities, :requiredModelCapabilities, :capabilityHash,
                        :systemPromptBaseline, :allowedTools, :approvedSkillKeys,
                        :structuredOutputSchema, :structuredOutputSchemaHash,
                        :memberConfigurableSlots, :administratorConfigurableSlots,
                        :policyHash, :contentHash, :status, :lifecycleVersion,
                        :createdAt, :createdBy, :updatedAt, :updatedBy
                    )
                    """,
                    parameters(required));
        } catch (DataIntegrityViolationException failure) {
            DomainValidationException conflict = new DomainValidationException(
                    "agentTemplate.version", "conflicts with the committed template stream");
            conflict.addSuppressed(failure);
            throw conflict;
        }
        return findByVersion(required.publisherScope(), required.templateVersion()).orElseThrow();
    }

    @Override
    @Transactional
    public AgentTemplateDefinition updateLifecycle(AgentTemplateDefinition definition) {
        AgentTemplateDefinition required = Objects.requireNonNull(definition, "definition");
        long expected = required.lifecycleVersion() - 1;
        if (expected < 0) {
            throw new DomainValidationException(
                    "agentTemplate.lifecycleVersion", "must contain one lifecycle mutation");
        }
        int affected = jdbc.update(
                """
                UPDATE crewscope.agent_template_version
                   SET status = :status, lifecycle_version = :lifecycleVersion,
                       updated_at = :updatedAt, updated_by_principal_id = :updatedBy
                 WHERE organization_id = :organizationId
                   AND publisher_type = :publisherType AND publisher_id = :publisherId
                   AND template_key = :templateKey AND template_version = :templateVersion
                   AND content_hash = :contentHash AND lifecycle_version = :expectedVersion
                """,
                parameters(required).addValue("expectedVersion", expected));
        if (affected == 0) {
            List<Long> actual = jdbc.query(
                    """
                    SELECT lifecycle_version FROM crewscope.agent_template_version
                     WHERE organization_id = :organizationId
                       AND publisher_type = :publisherType AND publisher_id = :publisherId
                       AND template_key = :templateKey AND template_version = :templateVersion
                    """,
                    parameters(required),
                    (row, ignored) -> row.getLong(1));
            if (actual.isEmpty()) {
                throw new DomainValidationException("agentTemplate", "does not exist");
            }
            throw new DomainValidationException(
                    "agentTemplate.lifecycleVersion",
                    "expected " + expected + " but committed version is " + actual.get(0));
        }
        return findByVersion(required.publisherScope(), required.templateVersion()).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentTemplateDefinition> findByVersion(
            AgentTemplatePublisherScope publisherScope, AgentTemplateVersion templateVersion) {
        return jdbc.query(
                        SELECT + """
                         WHERE organization_id = :organizationId
                           AND publisher_type = :publisherType AND publisher_id = :publisherId
                           AND template_key = :templateKey AND template_version = :templateVersion
                        """,
                        scopeParameters(Objects.requireNonNull(publisherScope))
                                .addValue("templateKey", Objects.requireNonNull(templateVersion).key().value())
                                .addValue("templateVersion", templateVersion.version()),
                        (row, ignored) -> mapper.template(row))
                .stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentTemplateDefinition> findLatest(
            AgentTemplatePublisherScope publisherScope, AgentTemplateKey templateKey) {
        return jdbc.query(
                        SELECT + """
                         WHERE organization_id = :organizationId
                           AND publisher_type = :publisherType AND publisher_id = :publisherId
                           AND template_key = :templateKey
                         ORDER BY template_version DESC LIMIT 1
                        """,
                        scopeParameters(Objects.requireNonNull(publisherScope))
                                .addValue("templateKey", Objects.requireNonNull(templateKey).value()),
                        (row, ignored) -> mapper.template(row))
                .stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentTemplateDefinition> findPage(
            AgentTemplatePublisherScope publisherScope, int offset, int limit) {
        ModelAgentJdbcGuard.requirePage(offset, limit, "agentTemplate.page");
        return jdbc.query(
                SELECT + """
                 WHERE organization_id = :organizationId
                   AND publisher_type = :publisherType AND publisher_id = :publisherId
                 ORDER BY template_key, template_version DESC
                 OFFSET :offset LIMIT :limit
                """,
                scopeParameters(Objects.requireNonNull(publisherScope))
                        .addValue("offset", offset)
                        .addValue("limit", limit),
                (row, ignored) -> mapper.template(row));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentTemplateDefinition> findLatestActivePage(
            AgentTemplatePublisherScope publisherScope, int offset, int limit) {
        ModelAgentJdbcGuard.requirePage(offset, limit, "agentTemplate.page");
        return jdbc.query(
                "SELECT * FROM (" + SELECT + """
                 WHERE organization_id = :organizationId
                   AND publisher_type = :publisherType AND publisher_id = :publisherId
                   AND status = 'ACTIVE'
                 ORDER BY template_key, template_version DESC
                ) latest
                WHERE NOT EXISTS (
                    SELECT 1 FROM crewscope.agent_template_version newer
                     WHERE newer.organization_id = latest.organization_id
                       AND newer.publisher_type = latest.publisher_type
                       AND newer.publisher_id = latest.publisher_id
                       AND newer.template_key = latest.template_key
                       AND newer.template_version > latest.template_version
                )
                ORDER BY template_key
                OFFSET :offset LIMIT :limit
                """,
                scopeParameters(Objects.requireNonNull(publisherScope))
                        .addValue("offset", offset)
                        .addValue("limit", limit),
                (row, ignored) -> mapper.template(row));
    }

    private MapSqlParameterSource parameters(AgentTemplateDefinition value) {
        PrincipalId createdBy = value.audit().createdBy().orElseThrow();
        PrincipalId updatedBy = value.audit().updatedBy().orElseThrow();
        return scopeParameters(value.publisherScope())
                .addValue("publisherTeamId", value.publisherScope().teamId().map(id -> id.value()).orElse(null))
                .addValue("templateKey", value.templateVersion().key().value())
                .addValue("templateVersion", value.templateVersion().version())
                .addValue("previousTemplateVersion", value.previousVersion().map(AgentTemplateVersion::version).orElse(null))
                .addValue("runtimeRole", value.runtimeRole().name())
                .addValue("allowedOwnershipTypes", mapper.jsonb(value.allowedOwnershipTypes().stream().map(Enum::name).sorted().toList()))
                .addValue("allowedExecutionScopes", mapper.jsonb(value.allowedExecutionScopes().stream().map(Enum::name).sorted().toList()))
                .addValue("declaredCapabilities", mapper.jsonb(value.capabilities().declaredCapabilities().stream().map(capability -> capability.value()).sorted().toList()))
                .addValue("requiredModelCapabilities", mapper.jsonb(value.capabilities().requiredModelCapabilities().stream().map(capability -> capability.value()).sorted().toList()))
                .addValue("capabilityHash", value.capabilities().capabilityHash().value())
                .addValue("systemPromptBaseline", value.policy().systemPromptBaseline())
                .addValue("allowedTools", mapper.jsonb(value.policy().allowedTools().stream().map(tool -> tool.value()).sorted().toList()))
                .addValue("approvedSkillKeys", mapper.jsonb(value.policy().approvedSkillKeys().stream().sorted().toList()))
                .addValue("structuredOutputSchema", value.policy().structuredOutputSchema().orElse(null))
                .addValue("structuredOutputSchemaHash", value.policy().structuredOutputSchemaHash().map(hash -> hash.value()).orElse(null))
                .addValue("memberConfigurableSlots", mapper.jsonb(value.policy().memberConfigurableSlots().stream().map(Enum::name).sorted().toList()))
                .addValue("administratorConfigurableSlots", mapper.jsonb(value.policy().administratorConfigurableSlots().stream().map(Enum::name).sorted().toList()))
                .addValue("policyHash", value.policy().policyHash().value())
                .addValue("contentHash", value.contentHash().value())
                .addValue("status", value.status().name())
                .addValue("lifecycleVersion", value.lifecycleVersion())
                .addValue("createdAt", timestamp(value.audit().createdAt()))
                .addValue("createdBy", createdBy.value())
                .addValue("updatedAt", timestamp(value.audit().updatedAt()))
                .addValue("updatedBy", updatedBy.value());
    }

    private static MapSqlParameterSource scopeParameters(AgentTemplatePublisherScope value) {
        return new MapSqlParameterSource()
                .addValue("organizationId", value.organizationId().value())
                .addValue("publisherType", value.teamId().isPresent() ? "TEAM" : "ORGANIZATION")
                .addValue("publisherId", publisherId(value));
    }

    private static Object publisherId(AgentTemplatePublisherScope value) {
        return value.teamId().map(id -> id.value()).orElse(value.organizationId().value());
    }

    private static OffsetDateTime timestamp(UtcTimestamp value) {
        return OffsetDateTime.ofInstant(value.value(), ZoneOffset.UTC);
    }
}
