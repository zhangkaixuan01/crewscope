package io.crewscope.domain.agent;

import io.crewscope.domain.model.ModelAdapterKey;
import io.crewscope.domain.model.ModelCatalogCoordinate;
import io.crewscope.domain.model.ModelCatalogEntry;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionHealthStatus;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelConnectionOwner;
import io.crewscope.domain.model.ModelConnectionOwnerType;
import io.crewscope.domain.model.ModelConnectionStatus;
import io.crewscope.domain.model.ModelCredentialVersion;
import io.crewscope.domain.model.ModelDataPolicy;
import io.crewscope.domain.model.ModelDataRetentionMode;
import io.crewscope.domain.model.ModelPriceRevision;
import io.crewscope.domain.model.ModelProviderDefinition;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelRegion;
import io.crewscope.domain.model.ModelRegistryHash;
import io.crewscope.domain.model.ModelRegistryStatus;
import io.crewscope.domain.model.ModelRevision;
import io.crewscope.domain.model.ModelTokenPrice;
import io.crewscope.domain.model.ModelTrainingUsagePolicy;
import io.crewscope.domain.shared.id.PrincipalId;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Complete non-secret runtime coordinate for one independently preflighted model role. */
public record ResolvedModelSelection(
        ResolvedModelRole role,
        ModelProviderKey providerKey,
        ModelRegistryHash providerDefinitionHash,
        ModelAdapterKey adapterKey,
        ModelDataPolicy dataPolicy,
        ModelConnectionId connectionId,
        long connectionVersion,
        ModelConnectionOwner connectionOwner,
        ModelCredentialVersion credentialVersion,
        ModelRegion region,
        ModelCatalogCoordinate catalogCoordinate,
        ModelRegistryHash catalogContentHash,
        ModelRevision modelRevision,
        long priceRevision,
        ModelTokenPrice tokenPrice,
        ModelRegistryHash priceContentHash,
        AgentConfigurationHash resolutionHash) {

    public ResolvedModelSelection {
        role = Objects.requireNonNull(role, "role");
        providerKey = Objects.requireNonNull(providerKey, "providerKey");
        providerDefinitionHash = Objects.requireNonNull(
                providerDefinitionHash, "providerDefinitionHash");
        adapterKey = Objects.requireNonNull(adapterKey, "adapterKey");
        dataPolicy = Objects.requireNonNull(dataPolicy, "dataPolicy");
        connectionId = Objects.requireNonNull(connectionId, "connectionId");
        if (connectionVersion < 0 || priceRevision < 1) {
            throw new AgentModelPreflightException(
                    AgentModelPreflightRejectionCode.COORDINATE_MISMATCH);
        }
        connectionOwner = Objects.requireNonNull(connectionOwner, "connectionOwner");
        credentialVersion = Objects.requireNonNull(credentialVersion, "credentialVersion");
        region = Objects.requireNonNull(region, "region");
        catalogCoordinate = Objects.requireNonNull(catalogCoordinate, "catalogCoordinate");
        catalogContentHash = Objects.requireNonNull(catalogContentHash, "catalogContentHash");
        modelRevision = Objects.requireNonNull(modelRevision, "modelRevision");
        tokenPrice = Objects.requireNonNull(tokenPrice, "tokenPrice");
        priceContentHash = Objects.requireNonNull(priceContentHash, "priceContentHash");
        AgentConfigurationHash expected = calculateHash(
                role,
                providerKey,
                providerDefinitionHash,
                adapterKey,
                dataPolicy,
                connectionId,
                connectionVersion,
                connectionOwner,
                credentialVersion,
                region,
                catalogCoordinate,
                catalogContentHash,
                modelRevision,
                priceRevision,
                tokenPrice,
                priceContentHash);
        if (!expected.equals(Objects.requireNonNull(resolutionHash, "resolutionHash"))) {
            throw new AgentModelPreflightException(
                    AgentModelPreflightRejectionCode.COORDINATE_MISMATCH);
        }
    }

    /** Resolves and validates one role without consulting or widening to another candidate. */
    public static ResolvedModelSelection resolve(
            ResolvedModelRole role,
            AgentModelSelection configuredSelection,
            ModelProviderDefinition provider,
            ModelConnection connection,
            ModelCatalogEntry catalog,
            ModelPriceRevision price,
            AgentOwnership agentOwnership,
            Optional<PrincipalId> ownerUserPrincipalId,
            AgentExecutionScope executionScope,
            AgentModelPolicyConstraints policy,
            AgentExecutionAuthorizationFacts authorization) {
        ResolvedModelRole requiredRole = Objects.requireNonNull(role, "role");
        AgentModelSelection configured = Objects.requireNonNull(
                configuredSelection, "configuredSelection");
        ModelProviderDefinition requiredProvider = Objects.requireNonNull(provider, "provider");
        ModelConnection requiredConnection = Objects.requireNonNull(connection, "connection");
        ModelCatalogEntry requiredCatalog = Objects.requireNonNull(catalog, "catalog");
        ModelPriceRevision requiredPrice = Objects.requireNonNull(price, "price");
        AgentModelPolicyConstraints requiredPolicy = Objects.requireNonNull(policy, "policy");
        AgentExecutionAuthorizationFacts requiredAuthorization = Objects.requireNonNull(
                authorization, "authorization");

        requireCurrentAuthorization(executionScope, requiredAuthorization);
        requireConfiguredCoordinates(configured, requiredProvider, requiredConnection, requiredCatalog);
        requireSelectable(requiredProvider, requiredConnection, requiredCatalog);
        requireConnectionAccess(
                configured,
                requiredConnection,
                Objects.requireNonNull(agentOwnership, "agentOwnership"),
                Objects.requireNonNull(ownerUserPrincipalId, "ownerUserPrincipalId"),
                executionScope,
                requiredAuthorization);
        requireModelPolicy(requiredProvider, requiredConnection, requiredCatalog, requiredPolicy);
        if (!requiredPrice.catalogCoordinate().equals(requiredCatalog.coordinate())) {
            throw rejected(AgentModelPreflightRejectionCode.PRICE_UNAVAILABLE);
        }

        AgentConfigurationHash hash = calculateHash(
                requiredRole,
                requiredProvider.providerKey(),
                requiredProvider.contentHash(),
                requiredProvider.adapterKey(),
                requiredProvider.dataPolicy(),
                requiredConnection.id(),
                requiredConnection.version(),
                requiredConnection.owner(),
                requiredConnection.credentialBinding().credentialVersion(),
                requiredConnection.region(),
                requiredCatalog.coordinate(),
                requiredCatalog.contentHash(),
                requiredCatalog.modelRevision(),
                requiredPrice.revision(),
                requiredPrice.tokenPrice(),
                requiredPrice.contentHash());
        return new ResolvedModelSelection(
                requiredRole,
                requiredProvider.providerKey(),
                requiredProvider.contentHash(),
                requiredProvider.adapterKey(),
                requiredProvider.dataPolicy(),
                requiredConnection.id(),
                requiredConnection.version(),
                requiredConnection.owner(),
                requiredConnection.credentialBinding().credentialVersion(),
                requiredConnection.region(),
                requiredCatalog.coordinate(),
                requiredCatalog.contentHash(),
                requiredCatalog.modelRevision(),
                requiredPrice.revision(),
                requiredPrice.tokenPrice(),
                requiredPrice.contentHash(),
                hash);
    }

    private static void requireCurrentAuthorization(
            AgentExecutionScope executionScope,
            AgentExecutionAuthorizationFacts authorization) {
        if (!authorization.principalActive()) {
            throw rejected(AgentModelPreflightRejectionCode.PRINCIPAL_INACTIVE);
        }
        if (!authorization.responsibilityAuthorized()) {
            throw rejected(AgentModelPreflightRejectionCode.RESPONSIBILITY_REQUIRED);
        }
        if (executionScope == AgentExecutionScope.TEAM
                && !authorization.teamParticipationActive()) {
            throw rejected(AgentModelPreflightRejectionCode.TEAM_PARTICIPATION_REQUIRED);
        }
        if (!authorization.budgetAvailable()) {
            throw rejected(AgentModelPreflightRejectionCode.BUDGET_EXHAUSTED);
        }
        if (!authorization.quotaAvailable()) {
            throw rejected(AgentModelPreflightRejectionCode.QUOTA_EXHAUSTED);
        }
    }

    private static void requireConfiguredCoordinates(
            AgentModelSelection configured,
            ModelProviderDefinition provider,
            ModelConnection connection,
            ModelCatalogEntry catalog) {
        if (!configured.organizationId().equals(connection.organizationId())
                || !configured.connectionId().equals(connection.id())
                || !configured.connectionOwner().equals(connection.owner())
                || !configured.providerKey().equals(provider.providerKey())
                || !configured.providerDefinitionHash().equals(provider.contentHash())
                || !configured.catalogCoordinate().equals(catalog.coordinate())
                || !configured.catalogContentHash().equals(catalog.contentHash())
                || !connection.providerKey().equals(provider.providerKey())
                || !catalog.providerKey().equals(provider.providerKey())) {
            throw rejected(AgentModelPreflightRejectionCode.COORDINATE_MISMATCH);
        }
    }

    private static void requireSelectable(
            ModelProviderDefinition provider,
            ModelConnection connection,
            ModelCatalogEntry catalog) {
        if (provider.status() != ModelRegistryStatus.ACTIVE) {
            throw rejected(AgentModelPreflightRejectionCode.PROVIDER_UNAVAILABLE);
        }
        if (catalog.status() != ModelRegistryStatus.ACTIVE) {
            throw rejected(AgentModelPreflightRejectionCode.CATALOG_UNAVAILABLE);
        }
        if (connection.status() != ModelConnectionStatus.ACTIVE
                || connection.health().status() != ModelConnectionHealthStatus.HEALTHY
                || !connection.health().isHealthyFor(
                        connection.credentialBinding().credentialVersion())) {
            throw rejected(AgentModelPreflightRejectionCode.CONNECTION_UNAVAILABLE);
        }
    }

    private static void requireConnectionAccess(
            AgentModelSelection configured,
            ModelConnection connection,
            AgentOwnership ownership,
            Optional<PrincipalId> ownerUserPrincipalId,
            AgentExecutionScope executionScope,
            AgentExecutionAuthorizationFacts authorization) {
        if (!authorization.usableConnectionIds().contains(connection.id())) {
            throw rejected(AgentModelPreflightRejectionCode.CONNECTION_FORBIDDEN);
        }
        ModelConnectionOwner owner = connection.owner();
        if (owner.type() == ModelConnectionOwnerType.USER) {
            PrincipalId userOwner = owner.userPrincipalId().orElseThrow();
            if (executionScope != AgentExecutionScope.PERSONAL
                    || ownership.type() != AgentOwnershipType.USER
                    || ownerUserPrincipalId.filter(userOwner::equals).isEmpty()
                    || !authorization.requestingPrincipalId().equals(userOwner)) {
                throw rejected(AgentModelPreflightRejectionCode.CONNECTION_FORBIDDEN);
            }
        } else if (executionScope == AgentExecutionScope.TEAM
                && owner.type() == ModelConnectionOwnerType.TEAM
                && owner.teamId().filter(teamId -> ownership.teamId().filter(teamId::equals).isPresent())
                        .isEmpty()) {
            throw rejected(AgentModelPreflightRejectionCode.CONNECTION_FORBIDDEN);
        }
        if (!configured.connectionOwner().equals(owner)) {
            throw rejected(AgentModelPreflightRejectionCode.COORDINATE_MISMATCH);
        }
    }

    private static void requireModelPolicy(
            ModelProviderDefinition provider,
            ModelConnection connection,
            ModelCatalogEntry catalog,
            AgentModelPolicyConstraints policy) {
        if (!catalog.capabilities().containsAll(policy.requiredCapabilities())) {
            throw rejected(AgentModelPreflightRejectionCode.CAPABILITY_UNSUPPORTED);
        }
        if (!policy.allowedRegions().contains(connection.region())
                || !catalog.availableRegions().contains(connection.region())
                || !provider.availableRegions().contains(connection.region())) {
            throw rejected(AgentModelPreflightRejectionCode.REGION_FORBIDDEN);
        }
        ModelDataPolicy dataPolicy = provider.dataPolicy();
        if (!policy.allowedRetentionModes().contains(dataPolicy.retentionMode())) {
            throw rejected(AgentModelPreflightRejectionCode.DATA_POLICY_FORBIDDEN);
        }
        if (!policy.providerTrainingAllowed()
                && dataPolicy.trainingUsagePolicy() != ModelTrainingUsagePolicy.PROHIBITED) {
            throw rejected(AgentModelPreflightRejectionCode.DATA_POLICY_FORBIDDEN);
        }
        if (dataPolicy.retentionMode() == ModelDataRetentionMode.TIME_BOUND) {
            Optional<Duration> maximum = policy.maximumRetention();
            if (maximum.isPresent()
                    && dataPolicy.maximumRetention().orElseThrow().compareTo(maximum.orElseThrow()) > 0) {
                throw rejected(AgentModelPreflightRejectionCode.DATA_POLICY_FORBIDDEN);
            }
        }
        if (catalog.contextWindowTokens() < policy.minimumContextWindowTokens()) {
            throw rejected(AgentModelPreflightRejectionCode.CONTEXT_LIMIT_EXCEEDED);
        }
        if (catalog.maximumOutputTokens() < policy.minimumOutputTokens()) {
            throw rejected(AgentModelPreflightRejectionCode.OUTPUT_LIMIT_EXCEEDED);
        }
    }

    private static AgentConfigurationHash calculateHash(
            ResolvedModelRole role,
            ModelProviderKey providerKey,
            ModelRegistryHash providerDefinitionHash,
            ModelAdapterKey adapterKey,
            ModelDataPolicy dataPolicy,
            ModelConnectionId connectionId,
            long connectionVersion,
            ModelConnectionOwner connectionOwner,
            ModelCredentialVersion credentialVersion,
            ModelRegion region,
            ModelCatalogCoordinate catalogCoordinate,
            ModelRegistryHash catalogContentHash,
            ModelRevision modelRevision,
            long priceRevision,
            ModelTokenPrice tokenPrice,
            ModelRegistryHash priceContentHash) {
        StringBuilder canonical = new StringBuilder("resolved-model-selection-v1");
        AgentConfigurationHash.append(canonical, role.name());
        AgentConfigurationHash.append(canonical, providerKey.toString());
        AgentConfigurationHash.append(canonical, providerDefinitionHash.toString());
        AgentConfigurationHash.append(canonical, adapterKey.toString());
        AgentConfigurationHash.append(canonical, dataPolicy.retentionMode().name());
        AgentConfigurationHash.append(
                canonical,
                dataPolicy.maximumRetention().map(Duration::getSeconds)
                        .map(Object::toString)
                        .orElse("none"));
        AgentConfigurationHash.append(canonical, dataPolicy.trainingUsagePolicy().name());
        AgentConfigurationHash.append(canonical, connectionId.toString());
        AgentConfigurationHash.append(canonical, Long.toString(connectionVersion));
        AgentConfigurationHash.append(canonical, connectionOwner.type().name());
        AgentConfigurationHash.append(canonical, connectionOwner.ownerId().toString());
        AgentConfigurationHash.append(canonical, credentialVersion.toString());
        AgentConfigurationHash.append(canonical, region.toString());
        AgentConfigurationHash.append(canonical, catalogCoordinate.entryId().toString());
        AgentConfigurationHash.append(canonical, catalogCoordinate.providerKey().toString());
        AgentConfigurationHash.append(canonical, catalogCoordinate.modelId().toString());
        AgentConfigurationHash.append(canonical, catalogCoordinate.catalogRevision().toString());
        AgentConfigurationHash.append(canonical, catalogContentHash.toString());
        AgentConfigurationHash.append(canonical, modelRevision.toString());
        AgentConfigurationHash.append(canonical, Long.toString(priceRevision));
        AgentConfigurationHash.append(
                canonical, tokenPrice.inputPerMillionTokens().toPlainString());
        AgentConfigurationHash.append(
                canonical, tokenPrice.outputPerMillionTokens().toPlainString());
        AgentConfigurationHash.append(
                canonical,
                tokenPrice.cachedInputPerMillionTokens()
                        .map(value -> value.toPlainString())
                        .orElse("none"));
        AgentConfigurationHash.append(canonical, tokenPrice.currencyCode());
        AgentConfigurationHash.append(canonical, priceContentHash.toString());
        return AgentConfigurationHash.sha256(canonical.toString());
    }

    private static AgentModelPreflightException rejected(
            AgentModelPreflightRejectionCode reason) {
        return new AgentModelPreflightException(reason);
    }
}
