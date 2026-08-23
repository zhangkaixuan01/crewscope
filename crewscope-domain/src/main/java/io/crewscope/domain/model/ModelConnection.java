package io.crewscope.domain.model;

import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Stable, versioned and non-secret connection to one model provider endpoint. */
public final class ModelConnection {

    private final ModelConnectionId id;
    private final OrganizationId organizationId;
    private final ModelProviderKey providerKey;
    private final ModelRegistryHash providerDefinitionHash;
    private final ModelConnectionOwner owner;
    private final ModelEndpoint endpoint;
    private final ModelRegion region;
    private final ModelCredentialBinding credentialBinding;
    private final ModelBillingSubject billingSubject;
    private final ModelConnectionStatus status;
    private final ModelConnectionHealth health;
    private final Optional<ModelConnectionRevocationReason> revocationReason;
    private final long version;
    private final AuditMetadata audit;

    private ModelConnection(
            ModelProviderDefinition provider,
            ModelRegistryHash providerDefinitionHash,
            ModelConnectionId id,
            OrganizationId organizationId,
            ModelConnectionOwner owner,
            ModelEndpoint endpoint,
            ModelRegion region,
            ModelCredentialBinding credentialBinding,
            ModelBillingSubject billingSubject,
            ModelConnectionStatus status,
            ModelConnectionHealth health,
            Optional<ModelConnectionRevocationReason> revocationReason,
            long version,
            AuditMetadata audit,
            boolean requireActiveProvider) {
        ModelProviderDefinition requiredProvider = Objects.requireNonNull(provider, "provider");
        if (requireActiveProvider) {
            requiredProvider.requireSelectable();
        }
        this.id = Objects.requireNonNull(id, "id");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.providerKey = requiredProvider.providerKey();
        this.providerDefinitionHash = Objects.requireNonNull(
                providerDefinitionHash, "providerDefinitionHash");
        requireProvider(requiredProvider, false);
        this.owner = requireOwner(organizationId, owner);
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.region = requireRegion(requiredProvider, region);
        this.credentialBinding = requireCredentialBinding(this.owner, credentialBinding);
        this.billingSubject = requireBillingSubject(this.owner, billingSubject);
        this.status = Objects.requireNonNull(status, "status");
        this.health = requireHealth(this.credentialBinding, health);
        this.revocationReason = requireRevocationReason(status, revocationReason);
        this.version = requireVersion(version);
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    private ModelConnection(
            ModelConnection source,
            ModelCredentialBinding credentialBinding,
            ModelConnectionStatus status,
            ModelConnectionHealth health,
            Optional<ModelConnectionRevocationReason> revocationReason,
            long version,
            AuditMetadata audit) {
        ModelConnection requiredSource = Objects.requireNonNull(source, "source");
        this.id = requiredSource.id;
        this.organizationId = requiredSource.organizationId;
        this.providerKey = requiredSource.providerKey;
        this.providerDefinitionHash = requiredSource.providerDefinitionHash;
        this.owner = requiredSource.owner;
        this.endpoint = requiredSource.endpoint;
        this.region = requiredSource.region;
        this.credentialBinding = requireCredentialBinding(this.owner, credentialBinding);
        this.billingSubject = requiredSource.billingSubject;
        this.status = Objects.requireNonNull(status, "status");
        this.health = requireHealth(this.credentialBinding, health);
        this.revocationReason = requireRevocationReason(this.status, revocationReason);
        this.version = requireVersion(version);
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** Opens an active connection whose current credential must still be verified. */
    public static ModelConnection open(
            ModelProviderDefinition provider,
            ModelConnectionId id,
            ModelConnectionOwner owner,
            ModelEndpoint endpoint,
            ModelRegion region,
            ModelCredentialBinding credentialBinding,
            ModelBillingSubject billingSubject,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        ModelProviderDefinition requiredProvider = Objects.requireNonNull(provider, "provider");
        ModelConnectionOwner requiredOwner = Objects.requireNonNull(owner, "owner");
        ModelCredentialBinding requiredCredential = Objects.requireNonNull(
                credentialBinding, "credentialBinding");
        return new ModelConnection(
                requiredProvider,
                requiredProvider.contentHash(),
                id,
                requiredOwner.organizationId(),
                requiredOwner,
                endpoint,
                region,
                requiredCredential,
                billingSubject,
                ModelConnectionStatus.ACTIVE,
                ModelConnectionHealth.unknown(requiredCredential.credentialVersion()),
                Optional.empty(),
                0,
                AuditMetadata.createdBy(actor, occurredAt),
                true);
    }

    /** Reconstitutes a connection while verifying its exact provider and scope facts. */
    public static ModelConnection reconstitute(
            ModelProviderDefinition provider,
            ModelRegistryHash providerDefinitionHash,
            ModelConnectionId id,
            OrganizationId organizationId,
            ModelConnectionOwner owner,
            ModelEndpoint endpoint,
            ModelRegion region,
            ModelCredentialBinding credentialBinding,
            ModelBillingSubject billingSubject,
            ModelConnectionStatus status,
            ModelConnectionHealth health,
            Optional<ModelConnectionRevocationReason> revocationReason,
            long version,
            AuditMetadata audit) {
        return new ModelConnection(
                provider,
                providerDefinitionHash,
                id,
                organizationId,
                owner,
                endpoint,
                region,
                credentialBinding,
                billingSubject,
                status,
                health,
                revocationReason,
                version,
                audit,
                false);
    }

    /** Records a successful sanitized verification for the current credential version. */
    public ModelConnection recordVerificationSuccess(
            ModelProviderDefinition provider,
            long expectedVersion,
            ModelCredentialVersion expectedCredentialVersion,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        requireNonTerminal(ModelConnectionStatus.ACTIVE);
        requireProvider(provider, true);
        return mutate(
                status,
                credentialBinding,
                health.recordSuccess(expectedCredentialVersion, occurredAt),
                revocationReason,
                actor,
                occurredAt);
    }

    /** Records a failed sanitized verification without retaining the provider response. */
    public ModelConnection recordVerificationFailure(
            ModelProviderDefinition provider,
            long expectedVersion,
            ModelCredentialVersion expectedCredentialVersion,
            ModelConnectionHealthFailureCode failureCode,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        requireNonTerminal(ModelConnectionStatus.ACTIVE);
        requireProvider(provider, true);
        return mutate(
                status,
                credentialBinding,
                health.recordFailure(expectedCredentialVersion, failureCode, occurredAt),
                revocationReason,
                actor,
                occurredAt);
    }

    /** Advances the CredentialStore version while retaining stable connection identity. */
    public ModelConnection rotateCredential(
            long expectedVersion,
            ModelCredentialVersion nextCredentialVersion,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        requireNonTerminal(status);
        ModelCredentialBinding rotated = credentialBinding.rotate(nextCredentialVersion);
        return mutate(
                status,
                rotated,
                ModelConnectionHealth.unknown(rotated.credentialVersion()),
                revocationReason,
                actor,
                occurredAt);
    }

    public ModelConnection suspend(
            long expectedVersion, PrincipalId actor, UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        requireStatus(ModelConnectionStatus.ACTIVE, ModelConnectionStatus.SUSPENDED);
        return mutate(
                ModelConnectionStatus.SUSPENDED,
                credentialBinding,
                health,
                Optional.empty(),
                actor,
                occurredAt);
    }

    public ModelConnection activate(
            ModelProviderDefinition provider,
            long expectedVersion,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        requireStatus(ModelConnectionStatus.SUSPENDED, ModelConnectionStatus.ACTIVE);
        requireProvider(provider, true);
        if (!health.isHealthyFor(credentialBinding.credentialVersion())) {
            throw new DomainValidationException(
                    "modelConnection.health",
                    "must be HEALTHY for the current credential version before activation");
        }
        return mutate(
                ModelConnectionStatus.ACTIVE,
                credentialBinding,
                health,
                Optional.empty(),
                actor,
                occurredAt);
    }

    public ModelConnection revoke(
            long expectedVersion,
            ModelConnectionRevocationReason reason,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        requireNonTerminal(ModelConnectionStatus.REVOKED);
        return mutate(
                ModelConnectionStatus.REVOKED,
                credentialBinding,
                health,
                Optional.of(Objects.requireNonNull(reason, "reason")),
                actor,
                occurredAt);
    }

    /** Fails closed unless this exact provider and healthy connection can serve a new selection. */
    public void requireSelectable(ModelProviderDefinition provider) {
        requireProvider(provider, true);
        if (status != ModelConnectionStatus.ACTIVE) {
            throw new DomainValidationException(
                    "modelConnection.status", "must be ACTIVE for a new model selection");
        }
        if (!health.isHealthyFor(credentialBinding.credentialVersion())) {
            throw new DomainValidationException(
                    "modelConnection.health",
                    "must be HEALTHY for the current credential version");
        }
    }

    private ModelConnection mutate(
            ModelConnectionStatus nextStatus,
            ModelCredentialBinding nextCredentialBinding,
            ModelConnectionHealth nextHealth,
            Optional<ModelConnectionRevocationReason> nextRevocationReason,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        return new ModelConnection(
                this,
                nextCredentialBinding,
                nextStatus,
                nextHealth,
                nextRevocationReason,
                nextVersion(),
                audit.modifiedBy(actor, occurredAt));
    }

    private void requireProvider(ModelProviderDefinition provider, boolean requireActive) {
        ModelProviderDefinition required = Objects.requireNonNull(provider, "provider");
        if (requireActive) {
            required.requireSelectable();
        }
        if (!providerKey.equals(required.providerKey())
                || !providerDefinitionHash.equals(required.contentHash())) {
            throw new DomainValidationException(
                    "modelConnection.providerKey",
                    "must reference the exact model provider definition");
        }
    }

    private void requireExpectedVersion(long expectedVersion) {
        if (version != requireVersion(expectedVersion)) {
            throw new OptimisticLockConflictException(
                    "ModelConnection", id, expectedVersion, version);
        }
    }

    private void requireStatus(ModelConnectionStatus required, ModelConnectionStatus target) {
        if (status != required) {
            throw new InvalidStateTransitionException(
                    "ModelConnection", id, status, target);
        }
    }

    private void requireNonTerminal(ModelConnectionStatus target) {
        if (status.isTerminal()) {
            throw new InvalidStateTransitionException(
                    "ModelConnection", id, status, target);
        }
    }

    private long nextVersion() {
        if (version == Long.MAX_VALUE) {
            throw new DomainValidationException(
                    "modelConnection.version", "must not overflow");
        }
        return version + 1;
    }

    private static ModelConnectionOwner requireOwner(
            OrganizationId organizationId, ModelConnectionOwner owner) {
        ModelConnectionOwner required = Objects.requireNonNull(owner, "owner");
        if (!organizationId.equals(required.organizationId())) {
            throw new DomainValidationException(
                    "modelConnection.owner", "must belong to the Connection Organization");
        }
        return required;
    }

    private static ModelRegion requireRegion(
            ModelProviderDefinition provider, ModelRegion region) {
        ModelRegion required = Objects.requireNonNull(region, "region");
        if (!provider.availableRegions().contains(required)) {
            throw new DomainValidationException(
                    "modelConnection.region", "must be available on the model provider");
        }
        return required;
    }

    private static ModelCredentialBinding requireCredentialBinding(
            ModelConnectionOwner owner, ModelCredentialBinding binding) {
        ModelCredentialBinding required = Objects.requireNonNull(binding, "credentialBinding");
        if (!required.subject().isAllowedFor(owner)) {
            throw new DomainValidationException(
                    "modelConnection.credentialSubject",
                    "must be authorized by the connection owner scope");
        }
        return required;
    }

    private static ModelBillingSubject requireBillingSubject(
            ModelConnectionOwner owner, ModelBillingSubject subject) {
        ModelBillingSubject required = Objects.requireNonNull(subject, "billingSubject");
        if (!required.isAllowedFor(owner)) {
            throw new DomainValidationException(
                    "modelConnection.billingSubject",
                    "must be authorized by the connection owner scope");
        }
        return required;
    }

    private static ModelConnectionHealth requireHealth(
            ModelCredentialBinding binding, ModelConnectionHealth health) {
        ModelConnectionHealth required = Objects.requireNonNull(health, "health");
        if (!required.credentialVersion().equals(binding.credentialVersion())) {
            throw new DomainValidationException(
                    "modelConnection.health.credentialVersion",
                    "must match the bound credential version");
        }
        return required;
    }

    private static Optional<ModelConnectionRevocationReason> requireRevocationReason(
            ModelConnectionStatus status,
            Optional<ModelConnectionRevocationReason> revocationReason) {
        Optional<ModelConnectionRevocationReason> required = Objects.requireNonNull(
                revocationReason, "revocationReason");
        if (status.isTerminal() != required.isPresent()) {
            throw new DomainValidationException(
                    "modelConnection.revocationReason",
                    "must be present exactly for a terminal status");
        }
        return required;
    }

    private static long requireVersion(long value) {
        if (value < 0) {
            throw new DomainValidationException(
                    "modelConnection.version", "must not be negative");
        }
        return value;
    }

    public ModelConnectionId id() {
        return id;
    }

    public OrganizationId organizationId() {
        return organizationId;
    }

    public ModelProviderKey providerKey() {
        return providerKey;
    }

    public ModelRegistryHash providerDefinitionHash() {
        return providerDefinitionHash;
    }

    public ModelConnectionOwner owner() {
        return owner;
    }

    public ModelEndpoint endpoint() {
        return endpoint;
    }

    public ModelRegion region() {
        return region;
    }

    public ModelCredentialBinding credentialBinding() {
        return credentialBinding;
    }

    public ModelBillingSubject billingSubject() {
        return billingSubject;
    }

    public ModelConnectionStatus status() {
        return status;
    }

    public ModelConnectionHealth health() {
        return health;
    }

    public Optional<ModelConnectionRevocationReason> revocationReason() {
        return revocationReason;
    }

    public long version() {
        return version;
    }

    public AuditMetadata audit() {
        return audit;
    }
}
