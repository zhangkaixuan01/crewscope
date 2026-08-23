package io.crewscope.domain.agent;

import io.crewscope.domain.model.ModelCatalogCoordinate;
import io.crewscope.domain.model.ModelCatalogEntry;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionHealthStatus;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelConnectionOwner;
import io.crewscope.domain.model.ModelConnectionStatus;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelRegistryHash;
import io.crewscope.domain.model.ModelRegistryStatus;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;

/** Non-secret snapshot of one Connection and exact model catalog revision selected by a config. */
public record AgentModelSelection(
        OrganizationId organizationId,
        ModelConnectionId connectionId,
        ModelConnectionOwner connectionOwner,
        ModelProviderKey providerKey,
        ModelRegistryHash providerDefinitionHash,
        ModelCatalogCoordinate catalogCoordinate,
        ModelRegistryHash catalogContentHash) {

    public AgentModelSelection {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        connectionId = Objects.requireNonNull(connectionId, "connectionId");
        connectionOwner = Objects.requireNonNull(connectionOwner, "connectionOwner");
        providerKey = Objects.requireNonNull(providerKey, "providerKey");
        providerDefinitionHash = Objects.requireNonNull(
                providerDefinitionHash, "providerDefinitionHash");
        catalogCoordinate = Objects.requireNonNull(catalogCoordinate, "catalogCoordinate");
        catalogContentHash = Objects.requireNonNull(catalogContentHash, "catalogContentHash");
        if (!organizationId.equals(connectionOwner.organizationId())) {
            throw new DomainValidationException(
                    "agentConfiguration.modelSelection.connectionOwner",
                    "must belong to the selected Organization");
        }
        if (!providerKey.equals(catalogCoordinate.providerKey())) {
            throw new DomainValidationException(
                    "agentConfiguration.modelSelection.providerKey",
                    "must match the exact model catalog coordinate");
        }
    }

    /** Captures only trusted, non-secret facts from a currently usable selection. */
    public static AgentModelSelection capture(
            ModelConnection connection, ModelCatalogEntry catalogEntry) {
        ModelConnection requiredConnection = Objects.requireNonNull(connection, "connection");
        ModelCatalogEntry requiredCatalog = Objects.requireNonNull(catalogEntry, "catalogEntry");
        if (requiredConnection.status() != ModelConnectionStatus.ACTIVE
                || requiredConnection.health().status() != ModelConnectionHealthStatus.HEALTHY
                || !requiredConnection.health().isHealthyFor(
                        requiredConnection.credentialBinding().credentialVersion())) {
            throw new DomainValidationException(
                    "agentConfiguration.modelSelection.connection",
                    "must be ACTIVE and HEALTHY for its current credential version");
        }
        if (requiredCatalog.status() != ModelRegistryStatus.ACTIVE) {
            throw new DomainValidationException(
                    "agentConfiguration.modelSelection.catalogEntry",
                    "must reference an ACTIVE model catalog revision");
        }
        if (!requiredConnection.providerKey().equals(requiredCatalog.providerKey())
                || !requiredConnection
                        .providerDefinitionHash()
                        .equals(requiredCatalog.providerDefinitionHash())) {
            throw new DomainValidationException(
                    "agentConfiguration.modelSelection.providerKey",
                    "Connection and Catalog must reference the same provider definition");
        }
        if (!requiredCatalog.availableRegions().contains(requiredConnection.region())) {
            throw new DomainValidationException(
                    "agentConfiguration.modelSelection.region",
                    "Connection Region must be available on the catalog revision");
        }
        return new AgentModelSelection(
                requiredConnection.organizationId(),
                requiredConnection.id(),
                requiredConnection.owner(),
                requiredConnection.providerKey(),
                requiredConnection.providerDefinitionHash(),
                requiredCatalog.coordinate(),
                requiredCatalog.contentHash());
    }

    boolean sameTarget(AgentModelSelection other) {
        AgentModelSelection required = Objects.requireNonNull(other, "other");
        return connectionId.equals(required.connectionId)
                && catalogCoordinate.equals(required.catalogCoordinate);
    }

    void appendCanonical(StringBuilder target, String role) {
        AgentConfigurationHash.append(target, role);
        AgentConfigurationHash.append(target, organizationId.toString());
        AgentConfigurationHash.append(target, connectionId.toString());
        AgentConfigurationHash.append(target, connectionOwner.type().name());
        AgentConfigurationHash.append(target, connectionOwner.ownerId().toString());
        AgentConfigurationHash.append(target, providerKey.toString());
        AgentConfigurationHash.append(target, providerDefinitionHash.toString());
        AgentConfigurationHash.append(target, catalogCoordinate.entryId().toString());
        AgentConfigurationHash.append(target, catalogCoordinate.providerKey().toString());
        AgentConfigurationHash.append(target, catalogCoordinate.modelId().toString());
        AgentConfigurationHash.append(target, catalogCoordinate.catalogRevision().toString());
        AgentConfigurationHash.append(target, catalogContentHash.toString());
    }
}
