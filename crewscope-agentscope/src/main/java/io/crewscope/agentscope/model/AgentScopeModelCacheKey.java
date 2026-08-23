package io.crewscope.agentscope.model;

import io.crewscope.domain.agent.AgentConfigurationHash;
import io.crewscope.domain.model.ModelAdapterKey;
import io.crewscope.domain.model.ModelCatalogCoordinate;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelCredentialVersion;
import io.crewscope.domain.model.ModelRegistryHash;
import io.crewscope.domain.model.ModelRevision;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;

/** Non-secret identity of one safely reusable connection-scoped model instance. */
public record AgentScopeModelCacheKey(
        OrganizationId organizationId,
        ModelConnectionId connectionId,
        long connectionVersion,
        ModelCredentialVersion credentialVersion,
        ModelRegistryHash providerDefinitionHash,
        ModelCatalogCoordinate catalogCoordinate,
        ModelRegistryHash catalogContentHash,
        ModelRevision modelRevision,
        ModelAdapterKey adapterKey,
        String adapterVersion,
        AgentScopeFormatterPolicy formatterPolicy,
        StructuredOutputCompatibility structuredOutputCompatibility,
        String endpointHash,
        AgentConfigurationHash compatibilityHash,
        String safeGenerateOptionsHash) {

    public AgentScopeModelCacheKey {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(credentialVersion, "credentialVersion");
        Objects.requireNonNull(providerDefinitionHash, "providerDefinitionHash");
        Objects.requireNonNull(catalogCoordinate, "catalogCoordinate");
        Objects.requireNonNull(catalogContentHash, "catalogContentHash");
        Objects.requireNonNull(modelRevision, "modelRevision");
        Objects.requireNonNull(adapterKey, "adapterKey");
        Objects.requireNonNull(adapterVersion, "adapterVersion");
        Objects.requireNonNull(formatterPolicy, "formatterPolicy");
        Objects.requireNonNull(structuredOutputCompatibility, "structuredOutputCompatibility");
        Objects.requireNonNull(endpointHash, "endpointHash");
        Objects.requireNonNull(compatibilityHash, "compatibilityHash");
        Objects.requireNonNull(safeGenerateOptionsHash, "safeGenerateOptionsHash");
    }
}
