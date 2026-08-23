package io.crewscope.infrastructure.persistence.modelagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.crewscope.domain.agent.AgentBudgetPolicyReference;
import io.crewscope.domain.agent.AgentConfigurableSlot;
import io.crewscope.domain.agent.AgentConfigurationHash;
import io.crewscope.domain.agent.AgentConfigurationRevision;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentDirectModelBinding;
import io.crewscope.domain.agent.AgentExecutionModelBinding;
import io.crewscope.domain.agent.AgentExecutionScope;
import io.crewscope.domain.agent.AgentMemoryPolicyReference;
import io.crewscope.domain.agent.AgentModelBindingKind;
import io.crewscope.domain.agent.AgentModelDefault;
import io.crewscope.domain.agent.AgentModelDefaultRevision;
import io.crewscope.domain.agent.AgentModelDefaultScope;
import io.crewscope.domain.agent.AgentModelSelection;
import io.crewscope.domain.agent.AgentOwnership;
import io.crewscope.domain.agent.AgentOwnershipType;
import io.crewscope.domain.agent.AgentReasoningMode;
import io.crewscope.domain.agent.AgentRuntimeRole;
import io.crewscope.domain.agent.AgentTemplateCapabilities;
import io.crewscope.domain.agent.AgentTemplateCapability;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.AgentTemplateHash;
import io.crewscope.domain.agent.AgentTemplateMemberConfiguration;
import io.crewscope.domain.agent.AgentTemplatePolicy;
import io.crewscope.domain.agent.AgentTemplatePublisherScope;
import io.crewscope.domain.agent.AgentTemplateStatus;
import io.crewscope.domain.agent.AgentTemplateVersion;
import io.crewscope.domain.agent.AgentToolKey;
import io.crewscope.domain.agent.SafeModelGenerateOptions;
import io.crewscope.domain.model.ModelAdapterKey;
import io.crewscope.domain.model.ModelBillingSubject;
import io.crewscope.domain.model.ModelCapability;
import io.crewscope.domain.model.ModelCatalogCoordinate;
import io.crewscope.domain.model.ModelCatalogEntry;
import io.crewscope.domain.model.ModelCatalogEntryId;
import io.crewscope.domain.model.ModelCatalogRevision;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionHealth;
import io.crewscope.domain.model.ModelConnectionHealthFailureCode;
import io.crewscope.domain.model.ModelConnectionHealthStatus;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelConnectionOwner;
import io.crewscope.domain.model.ModelConnectionOwnerType;
import io.crewscope.domain.model.ModelConnectionRevocationReason;
import io.crewscope.domain.model.ModelConnectionStatus;
import io.crewscope.domain.model.ModelCredentialBinding;
import io.crewscope.domain.model.ModelCredentialSubject;
import io.crewscope.domain.model.ModelCredentialVersion;
import io.crewscope.domain.model.ModelDataPolicy;
import io.crewscope.domain.model.ModelDataRetentionMode;
import io.crewscope.domain.model.ModelEndpoint;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.model.ModelPriceRevision;
import io.crewscope.domain.model.ModelPriceSource;
import io.crewscope.domain.model.ModelProviderDefinition;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelRegion;
import io.crewscope.domain.model.ModelRegistryHash;
import io.crewscope.domain.model.ModelRegistryStatus;
import io.crewscope.domain.model.ModelRevision;
import io.crewscope.domain.model.ModelSubjectType;
import io.crewscope.domain.model.ModelTokenPrice;
import io.crewscope.domain.model.ModelTrainingUsagePolicy;
import io.crewscope.domain.policy.PolicyPackId;
import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workspace.AgentProfile;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Component;

/** Canonical JDBC mapping for the V20 model and Agent configuration graph. */
@Component
public final class ModelAgentPersistenceMapper {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public ModelAgentPersistenceMapper(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** Encodes JSONB collections through the configured platform ObjectMapper. */
    public SqlParameterValue jsonb(Object value) {
        try {
            return new SqlParameterValue(
                    Types.OTHER, objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Unable to encode trusted model metadata", failure);
        }
    }

    public ModelProviderDefinition provider(ResultSet row) throws SQLException {
        return ModelProviderDefinition.reconstitute(
                new ModelProviderKey(row.getString("provider_key")),
                row.getString("display_name"),
                new ModelAdapterKey(row.getString("adapter_key")),
                new ModelEndpoint(row.getString("default_endpoint")),
                values(row, "available_regions", ModelRegion::new),
                dataPolicy(row),
                hash(row, "content_hash"),
                ModelRegistryStatus.valueOf(row.getString("status")),
                row.getLong("lifecycle_version"),
                audit(row));
    }

    public ModelCatalogEntry catalog(ResultSet row, ModelProviderDefinition provider)
            throws SQLException {
        return ModelCatalogEntry.reconstitute(
                provider,
                hash(row, "provider_definition_hash"),
                new ModelCatalogEntryId(row.getObject("id", UUID.class)),
                new ModelId(row.getString("model_id")),
                new ModelCatalogRevision(row.getLong("catalog_revision")),
                optionalLong(row, "previous_catalog_revision").map(ModelCatalogRevision::new),
                new ModelRevision(row.getString("model_revision")),
                row.getString("display_name"),
                row.getLong("context_window_tokens"),
                row.getLong("maximum_output_tokens"),
                values(row, "capabilities", ModelCapability::new),
                values(row, "available_regions", ModelRegion::new),
                hash(row, "content_hash"),
                ModelRegistryStatus.valueOf(row.getString("status")),
                row.getLong("lifecycle_version"),
                audit(row));
    }

    public ModelPriceRevision price(ResultSet row) throws SQLException {
        ModelCatalogCoordinate coordinate = new ModelCatalogCoordinate(
                new ModelCatalogEntryId(row.getObject("catalog_entry_id", UUID.class)),
                new ModelProviderKey(row.getString("provider_key")),
                new ModelId(row.getString("model_id")),
                new ModelCatalogRevision(row.getLong("catalog_revision")));
        return ModelPriceRevision.reconstitute(
                coordinate,
                row.getLong("price_revision"),
                timestamp(row, "effective_from"),
                new ModelTokenPrice(
                        row.getBigDecimal("input_per_million_tokens"),
                        row.getBigDecimal("output_per_million_tokens"),
                        Optional.ofNullable(row.getBigDecimal("cached_input_per_million_tokens")),
                        row.getString("currency_code")),
                new ModelPriceSource(row.getString("price_source")),
                hash(row, "content_hash"),
                immutableAudit(row));
    }

    public ModelConnection connection(ResultSet row, ModelProviderDefinition provider)
            throws SQLException {
        OrganizationId organizationId = new OrganizationId(row.getObject("organization_id", UUID.class));
        ModelConnectionOwner owner = owner(
                organizationId,
                row.getString("owner_type"),
                row.getObject("owner_id", UUID.class),
                row.getObject("owner_team_id", UUID.class),
                row.getObject("owner_user_principal_id", UUID.class));
        ModelCredentialSubject credentialSubject = subject(
                organizationId,
                row.getString("credential_subject_type"),
                row.getObject("credential_subject_id", UUID.class));
        ModelBillingSubject billingSubject = billingSubject(
                organizationId,
                row.getString("billing_subject_type"),
                row.getObject("billing_subject_id", UUID.class));
        ModelCredentialVersion credentialVersion = new ModelCredentialVersion(
                row.getLong("credential_version"));
        ModelConnectionHealth health = new ModelConnectionHealth(
                ModelConnectionHealthStatus.valueOf(row.getString("health_status")),
                new ModelCredentialVersion(row.getLong("health_credential_version")),
                optionalTimestamp(row, "health_checked_at"),
                optionalTimestamp(row, "last_healthy_at"),
                row.getInt("consecutive_failures"),
                Optional.ofNullable(row.getString("health_failure_code"))
                        .map(ModelConnectionHealthFailureCode::valueOf));
        return ModelConnection.reconstitute(
                provider,
                hash(row, "provider_definition_hash"),
                new ModelConnectionId(row.getObject("id", UUID.class)),
                organizationId,
                owner,
                new ModelEndpoint(row.getString("endpoint")),
                new ModelRegion(row.getString("region")),
                new ModelCredentialBinding(
                        new CredentialId(row.getObject("credential_id", UUID.class)),
                        credentialSubject,
                        credentialVersion),
                billingSubject,
                ModelConnectionStatus.valueOf(row.getString("status")),
                health,
                Optional.ofNullable(row.getString("revocation_reason"))
                        .map(ModelConnectionRevocationReason::valueOf),
                row.getLong("version"),
                audit(row));
    }

    public AgentTemplateDefinition template(ResultSet row) throws SQLException {
        OrganizationId organizationId = new OrganizationId(row.getObject("organization_id", UUID.class));
        Optional<TeamId> teamId = Optional.ofNullable(row.getObject("publisher_team_id", UUID.class))
                .map(TeamId::new);
        AgentTemplatePublisherScope scope = new AgentTemplatePublisherScope(organizationId, teamId);
        AgentTemplateVersion version = AgentTemplateVersion.of(
                row.getString("template_key"), row.getLong("template_version"));
        AgentTemplateCapabilities capabilities = AgentTemplateCapabilities.reconstitute(
                values(row, "declared_capabilities", AgentTemplateCapability::new),
                values(row, "required_model_capabilities", AgentTemplateCapability::new),
                new AgentTemplateHash(trim(row.getString("capability_hash"))));
        AgentTemplatePolicy policy = AgentTemplatePolicy.reconstitute(
                row.getString("system_prompt_baseline"),
                values(row, "allowed_tools", AgentToolKey::new),
                values(row, "approved_skill_keys", Function.identity()),
                Optional.ofNullable(row.getString("structured_output_schema")),
                enumValues(row, "member_configurable_slots", AgentConfigurableSlot.class),
                enumValues(row, "administrator_configurable_slots", AgentConfigurableSlot.class),
                new AgentTemplateHash(trim(row.getString("policy_hash"))));
        return AgentTemplateDefinition.reconstitute(
                scope,
                version,
                optionalLong(row, "previous_template_version")
                        .map(value -> new AgentTemplateVersion(version.key(), value)),
                AgentRuntimeRole.valueOf(row.getString("runtime_role")),
                enumValues(row, "allowed_ownership_types", AgentOwnershipType.class),
                enumValues(row, "allowed_execution_scopes", AgentExecutionScope.class),
                capabilities,
                policy,
                new AgentTemplateHash(trim(row.getString("content_hash"))),
                AgentTemplateStatus.valueOf(row.getString("status")),
                row.getLong("lifecycle_version"),
                audit(row));
    }

    ConfigurationHeader configurationHeader(ResultSet row) throws SQLException {
        return new ConfigurationHeader(
                AgentTemplateVersion.of(
                        row.getString("template_key"), row.getLong("template_version")),
                new AgentTemplateHash(trim(row.getString("template_content_hash"))),
                new AgentConfigurationRevision(row.getLong("configuration_revision")),
                optionalLong(row, "previous_configuration_revision")
                        .map(AgentConfigurationRevision::new),
                Optional.ofNullable(row.getObject("owner_user_principal_id", UUID.class))
                        .map(PrincipalId::new),
                new AgentTemplateMemberConfiguration(
                        Optional.ofNullable(row.getString("supplemental_instructions")),
                        values(row, "enabled_tools", AgentToolKey::new),
                        Optional.ofNullable(row.getString("structured_output_schema_hash"))
                                .map(String::trim)
                                .map(AgentTemplateHash::new)),
                values(row, "approved_skill_keys", Function.identity()),
                optionalPolicy(row, "memory_policy_id", "memory_policy_version", AgentMemoryPolicyReference::new),
                optionalPolicy(row, "budget_policy_id", "budget_policy_version", AgentBudgetPolicyReference::new),
                new PolicyPackReference(
                        new PolicyPackId(row.getObject("policy_pack_id", UUID.class)),
                        row.getLong("policy_pack_version")),
                new SafeModelGenerateOptions(
                        Optional.ofNullable(row.getBigDecimal("generate_temperature")),
                        Optional.ofNullable(row.getBigDecimal("generate_top_p")),
                        optionalLong(row, "generate_maximum_output_tokens"),
                        AgentReasoningMode.valueOf(row.getString("generate_reasoning_mode")),
                        row.getBoolean("generate_cache_enabled"),
                        row.getBoolean("generate_parallel_tool_calls"),
                        optionalLong(row, "generate_seed"),
                        row.getInt("generate_maximum_attempts")),
                new AgentConfigurationHash(trim(row.getString("configuration_hash"))),
                immutableAudit(row));
    }

    Optional<AgentExecutionModelBinding> configurationBinding(ResultSet row) throws SQLException {
        String scopeValue = row.getString("execution_scope");
        if (scopeValue == null) {
            return Optional.empty();
        }
        AgentExecutionScope scope = AgentExecutionScope.valueOf(scopeValue);
        AgentModelBindingKind kind = AgentModelBindingKind.valueOf(row.getString("binding_kind"));
        if (kind == AgentModelBindingKind.INHERIT_TEAM_DEFAULT) {
            return Optional.of(AgentExecutionModelBinding.inheritTeamDefault());
        }
        if (kind == AgentModelBindingKind.ORCHESTRATION_ONLY) {
            return Optional.of(AgentExecutionModelBinding.orchestrationOnly());
        }
        return Optional.of(AgentExecutionModelBinding.direct(
                scope,
                new AgentDirectModelBinding(
                        selection(row, "primary"),
                        nullableSelection(row, "fallback"))));
    }

    AgentConfigurationVersion configuration(
            AgentProfile profile,
            AgentTemplateDefinition template,
            ConfigurationHeader header,
            List<AgentExecutionModelBinding> bindings) {
        Optional<AgentExecutionModelBinding> personal = bindings.stream()
                .filter(value -> value.executionScope() == AgentExecutionScope.PERSONAL)
                .findFirst();
        Optional<AgentExecutionModelBinding> team = bindings.stream()
                .filter(value -> value.executionScope() == AgentExecutionScope.TEAM)
                .findFirst();
        return AgentConfigurationVersion.reconstitute(
                profile,
                template,
                header.ownerUserPrincipalId(),
                header.revision(),
                header.previousRevision(),
                personal,
                team,
                header.templateConfiguration(),
                header.approvedSkillKeys(),
                header.memoryPolicy(),
                header.budgetPolicy(),
                header.policyPack(),
                header.generateOptions(),
                header.configurationHash(),
                header.audit());
    }

    public AgentModelDefault modelDefault(ResultSet row, AgentTemplateDefinition template)
            throws SQLException {
        OrganizationId organizationId = new OrganizationId(row.getObject("organization_id", UUID.class));
        AgentModelDefaultScope scope = new AgentModelDefaultScope(
                organizationId,
                Optional.ofNullable(row.getObject("team_id", UUID.class)).map(TeamId::new));
        return AgentModelDefault.reconstitute(
                template,
                scope,
                AgentExecutionScope.valueOf(row.getString("execution_scope")),
                new AgentModelDefaultRevision(row.getLong("default_revision")),
                optionalLong(row, "previous_default_revision").map(AgentModelDefaultRevision::new),
                new AgentDirectModelBinding(
                        selection(row, "primary"), nullableSelection(row, "fallback")),
                new PolicyPackReference(
                        new PolicyPackId(row.getObject("policy_pack_id", UUID.class)),
                        row.getLong("policy_pack_version")),
                new AgentConfigurationHash(trim(row.getString("content_hash"))),
                immutableAudit(row));
    }

    private AgentModelSelection selection(ResultSet row, String prefix) throws SQLException {
        OrganizationId organizationId = new OrganizationId(row.getObject("organization_id", UUID.class));
        ModelProviderKey providerKey = new ModelProviderKey(row.getString(prefix + "_provider_key"));
        return new AgentModelSelection(
                organizationId,
                new ModelConnectionId(row.getObject(prefix + "_connection_id", UUID.class)),
                owner(
                        organizationId,
                        row.getString(prefix + "_owner_type"),
                        row.getObject(prefix + "_owner_id", UUID.class),
                        row.getObject(prefix + "_owner_team_id", UUID.class),
                        row.getObject(prefix + "_owner_user_principal_id", UUID.class)),
                providerKey,
                new ModelRegistryHash(trim(row.getString(prefix + "_provider_definition_hash"))),
                new ModelCatalogCoordinate(
                        new ModelCatalogEntryId(row.getObject(prefix + "_catalog_entry_id", UUID.class)),
                        providerKey,
                        new ModelId(row.getString(prefix + "_model_id")),
                        new ModelCatalogRevision(row.getLong(prefix + "_catalog_revision"))),
                new ModelRegistryHash(trim(row.getString(prefix + "_catalog_content_hash"))));
    }

    private Optional<AgentModelSelection> nullableSelection(ResultSet row, String prefix)
            throws SQLException {
        return row.getObject(prefix + "_connection_id") == null
                ? Optional.empty()
                : Optional.of(selection(row, prefix));
    }

    private ModelDataPolicy dataPolicy(ResultSet row) throws SQLException {
        ModelDataRetentionMode mode = ModelDataRetentionMode.valueOf(row.getString("retention_mode"));
        Optional<Duration> duration = optionalLong(row, "maximum_retention_seconds")
                .map(Duration::ofSeconds);
        return new ModelDataPolicy(
                mode,
                duration,
                ModelTrainingUsagePolicy.valueOf(row.getString("training_usage_policy")));
    }

    private static ModelConnectionOwner owner(
            OrganizationId organizationId,
            String type,
            UUID ownerId,
            UUID teamId,
            UUID principalId) {
        return new ModelConnectionOwner(
                organizationId,
                ModelConnectionOwnerType.valueOf(type),
                ownerId,
                Optional.ofNullable(teamId).map(TeamId::new),
                Optional.ofNullable(principalId).map(PrincipalId::new));
    }

    private static ModelCredentialSubject subject(
            OrganizationId organizationId, String type, UUID subjectId) {
        ModelSubjectType subjectType = ModelSubjectType.valueOf(type);
        return new ModelCredentialSubject(
                organizationId,
                subjectType,
                subjectId,
                subjectType == ModelSubjectType.TEAM
                        ? Optional.of(new TeamId(subjectId))
                        : Optional.empty(),
                subjectType == ModelSubjectType.PRINCIPAL
                        ? Optional.of(new PrincipalId(subjectId))
                        : Optional.empty());
    }

    private static ModelBillingSubject billingSubject(
            OrganizationId organizationId, String type, UUID subjectId) {
        ModelSubjectType subjectType = ModelSubjectType.valueOf(type);
        return new ModelBillingSubject(
                organizationId,
                subjectType,
                subjectId,
                subjectType == ModelSubjectType.TEAM
                        ? Optional.of(new TeamId(subjectId))
                        : Optional.empty(),
                subjectType == ModelSubjectType.PRINCIPAL
                        ? Optional.of(new PrincipalId(subjectId))
                        : Optional.empty());
    }

    private <T> Set<T> values(ResultSet row, String column, Function<String, T> factory)
            throws SQLException {
        try {
            List<String> values = objectMapper.readValue(row.getString(column), STRING_LIST);
            return values.stream().map(factory).collect(Collectors.toUnmodifiableSet());
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Invalid trusted JSONB collection " + column, failure);
        }
    }

    private <E extends Enum<E>> Set<E> enumValues(
            ResultSet row, String column, Class<E> enumType) throws SQLException {
        return values(row, column, value -> Enum.valueOf(enumType, value));
    }

    private static <T> Optional<T> optionalPolicy(
            ResultSet row,
            String idColumn,
            String versionColumn,
            PolicyReferenceFactory<T> factory) throws SQLException {
        UUID id = row.getObject(idColumn, UUID.class);
        return id == null ? Optional.empty() : Optional.of(factory.create(id, row.getLong(versionColumn)));
    }

    private static AuditMetadata audit(ResultSet row) throws SQLException {
        return new AuditMetadata(
                Optional.of(new PrincipalId(row.getObject("created_by_principal_id", UUID.class))),
                timestamp(row, "created_at"),
                Optional.of(new PrincipalId(row.getObject("updated_by_principal_id", UUID.class))),
                timestamp(row, "updated_at"));
    }

    private static AuditMetadata immutableAudit(ResultSet row) throws SQLException {
        PrincipalId createdBy = new PrincipalId(row.getObject("created_by_principal_id", UUID.class));
        UtcTimestamp createdAt = timestamp(row, "created_at");
        return AuditMetadata.createdBy(createdBy, createdAt);
    }

    private static UtcTimestamp timestamp(ResultSet row, String column) throws SQLException {
        return UtcTimestamp.from(row.getObject(column, OffsetDateTime.class).toInstant());
    }

    private static Optional<UtcTimestamp> optionalTimestamp(ResultSet row, String column)
            throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return Optional.ofNullable(value).map(OffsetDateTime::toInstant).map(UtcTimestamp::from);
    }

    private static Optional<Long> optionalLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? Optional.empty() : Optional.of(value);
    }

    private static ModelRegistryHash hash(ResultSet row, String column) throws SQLException {
        return new ModelRegistryHash(trim(row.getString(column)));
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    record ConfigurationHeader(
            AgentTemplateVersion templateVersion,
            AgentTemplateHash templateContentHash,
            AgentConfigurationRevision revision,
            Optional<AgentConfigurationRevision> previousRevision,
            Optional<PrincipalId> ownerUserPrincipalId,
            AgentTemplateMemberConfiguration templateConfiguration,
            Set<String> approvedSkillKeys,
            Optional<AgentMemoryPolicyReference> memoryPolicy,
            Optional<AgentBudgetPolicyReference> budgetPolicy,
            PolicyPackReference policyPack,
            SafeModelGenerateOptions generateOptions,
            AgentConfigurationHash configurationHash,
            AuditMetadata audit) {}

    @FunctionalInterface
    private interface PolicyReferenceFactory<T> {
        T create(UUID id, long version);
    }
}
