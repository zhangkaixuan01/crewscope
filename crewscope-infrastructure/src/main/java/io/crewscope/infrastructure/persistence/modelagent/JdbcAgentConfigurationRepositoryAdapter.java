package io.crewscope.infrastructure.persistence.modelagent;

import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.domain.agent.AgentConfigurationRevision;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentExecutionModelBinding;
import io.crewscope.domain.agent.AgentModelSelection;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Locked append adapter for Agent configuration headers and their two-scope model bindings. */
@Repository
public class JdbcAgentConfigurationRepositoryAdapter
        implements AgentConfigurationRepository {

    private static final String GRAPH_SELECT = """
            SELECT configuration.*,
                   binding.execution_scope, binding.binding_kind,
                   binding.primary_connection_id, binding.primary_provider_key,
                   binding.primary_provider_definition_hash,
                   binding.primary_catalog_entry_id, binding.primary_model_id,
                   binding.primary_catalog_revision, binding.primary_catalog_content_hash,
                   binding.fallback_connection_id, binding.fallback_provider_key,
                   binding.fallback_provider_definition_hash,
                   binding.fallback_catalog_entry_id, binding.fallback_model_id,
                   binding.fallback_catalog_revision, binding.fallback_catalog_content_hash,
                   primary_connection.owner_type AS primary_owner_type,
                   primary_connection.owner_id AS primary_owner_id,
                   primary_connection.owner_team_id AS primary_owner_team_id,
                   primary_connection.owner_user_principal_id AS primary_owner_user_principal_id,
                   fallback_connection.owner_type AS fallback_owner_type,
                   fallback_connection.owner_id AS fallback_owner_id,
                   fallback_connection.owner_team_id AS fallback_owner_team_id,
                   fallback_connection.owner_user_principal_id AS fallback_owner_user_principal_id
              FROM %s configuration
              LEFT JOIN crewscope.agent_configuration_model_binding binding
                ON binding.organization_id = configuration.organization_id
               AND binding.agent_profile_id = configuration.agent_profile_id
               AND binding.configuration_revision = configuration.configuration_revision
              LEFT JOIN crewscope.model_connection primary_connection
                ON primary_connection.organization_id = binding.organization_id
               AND primary_connection.id = binding.primary_connection_id
              LEFT JOIN crewscope.model_connection fallback_connection
                ON fallback_connection.organization_id = binding.organization_id
               AND fallback_connection.id = binding.fallback_connection_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ModelAgentPersistenceMapper mapper;
    private final AgentProfileRepository profiles;

    public JdbcAgentConfigurationRepositoryAdapter(
            NamedParameterJdbcTemplate jdbc,
            ModelAgentPersistenceMapper mapper,
            AgentProfileRepository profiles) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
    }

    @Override
    @Transactional
    public AgentConfigurationVersion append(AgentConfigurationVersion configuration) {
        AgentConfigurationVersion required = Objects.requireNonNull(configuration, "configuration");
        ModelAgentJdbcGuard.lock(
                jdbc,
                "crewscope:agent-configuration:" + required.organizationId() + ":"
                        + required.agentProfileId());
        Long latest = jdbc.query(
                        """
                        SELECT configuration_revision FROM crewscope.agent_configuration_version
                         WHERE organization_id = :organizationId AND agent_profile_id = :agentProfileId
                         ORDER BY configuration_revision DESC LIMIT 1
                        """,
                        identity(required.organizationId(), required.agentProfileId()),
                        (row, ignored) -> row.getLong(1))
                .stream().findFirst().orElse(null);
        ModelAgentJdbcGuard.requireNextRevision(
                "agentConfiguration.revision", required.revision().value(), latest);
        try {
            jdbc.update(
                    """
                    INSERT INTO crewscope.agent_configuration_version (
                        organization_id, agent_profile_id, ownership_type, ownership_team_id,
                        owner_member_id, owner_user_principal_id, template_key, template_version,
                        template_content_hash, configuration_revision,
                        previous_configuration_revision, supplemental_instructions, enabled_tools,
                        structured_output_schema_hash, approved_skill_keys, memory_policy_id,
                        memory_policy_version, budget_policy_id, budget_policy_version,
                        policy_pack_id, policy_pack_version, generate_temperature, generate_top_p,
                        generate_maximum_output_tokens, generate_reasoning_mode,
                        generate_cache_enabled, generate_parallel_tool_calls, generate_seed,
                        generate_maximum_attempts, configuration_hash, created_at,
                        created_by_principal_id
                    ) VALUES (
                        :organizationId, :agentProfileId, :ownershipType, :ownershipTeamId,
                        :ownerMemberId, :ownerUserPrincipalId, :templateKey, :templateVersion,
                        :templateContentHash, :configurationRevision,
                        :previousConfigurationRevision, :supplementalInstructions, :enabledTools,
                        :structuredOutputSchemaHash, :approvedSkillKeys, :memoryPolicyId,
                        :memoryPolicyVersion, :budgetPolicyId, :budgetPolicyVersion,
                        :policyPackId, :policyPackVersion, :generateTemperature, :generateTopP,
                        :generateMaximumOutputTokens, :generateReasoningMode,
                        :generateCacheEnabled, :generateParallelToolCalls, :generateSeed,
                        :generateMaximumAttempts, :configurationHash, :createdAt,
                        :createdBy
                    )
                    """,
                    parameters(required));
            required.personalModelBinding().ifPresent(binding -> insertBinding(required, binding));
            required.teamModelBinding().ifPresent(binding -> insertBinding(required, binding));
        } catch (DataIntegrityViolationException failure) {
            DomainValidationException conflict = new DomainValidationException(
                    "agentConfiguration.revision",
                    "conflicts with the committed Profile, Template or model coordinates");
            conflict.addSuppressed(failure);
            throw conflict;
        }
        return findByRevision(
                        required.organizationId(), required.agentProfileId(), required.revision())
                .orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentConfigurationVersion> findCurrent(
            OrganizationId organizationId, AgentProfileId agentProfileId) {
        List<AgentConfigurationVersion> values = load(
                GRAPH_SELECT.formatted("""
                    (SELECT * FROM crewscope.agent_configuration_version
                      WHERE organization_id = :organizationId AND agent_profile_id = :agentProfileId
                      ORDER BY configuration_revision DESC LIMIT 1)
                    """) + " ORDER BY configuration.configuration_revision DESC, binding.execution_scope",
                identity(organizationId, agentProfileId));
        return values.stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentConfigurationVersion> findByRevision(
            OrganizationId organizationId,
            AgentProfileId agentProfileId,
            AgentConfigurationRevision revision) {
        return load(
                        GRAPH_SELECT.formatted("""
                            (SELECT * FROM crewscope.agent_configuration_version
                              WHERE organization_id = :organizationId
                                AND agent_profile_id = :agentProfileId
                                AND configuration_revision = :revision)
                            """) + " ORDER BY binding.execution_scope",
                        identity(organizationId, agentProfileId)
                                .addValue("revision", Objects.requireNonNull(revision).value()))
                .stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentConfigurationVersion> findAll(
            OrganizationId organizationId, AgentProfileId agentProfileId) {
        return load(
                GRAPH_SELECT.formatted("crewscope.agent_configuration_version") + """
                 WHERE configuration.organization_id = :organizationId
                   AND configuration.agent_profile_id = :agentProfileId
                 ORDER BY configuration.configuration_revision, binding.execution_scope
                """,
                identity(organizationId, agentProfileId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentConfigurationVersion> findPage(
            OrganizationId organizationId,
            AgentProfileId agentProfileId,
            int offset,
            int limit) {
        ModelAgentJdbcGuard.requirePage(offset, limit, "agentConfiguration.page");
        return load(
                GRAPH_SELECT.formatted("""
                    (SELECT * FROM crewscope.agent_configuration_version
                      WHERE organization_id = :organizationId AND agent_profile_id = :agentProfileId
                      ORDER BY configuration_revision DESC OFFSET :offset LIMIT :limit)
                    """) + " ORDER BY configuration.configuration_revision DESC, binding.execution_scope",
                identity(organizationId, agentProfileId)
                        .addValue("offset", offset)
                        .addValue("limit", limit));
    }

    private List<AgentConfigurationVersion> load(
            String sql, MapSqlParameterSource parameters) {
        List<ConfigurationGraphRow> rows = jdbc.query(
                sql,
                parameters,
                (row, ignored) -> new ConfigurationGraphRow(
                        mapper.configurationHeader(row), mapper.configurationBinding(row)));
        if (rows.isEmpty()) {
            return List.of();
        }
        OrganizationId organizationId = new OrganizationId(
                (java.util.UUID) parameters.getValue("organizationId"));
        AgentProfileId profileId = new AgentProfileId(
                (java.util.UUID) parameters.getValue("agentProfileId"));
        AgentProfile profile = profiles.findById(organizationId, profileId)
                .orElseThrow(() -> new DomainValidationException(
                        "agentConfiguration.agentProfileId", "references a missing Profile"));
        ModelAgentPersistenceMapper.ConfigurationHeader first = rows.get(0).header();
        AgentTemplateDefinition template = findTemplate(
                organizationId, first.templateVersion(), first.templateContentHash().value());

        Map<Long, ConfigurationAccumulator> grouped = new LinkedHashMap<>();
        for (ConfigurationGraphRow row : rows) {
            ConfigurationAccumulator accumulator = grouped.computeIfAbsent(
                    row.header().revision().value(),
                    ignored -> new ConfigurationAccumulator(row.header(), new ArrayList<>()));
            row.binding().ifPresent(accumulator.bindings()::add);
        }
        return grouped.values().stream()
                .map(value -> mapper.configuration(
                        profile, template, value.header(), value.bindings()))
                .toList();
    }

    private AgentTemplateDefinition findTemplate(
            OrganizationId organizationId,
            io.crewscope.domain.agent.AgentTemplateVersion version,
            String contentHash) {
        return jdbc.query(
                        """
                        SELECT * FROM crewscope.agent_template_version
                         WHERE organization_id = :organizationId
                           AND template_key = :templateKey AND template_version = :templateVersion
                           AND content_hash = :contentHash
                        """,
                        new MapSqlParameterSource()
                                .addValue("organizationId", organizationId.value())
                                .addValue("templateKey", version.key().value())
                                .addValue("templateVersion", version.version())
                                .addValue("contentHash", contentHash),
                        (row, ignored) -> mapper.template(row))
                .stream().findFirst()
                .orElseThrow(() -> new DomainValidationException(
                        "agentConfiguration.templateVersion", "references a missing template"));
    }

    private void insertBinding(
            AgentConfigurationVersion configuration, AgentExecutionModelBinding binding) {
        MapSqlParameterSource values = identity(
                        configuration.organizationId(), configuration.agentProfileId())
                .addValue("configurationRevision", configuration.revision().value())
                .addValue("executionScope", binding.executionScope().name())
                .addValue("bindingKind", binding.kind().name());
        if (binding.directBinding().isPresent()) {
            addSelection(values, "primary", binding.directBinding().orElseThrow().primary());
            if (binding.directBinding().orElseThrow().fallback().isPresent()) {
                addSelection(values, "fallback", binding.directBinding().orElseThrow().fallback().orElseThrow());
            } else {
                addEmptySelection(values, "fallback");
            }
        } else {
            addEmptySelection(values, "primary");
            addEmptySelection(values, "fallback");
        }
        jdbc.update(
                """
                INSERT INTO crewscope.agent_configuration_model_binding (
                    organization_id, agent_profile_id, configuration_revision,
                    execution_scope, binding_kind,
                    primary_connection_id, primary_provider_key,
                    primary_provider_definition_hash, primary_catalog_entry_id,
                    primary_model_id, primary_catalog_revision, primary_catalog_content_hash,
                    fallback_connection_id, fallback_provider_key,
                    fallback_provider_definition_hash, fallback_catalog_entry_id,
                    fallback_model_id, fallback_catalog_revision, fallback_catalog_content_hash
                ) VALUES (
                    :organizationId, :agentProfileId, :configurationRevision,
                    :executionScope, :bindingKind,
                    :primaryConnectionId, :primaryProviderKey,
                    :primaryProviderDefinitionHash, :primaryCatalogEntryId,
                    :primaryModelId, :primaryCatalogRevision, :primaryCatalogContentHash,
                    :fallbackConnectionId, :fallbackProviderKey,
                    :fallbackProviderDefinitionHash, :fallbackCatalogEntryId,
                    :fallbackModelId, :fallbackCatalogRevision, :fallbackCatalogContentHash
                )
                """,
                values);
    }

    private MapSqlParameterSource parameters(AgentConfigurationVersion value) {
        PrincipalId createdBy = value.audit().createdBy().orElseThrow();
        return identity(value.organizationId(), value.agentProfileId())
                .addValue("ownershipType", value.ownership().type().name())
                .addValue("ownershipTeamId", value.ownership().teamId().map(id -> id.value()).orElse(null))
                .addValue("ownerMemberId", value.ownership().ownerMemberId().map(id -> id.value()).orElse(null))
                .addValue("ownerUserPrincipalId", value.ownerUserPrincipalId().map(id -> id.value()).orElse(null))
                .addValue("templateKey", value.templateVersion().key().value())
                .addValue("templateVersion", value.templateVersion().version())
                .addValue("templateContentHash", value.templateContentHash().value())
                .addValue("configurationRevision", value.revision().value())
                .addValue("previousConfigurationRevision", value.previousRevision().map(AgentConfigurationRevision::value).orElse(null))
                .addValue("supplementalInstructions", value.templateConfiguration().supplementalInstructions().orElse(null))
                .addValue("enabledTools", mapper.jsonb(value.templateConfiguration().enabledTools().stream().map(tool -> tool.value()).sorted().toList()))
                .addValue("structuredOutputSchemaHash", value.templateConfiguration().structuredOutputSchemaHash().map(hash -> hash.value()).orElse(null))
                .addValue("approvedSkillKeys", mapper.jsonb(value.approvedSkillKeys().stream().sorted().toList()))
                .addValue("memoryPolicyId", value.memoryPolicy().map(policy -> policy.policyId()).orElse(null))
                .addValue("memoryPolicyVersion", value.memoryPolicy().map(policy -> policy.version()).orElse(null))
                .addValue("budgetPolicyId", value.budgetPolicy().map(policy -> policy.policyId()).orElse(null))
                .addValue("budgetPolicyVersion", value.budgetPolicy().map(policy -> policy.version()).orElse(null))
                .addValue("policyPackId", value.policyPack().id().value())
                .addValue("policyPackVersion", value.policyPack().version())
                .addValue("generateTemperature", value.generateOptions().temperature().orElse(null))
                .addValue("generateTopP", value.generateOptions().topP().orElse(null))
                .addValue("generateMaximumOutputTokens", value.generateOptions().maximumOutputTokens().orElse(null))
                .addValue("generateReasoningMode", value.generateOptions().reasoningMode().name())
                .addValue("generateCacheEnabled", value.generateOptions().cacheEnabled())
                .addValue("generateParallelToolCalls", value.generateOptions().parallelToolCalls())
                .addValue("generateSeed", value.generateOptions().seed().orElse(null))
                .addValue("generateMaximumAttempts", value.generateOptions().maximumAttempts())
                .addValue("configurationHash", value.configurationHash().value())
                .addValue("createdAt", timestamp(value.audit().createdAt()))
                .addValue("createdBy", createdBy.value());
    }

    private static MapSqlParameterSource identity(
            OrganizationId organizationId, AgentProfileId profileId) {
        return new MapSqlParameterSource()
                .addValue("organizationId", Objects.requireNonNull(organizationId).value())
                .addValue("agentProfileId", Objects.requireNonNull(profileId).value());
    }

    private static void addSelection(
            MapSqlParameterSource values, String prefix, AgentModelSelection selection) {
        values.addValue(prefix + "ConnectionId", selection.connectionId().value())
                .addValue(prefix + "ProviderKey", selection.providerKey().value())
                .addValue(prefix + "ProviderDefinitionHash", selection.providerDefinitionHash().value())
                .addValue(prefix + "CatalogEntryId", selection.catalogCoordinate().entryId().value())
                .addValue(prefix + "ModelId", selection.catalogCoordinate().modelId().value())
                .addValue(prefix + "CatalogRevision", selection.catalogCoordinate().catalogRevision().value())
                .addValue(prefix + "CatalogContentHash", selection.catalogContentHash().value());
    }

    private static void addEmptySelection(MapSqlParameterSource values, String prefix) {
        values.addValue(prefix + "ConnectionId", null)
                .addValue(prefix + "ProviderKey", null)
                .addValue(prefix + "ProviderDefinitionHash", null)
                .addValue(prefix + "CatalogEntryId", null)
                .addValue(prefix + "ModelId", null)
                .addValue(prefix + "CatalogRevision", null)
                .addValue(prefix + "CatalogContentHash", null);
    }

    private static OffsetDateTime timestamp(UtcTimestamp value) {
        return OffsetDateTime.ofInstant(value.value(), ZoneOffset.UTC);
    }

    private record ConfigurationGraphRow(
            ModelAgentPersistenceMapper.ConfigurationHeader header,
            Optional<AgentExecutionModelBinding> binding) {}

    private record ConfigurationAccumulator(
            ModelAgentPersistenceMapper.ConfigurationHeader header,
            List<AgentExecutionModelBinding> bindings) {}
}
