package io.crewscope.infrastructure.persistence.modelagent;

import io.crewscope.application.agent.AgentModelDefaultRepository;
import io.crewscope.domain.agent.AgentExecutionScope;
import io.crewscope.domain.agent.AgentModelDefault;
import io.crewscope.domain.agent.AgentModelDefaultRevision;
import io.crewscope.domain.agent.AgentModelDefaultScope;
import io.crewscope.domain.agent.AgentModelSelection;
import io.crewscope.domain.agent.AgentTemplateDefinition;
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

/** Locked append adapter for Organization and Team model-default streams. */
@Repository
public class JdbcAgentModelDefaultRepositoryAdapter
        implements AgentModelDefaultRepository {

    private static final String GRAPH_SELECT = """
            SELECT value.*,
                   primary_connection.owner_type AS primary_owner_type,
                   primary_connection.owner_id AS primary_owner_id,
                   primary_connection.owner_team_id AS primary_owner_team_id,
                   primary_connection.owner_user_principal_id AS primary_owner_user_principal_id,
                   fallback_connection.owner_type AS fallback_owner_type,
                   fallback_connection.owner_id AS fallback_owner_id,
                   fallback_connection.owner_team_id AS fallback_owner_team_id,
                   fallback_connection.owner_user_principal_id AS fallback_owner_user_principal_id
              FROM crewscope.agent_model_default value
              JOIN crewscope.model_connection primary_connection
                ON primary_connection.organization_id = value.organization_id
               AND primary_connection.id = value.primary_connection_id
              LEFT JOIN crewscope.model_connection fallback_connection
                ON fallback_connection.organization_id = value.organization_id
               AND fallback_connection.id = value.fallback_connection_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ModelAgentPersistenceMapper mapper;

    public JdbcAgentModelDefaultRepositoryAdapter(
            NamedParameterJdbcTemplate jdbc, ModelAgentPersistenceMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public AgentModelDefault append(AgentModelDefault modelDefault) {
        AgentModelDefault required = Objects.requireNonNull(modelDefault, "modelDefault");
        String stream = required.scope().organizationId() + ":" + scopeId(required.scope())
                + ":" + required.templateVersion() + ":" + required.executionScope();
        ModelAgentJdbcGuard.lock(jdbc, "crewscope:agent-model-default:" + stream);
        Long latest = jdbc.query(
                        """
                        SELECT default_revision FROM crewscope.agent_model_default
                         WHERE organization_id = :organizationId
                           AND default_scope_type = :defaultScopeType
                           AND default_scope_id = :defaultScopeId
                           AND template_key = :templateKey AND template_version = :templateVersion
                           AND execution_scope = :executionScope
                         ORDER BY default_revision DESC LIMIT 1
                        """,
                        keyParameters(
                                required.scope(), required.templateVersion(), required.executionScope()),
                        (row, ignored) -> row.getLong(1))
                .stream().findFirst().orElse(null);
        ModelAgentJdbcGuard.requireNextRevision(
                "agentModelDefault.revision", required.revision().value(), latest);
        MapSqlParameterSource values = parameters(required);
        addSelection(values, "primary", required.modelBinding().primary());
        required.modelBinding().fallback().ifPresentOrElse(
                selection -> addSelection(values, "fallback", selection),
                () -> addEmptySelection(values, "fallback"));
        try {
            jdbc.update(
                    """
                    INSERT INTO crewscope.agent_model_default (
                        organization_id, default_scope_type, default_scope_id, team_id,
                        template_key, template_version, template_content_hash, execution_scope,
                        default_revision, previous_default_revision,
                        primary_connection_id, primary_provider_key,
                        primary_provider_definition_hash, primary_catalog_entry_id,
                        primary_model_id, primary_catalog_revision, primary_catalog_content_hash,
                        fallback_connection_id, fallback_provider_key,
                        fallback_provider_definition_hash, fallback_catalog_entry_id,
                        fallback_model_id, fallback_catalog_revision, fallback_catalog_content_hash,
                        policy_pack_id, policy_pack_version, content_hash,
                        created_at, created_by_principal_id
                    ) VALUES (
                        :organizationId, :defaultScopeType, :defaultScopeId, :teamId,
                        :templateKey, :templateVersion, :templateContentHash, :executionScope,
                        :defaultRevision, :previousDefaultRevision,
                        :primaryConnectionId, :primaryProviderKey,
                        :primaryProviderDefinitionHash, :primaryCatalogEntryId,
                        :primaryModelId, :primaryCatalogRevision, :primaryCatalogContentHash,
                        :fallbackConnectionId, :fallbackProviderKey,
                        :fallbackProviderDefinitionHash, :fallbackCatalogEntryId,
                        :fallbackModelId, :fallbackCatalogRevision, :fallbackCatalogContentHash,
                        :policyPackId, :policyPackVersion, :contentHash,
                        :createdAt, :createdBy
                    )
                    """,
                    values);
        } catch (DataIntegrityViolationException failure) {
            DomainValidationException conflict = new DomainValidationException(
                    "agentModelDefault.revision",
                    "conflicts with the committed Scope, Template or model coordinates");
            conflict.addSuppressed(failure);
            throw conflict;
        }
        return findByRevision(
                        required.scope(),
                        required.templateVersion(),
                        required.executionScope(),
                        required.revision())
                .orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentModelDefault> findCurrent(
            AgentModelDefaultScope scope,
            AgentTemplateVersion templateVersion,
            AgentExecutionScope executionScope) {
        List<AgentModelDefault> candidates = findCurrentCandidates(
                scope, templateVersion, executionScope);
        return candidates.stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentModelDefault> findCurrentCandidates(
            AgentModelDefaultScope scope,
            AgentTemplateVersion templateVersion,
            AgentExecutionScope executionScope) {
        MapSqlParameterSource values = keyParameters(scope, templateVersion, executionScope);
        AgentTemplateDefinition template = findTemplate(
                scope, templateVersion, executionScope, Optional.empty());
        return jdbc.query(
                GRAPH_SELECT + """
                 WHERE value.organization_id = :organizationId
                   AND value.default_scope_type = :defaultScopeType
                   AND value.default_scope_id = :defaultScopeId
                   AND value.template_key = :templateKey
                   AND value.template_version = :templateVersion
                   AND value.execution_scope = :executionScope
                   AND value.default_revision = (
                       SELECT MAX(current.default_revision)
                         FROM crewscope.agent_model_default current
                        WHERE current.organization_id = :organizationId
                          AND current.default_scope_type = :defaultScopeType
                          AND current.default_scope_id = :defaultScopeId
                          AND current.template_key = :templateKey
                          AND current.template_version = :templateVersion
                          AND current.execution_scope = :executionScope)
                """,
                values,
                (row, ignored) -> mapper.modelDefault(row, template));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentModelDefault> findByRevision(
            AgentModelDefaultScope scope,
            AgentTemplateVersion templateVersion,
            AgentExecutionScope executionScope,
            AgentModelDefaultRevision revision) {
        AgentTemplateDefinition template = findTemplate(
                scope, templateVersion, executionScope, Optional.of(revision));
        return jdbc.query(
                        GRAPH_SELECT + """
                         WHERE value.organization_id = :organizationId
                           AND value.default_scope_type = :defaultScopeType
                           AND value.default_scope_id = :defaultScopeId
                           AND value.template_key = :templateKey
                           AND value.template_version = :templateVersion
                           AND value.execution_scope = :executionScope
                           AND value.default_revision = :defaultRevision
                        """,
                        keyParameters(scope, templateVersion, executionScope)
                                .addValue("defaultRevision", Objects.requireNonNull(revision).value()),
                        (row, ignored) -> mapper.modelDefault(row, template))
                .stream().findFirst();
    }

    private AgentTemplateDefinition findTemplate(
            AgentModelDefaultScope scope,
            AgentTemplateVersion version,
            AgentExecutionScope executionScope,
            Optional<AgentModelDefaultRevision> revision) {
        MapSqlParameterSource values = keyParameters(scope, version, executionScope);
        String revisionPredicate = revision
                .map(value -> {
                    values.addValue("defaultRevision", value.value());
                    return "AND value.default_revision = :defaultRevision";
                })
                .orElse("");
        return jdbc.query(
                        """
                        SELECT template.*
                          FROM crewscope.agent_template_version template
                          JOIN crewscope.agent_model_default value
                            ON value.organization_id = template.organization_id
                           AND value.template_key = template.template_key
                           AND value.template_version = template.template_version
                           AND value.template_content_hash = template.content_hash
                         WHERE value.organization_id = :organizationId
                           AND value.default_scope_type = :defaultScopeType
                           AND value.default_scope_id = :defaultScopeId
                           AND value.template_key = :templateKey
                           AND value.template_version = :templateVersion
                           AND value.execution_scope = :executionScope
                        """ + revisionPredicate + """
                         ORDER BY value.default_revision DESC LIMIT 1
                        """,
                        values,
                        (row, ignored) -> mapper.template(row))
                .stream().findFirst()
                .orElseThrow(() -> new DomainValidationException(
                        "agentModelDefault.templateVersion", "references a missing template"));
    }

    private static MapSqlParameterSource keyParameters(
            AgentModelDefaultScope scope,
            AgentTemplateVersion version,
            AgentExecutionScope executionScope) {
        AgentModelDefaultScope requiredScope = Objects.requireNonNull(scope);
        return new MapSqlParameterSource()
                .addValue("organizationId", requiredScope.organizationId().value())
                .addValue("defaultScopeType", requiredScope.teamId().isPresent() ? "TEAM" : "ORGANIZATION")
                .addValue("defaultScopeId", scopeId(requiredScope))
                .addValue("templateKey", Objects.requireNonNull(version).key().value())
                .addValue("templateVersion", version.version())
                .addValue("executionScope", Objects.requireNonNull(executionScope).name());
    }

    private static MapSqlParameterSource parameters(AgentModelDefault value) {
        PrincipalId createdBy = value.audit().createdBy().orElseThrow();
        return keyParameters(value.scope(), value.templateVersion(), value.executionScope())
                .addValue("teamId", value.scope().teamId().map(id -> id.value()).orElse(null))
                .addValue("templateContentHash", value.templateContentHash().value())
                .addValue("defaultRevision", value.revision().value())
                .addValue("previousDefaultRevision", value.previousRevision().map(AgentModelDefaultRevision::value).orElse(null))
                .addValue("policyPackId", value.policyPack().id().value())
                .addValue("policyPackVersion", value.policyPack().version())
                .addValue("contentHash", value.contentHash().value())
                .addValue("createdAt", timestamp(value.audit().createdAt()))
                .addValue("createdBy", createdBy.value());
    }

    private static Object scopeId(AgentModelDefaultScope scope) {
        return scope.teamId().map(id -> id.value()).orElse(scope.organizationId().value());
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
}
