package io.crewscope.agentscope.model;

import io.crewscope.domain.agent.AgentConfigurationHash;
import io.crewscope.domain.agent.ResolvedModelRole;
import io.crewscope.domain.agent.SafeModelGenerateOptions;
import io.crewscope.domain.model.ModelAdapterKey;
import io.crewscope.domain.model.ModelCatalogCoordinate;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelCredentialVersion;
import io.crewscope.domain.model.ModelEndpoint;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelRegistryHash;
import io.crewscope.domain.model.ModelRevision;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;

/** Complete trusted, non-secret coordinate used to build one connection-scoped model. */
public record TrustedModelBuildRequest(
        OrganizationId organizationId,
        ResolvedModelRole role,
        ModelProviderKey providerKey,
        ModelRegistryHash providerDefinitionHash,
        ModelAdapterKey adapterKey,
        ModelConnectionId connectionId,
        long connectionVersion,
        ModelCredentialVersion credentialVersion,
        ModelEndpoint endpoint,
        String endpointPath,
        ModelCatalogCoordinate catalogCoordinate,
        ModelRegistryHash catalogContentHash,
        ModelRevision modelRevision,
        AgentScopeFormatterPolicy formatterPolicy,
        StructuredOutputCompatibility structuredOutputCompatibility,
        SafeModelGenerateOptions generateOptions,
        AgentConfigurationHash compatibilityHash) {

    public TrustedModelBuildRequest {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        role = Objects.requireNonNull(role, "role");
        providerKey = Objects.requireNonNull(providerKey, "providerKey");
        providerDefinitionHash = Objects.requireNonNull(
                providerDefinitionHash, "providerDefinitionHash");
        adapterKey = Objects.requireNonNull(adapterKey, "adapterKey");
        connectionId = Objects.requireNonNull(connectionId, "connectionId");
        credentialVersion = Objects.requireNonNull(credentialVersion, "credentialVersion");
        endpoint = Objects.requireNonNull(endpoint, "endpoint");
        catalogCoordinate = Objects.requireNonNull(catalogCoordinate, "catalogCoordinate");
        catalogContentHash = Objects.requireNonNull(catalogContentHash, "catalogContentHash");
        modelRevision = Objects.requireNonNull(modelRevision, "modelRevision");
        formatterPolicy = Objects.requireNonNull(formatterPolicy, "formatterPolicy");
        structuredOutputCompatibility = Objects.requireNonNull(
                structuredOutputCompatibility, "structuredOutputCompatibility");
        generateOptions = Objects.requireNonNull(generateOptions, "generateOptions");
        compatibilityHash = Objects.requireNonNull(compatibilityHash, "compatibilityHash");
        if (connectionVersion < 0 || !catalogCoordinate.providerKey().equals(providerKey)) {
            throw new AgentScopeModelBuildException(
                    AgentScopeModelBuildException.Code.INVALID_TRUSTED_REQUEST);
        }
        endpointPath = normalizeEndpointPath(endpointPath);
    }

    public String modelName() {
        return catalogCoordinate.modelId().value();
    }

    private static String normalizeEndpointPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "/v1/chat/completions";
        }
        String path = rawPath.strip();
        if (!path.startsWith("/")
                || path.startsWith("//")
                || path.length() > 256
                || path.contains("?")
                || path.contains("#")
                || path.contains("\\")) {
            throw new AgentScopeModelBuildException(
                    AgentScopeModelBuildException.Code.INVALID_TRUSTED_REQUEST);
        }
        return path;
    }

    @Override
    public String toString() {
        return "TrustedModelBuildRequest[organizationId=" + organizationId
                + ", role=" + role
                + ", providerKey=" + providerKey
                + ", adapterKey=" + adapterKey
                + ", connectionId=" + connectionId
                + ", connectionVersion=" + connectionVersion
                + ", credentialVersion=" + credentialVersion
                + ", catalogCoordinate=" + catalogCoordinate
                + ", modelRevision=" + modelRevision
                + ", endpoint=REDACTED]";
    }
}
