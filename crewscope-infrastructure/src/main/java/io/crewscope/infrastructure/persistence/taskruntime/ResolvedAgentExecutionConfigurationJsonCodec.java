package io.crewscope.infrastructure.persistence.taskruntime;

import io.crewscope.domain.agent.AgentConfigurationHash;
import io.crewscope.domain.agent.AgentConfigurationRevision;
import io.crewscope.domain.agent.AgentExecutionScope;
import io.crewscope.domain.agent.AgentModelBindingSource;
import io.crewscope.domain.agent.AgentModelDefaultRevision;
import io.crewscope.domain.agent.AgentModelDefaultScope;
import io.crewscope.domain.agent.AgentOwnership;
import io.crewscope.domain.agent.AgentOwnershipType;
import io.crewscope.domain.agent.AgentTemplateHash;
import io.crewscope.domain.agent.AgentTemplateVersion;
import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.agent.ResolvedAgentModelDefault;
import io.crewscope.domain.agent.ResolvedModelRole;
import io.crewscope.domain.agent.ResolvedModelSelection;
import io.crewscope.domain.model.ModelAdapterKey;
import io.crewscope.domain.model.ModelCatalogCoordinate;
import io.crewscope.domain.model.ModelCatalogEntryId;
import io.crewscope.domain.model.ModelCatalogRevision;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelConnectionOwner;
import io.crewscope.domain.model.ModelConnectionOwnerType;
import io.crewscope.domain.model.ModelCredentialVersion;
import io.crewscope.domain.model.ModelDataPolicy;
import io.crewscope.domain.model.ModelDataRetentionMode;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelRegion;
import io.crewscope.domain.model.ModelRegistryHash;
import io.crewscope.domain.model.ModelRevision;
import io.crewscope.domain.model.ModelTokenPrice;
import io.crewscope.domain.model.ModelTrainingUsagePolicy;
import io.crewscope.domain.policy.PolicyPackId;
import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workspace.AgentProfileId;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Explicit non-secret JSONB codec for PolicySnapshot Schema v2 execution coordinates. */
@Component
final class ResolvedAgentExecutionConfigurationJsonCodec {

    Map<String, Object> encode(ResolvedAgentExecutionConfiguration value) {
        ResolvedAgentExecutionConfiguration required = Objects.requireNonNull(value, "value");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentProfileId", required.agentProfileId().toString());
        result.put("agentProfileVersion", required.agentProfileVersion());
        result.put("agentPrincipalId", required.agentPrincipalId().toString());
        result.put("ownership", encodeOwnership(required.ownership()));
        result.put("templateKey", required.templateVersion().key().value());
        result.put("templateVersion", required.templateVersion().version());
        result.put("templateContentHash", required.templateContentHash().value());
        result.put("configurationRevision", required.configurationRevision().value());
        result.put("configurationHash", required.configurationHash().value());
        result.put("executionScope", required.executionScope().name());
        result.put("bindingSource", required.bindingSource().name());
        result.put("modelDefault", required.modelDefault().map(this::encodeDefault).orElse(null));
        result.put("primary", encodeSelection(required.primary()));
        result.put("fallback", required.fallback().map(this::encodeSelection).orElse(null));
        result.put("promptHash", required.promptHash().value());
        result.put("toolHash", required.toolHash().value());
        result.put("skillHash", required.skillHash().value());
        result.put("structuredOutputSchemaHash", required.structuredOutputSchemaHash()
                .map(AgentTemplateHash::value).orElse(null));
        result.put("templatePolicyHash", required.templatePolicyHash().value());
        result.put("configurationPolicyPackId", required.configurationPolicyPack().id().toString());
        result.put("configurationPolicyPackVersion", required.configurationPolicyPack().version());
        result.put("resolutionHash", required.resolutionHash().value());
        return java.util.Collections.unmodifiableMap(result);
    }

    ResolvedAgentExecutionConfiguration decode(Map<String, Object> source) {
        Map<String, Object> value = requiredMap(source, "agentExecutionConfiguration");
        return new ResolvedAgentExecutionConfiguration(
                new AgentProfileId(uuid(value, "agentProfileId")),
                number(value, "agentProfileVersion"),
                new PrincipalId(uuid(value, "agentPrincipalId")),
                decodeOwnership(map(value, "ownership")),
                AgentTemplateVersion.of(text(value, "templateKey"), number(value, "templateVersion")),
                new AgentTemplateHash(text(value, "templateContentHash")),
                new AgentConfigurationRevision(number(value, "configurationRevision")),
                new AgentConfigurationHash(text(value, "configurationHash")),
                AgentExecutionScope.valueOf(text(value, "executionScope")),
                AgentModelBindingSource.valueOf(text(value, "bindingSource")),
                optionalMap(value, "modelDefault").map(this::decodeDefault),
                decodeSelection(map(value, "primary")),
                optionalMap(value, "fallback").map(this::decodeSelection),
                new AgentTemplateHash(text(value, "promptHash")),
                new AgentTemplateHash(text(value, "toolHash")),
                new AgentTemplateHash(text(value, "skillHash")),
                optionalText(value, "structuredOutputSchemaHash").map(AgentTemplateHash::new),
                new AgentTemplateHash(text(value, "templatePolicyHash")),
                new PolicyPackReference(
                        new PolicyPackId(uuid(value, "configurationPolicyPackId")),
                        number(value, "configurationPolicyPackVersion")),
                new AgentConfigurationHash(text(value, "resolutionHash")));
    }

    private Map<String, Object> encodeDefault(ResolvedAgentModelDefault value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", value.source().name());
        result.put("organizationId", value.scope().organizationId().toString());
        result.put("teamId", value.scope().teamId().map(Object::toString).orElse(null));
        result.put("revision", value.revision().value());
        result.put("contentHash", value.contentHash().value());
        result.put("policyPackId", value.policyPack().id().toString());
        result.put("policyPackVersion", value.policyPack().version());
        return result;
    }

    private ResolvedAgentModelDefault decodeDefault(Map<String, Object> value) {
        OrganizationId organizationId = new OrganizationId(uuid(value, "organizationId"));
        return new ResolvedAgentModelDefault(
                AgentModelBindingSource.valueOf(text(value, "source")),
                new AgentModelDefaultScope(
                        organizationId,
                        optionalText(value, "teamId").map(UUID::fromString).map(TeamId::new)),
                new AgentModelDefaultRevision(number(value, "revision")),
                new AgentConfigurationHash(text(value, "contentHash")),
                new PolicyPackReference(
                        new PolicyPackId(uuid(value, "policyPackId")),
                        number(value, "policyPackVersion")));
    }

    private Map<String, Object> encodeSelection(ResolvedModelSelection value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", value.role().name());
        result.put("providerKey", value.providerKey().value());
        result.put("providerDefinitionHash", value.providerDefinitionHash().value());
        result.put("adapterKey", value.adapterKey().value());
        result.put("retentionMode", value.dataPolicy().retentionMode().name());
        result.put("maximumRetentionSeconds", value.dataPolicy().maximumRetention()
                .map(Duration::getSeconds).orElse(null));
        result.put("trainingUsagePolicy", value.dataPolicy().trainingUsagePolicy().name());
        result.put("connectionId", value.connectionId().toString());
        result.put("connectionVersion", value.connectionVersion());
        result.put("connectionOwner", encodeOwner(value.connectionOwner()));
        result.put("credentialVersion", value.credentialVersion().value());
        result.put("region", value.region().value());
        result.put("catalogEntryId", value.catalogCoordinate().entryId().toString());
        result.put("catalogProviderKey", value.catalogCoordinate().providerKey().value());
        result.put("modelId", value.catalogCoordinate().modelId().value());
        result.put("catalogRevision", value.catalogCoordinate().catalogRevision().value());
        result.put("catalogContentHash", value.catalogContentHash().value());
        result.put("modelRevision", value.modelRevision().value());
        result.put("priceRevision", value.priceRevision());
        result.put("inputPrice", value.tokenPrice().inputPerMillionTokens().toPlainString());
        result.put("outputPrice", value.tokenPrice().outputPerMillionTokens().toPlainString());
        result.put("cachedInputPrice", value.tokenPrice().cachedInputPerMillionTokens()
                .map(BigDecimal::toPlainString).orElse(null));
        result.put("currencyCode", value.tokenPrice().currencyCode());
        result.put("priceContentHash", value.priceContentHash().value());
        result.put("resolutionHash", value.resolutionHash().value());
        return result;
    }

    private ResolvedModelSelection decodeSelection(Map<String, Object> value) {
        ModelProviderKey providerKey = new ModelProviderKey(text(value, "providerKey"));
        return new ResolvedModelSelection(
                ResolvedModelRole.valueOf(text(value, "role")),
                providerKey,
                new ModelRegistryHash(text(value, "providerDefinitionHash")),
                new ModelAdapterKey(text(value, "adapterKey")),
                new ModelDataPolicy(
                        ModelDataRetentionMode.valueOf(text(value, "retentionMode")),
                        optionalNumber(value, "maximumRetentionSeconds").map(Duration::ofSeconds),
                        ModelTrainingUsagePolicy.valueOf(text(value, "trainingUsagePolicy"))),
                new ModelConnectionId(uuid(value, "connectionId")),
                number(value, "connectionVersion"),
                decodeOwner(map(value, "connectionOwner")),
                new ModelCredentialVersion(number(value, "credentialVersion")),
                new ModelRegion(text(value, "region")),
                new ModelCatalogCoordinate(
                        new ModelCatalogEntryId(uuid(value, "catalogEntryId")),
                        new ModelProviderKey(text(value, "catalogProviderKey")),
                        new ModelId(text(value, "modelId")),
                        new ModelCatalogRevision(number(value, "catalogRevision"))),
                new ModelRegistryHash(text(value, "catalogContentHash")),
                new ModelRevision(text(value, "modelRevision")),
                number(value, "priceRevision"),
                new ModelTokenPrice(
                        new BigDecimal(text(value, "inputPrice")),
                        new BigDecimal(text(value, "outputPrice")),
                        optionalText(value, "cachedInputPrice").map(BigDecimal::new),
                        text(value, "currencyCode")),
                new ModelRegistryHash(text(value, "priceContentHash")),
                new AgentConfigurationHash(text(value, "resolutionHash")));
    }

    private static Map<String, Object> encodeOwnership(AgentOwnership value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", value.type().name());
        result.put("organizationId", value.organizationId().toString());
        result.put("teamId", value.teamId().map(Object::toString).orElse(null));
        result.put("ownerMemberId", value.ownerMemberId().map(Object::toString).orElse(null));
        return result;
    }

    private static AgentOwnership decodeOwnership(Map<String, Object> value) {
        return new AgentOwnership(
                AgentOwnershipType.valueOf(text(value, "type")),
                new OrganizationId(uuid(value, "organizationId")),
                optionalText(value, "teamId").map(UUID::fromString).map(TeamId::new),
                optionalText(value, "ownerMemberId").map(UUID::fromString).map(TeamMemberId::new));
    }

    private static Map<String, Object> encodeOwner(ModelConnectionOwner value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("organizationId", value.organizationId().toString());
        result.put("type", value.type().name());
        result.put("ownerId", value.ownerId().toString());
        result.put("teamId", value.teamId().map(Object::toString).orElse(null));
        result.put("userPrincipalId", value.userPrincipalId().map(Object::toString).orElse(null));
        return result;
    }

    private static ModelConnectionOwner decodeOwner(Map<String, Object> value) {
        return new ModelConnectionOwner(
                new OrganizationId(uuid(value, "organizationId")),
                ModelConnectionOwnerType.valueOf(text(value, "type")),
                uuid(value, "ownerId"),
                optionalText(value, "teamId").map(UUID::fromString).map(TeamId::new),
                optionalText(value, "userPrincipalId").map(UUID::fromString).map(PrincipalId::new));
    }

    private static Map<String, Object> map(Map<String, Object> source, String key) {
        return requiredMap(source.get(key), key);
    }

    private static Optional<Map<String, Object>> optionalMap(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value == null ? Optional.empty() : Optional.of(requiredMap(value, key));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requiredMap(Object value, String key) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException("Missing or invalid PolicySnapshot field " + key);
        }
        return (Map<String, Object>) map;
    }

    private static String text(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("Missing or invalid PolicySnapshot field " + key);
        }
        return text;
    }

    private static Optional<String> optionalText(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value == null ? Optional.empty() : Optional.of(text(source, key));
    }

    private static long number(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("Missing or invalid PolicySnapshot field " + key);
        }
        return number.longValue();
    }

    private static Optional<Long> optionalNumber(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value == null ? Optional.empty() : Optional.of(number(source, key));
    }

    private static UUID uuid(Map<String, Object> source, String key) {
        return UUID.fromString(text(source, key));
    }
}
