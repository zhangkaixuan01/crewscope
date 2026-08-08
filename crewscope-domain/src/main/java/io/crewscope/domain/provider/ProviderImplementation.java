package io.crewscope.domain.provider;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Versioned concrete implementation of one ProviderDefinition contract. */
public final class ProviderImplementation {

    private final ProviderImplementationId id;
    private final OrganizationId organizationId;
    private final ProviderDefinitionId definitionId;
    private final ProviderType type;
    private final String definitionInterfaceVersion;
    private final String key;
    private final String implementationVersion;
    private final ProviderCapabilities capabilities;
    private final ProviderConnectionRequirement connectionRequirement;
    private final Optional<String> connectorKey;
    private final ProviderRegistrationStatus status;
    private final long version;
    private final AuditMetadata audit;

    private ProviderImplementation(
            ProviderImplementationId id,
            OrganizationId organizationId,
            ProviderDefinitionId definitionId,
            ProviderType type,
            String definitionInterfaceVersion,
            String key,
            String implementationVersion,
            ProviderCapabilities capabilities,
            ProviderConnectionRequirement connectionRequirement,
            Optional<String> connectorKey,
            ProviderRegistrationStatus status,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.definitionId = Objects.requireNonNull(definitionId, "definitionId");
        this.type = Objects.requireNonNull(type, "type");
        this.definitionInterfaceVersion = ProviderModelSupport.version(
                definitionInterfaceVersion,
                "providerImplementation.definitionInterfaceVersion");
        this.key = ProviderModelSupport.key(key, "providerImplementation.key");
        this.implementationVersion = ProviderModelSupport.version(
                implementationVersion, "providerImplementation.implementationVersion");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.connectionRequirement = Objects.requireNonNull(
                connectionRequirement, "connectionRequirement");
        this.connectorKey = requireConnector(connectionRequirement, connectorKey);
        this.status = Objects.requireNonNull(status, "status");
        this.version = ProviderModelSupport.nonNegativeVersion(
                version, "providerImplementation.version");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    public static ProviderImplementation create(
            ProviderImplementationId id,
            ProviderDefinition definition,
            String key,
            String implementationVersion,
            ProviderCapabilities capabilities,
            ProviderConnectionRequirement connectionRequirement,
            Optional<String> connectorKey,
            Principal actor,
            UtcTimestamp occurredAt) {
        ProviderDefinition requiredDefinition = requireActiveDefinition(definition);
        ProviderCapabilities requiredCapabilities = Objects.requireNonNull(
                capabilities, "capabilities");
        if (!requiredDefinition.capabilities().includes(requiredCapabilities)) {
            throw new DomainValidationException(
                    "providerImplementation.capabilities",
                    "must be a subset of the ProviderDefinition capabilities");
        }
        PrincipalId actorId = ProviderModelSupport.activeActor(
                actor,
                requiredDefinition.organizationId(),
                "providerImplementation.createdByPrincipalId");
        return new ProviderImplementation(
                id,
                requiredDefinition.organizationId(),
                requiredDefinition.id(),
                requiredDefinition.type(),
                requiredDefinition.interfaceVersion(),
                key,
                implementationVersion,
                requiredCapabilities,
                connectionRequirement,
                connectorKey,
                ProviderRegistrationStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(actorId, occurredAt));
    }

    public static ProviderImplementation reconstitute(
            ProviderImplementationId id,
            OrganizationId organizationId,
            ProviderDefinitionId definitionId,
            ProviderType type,
            String definitionInterfaceVersion,
            String key,
            String implementationVersion,
            ProviderCapabilities capabilities,
            ProviderConnectionRequirement connectionRequirement,
            Optional<String> connectorKey,
            ProviderRegistrationStatus status,
            long version,
            AuditMetadata audit) {
        return new ProviderImplementation(
                id,
                organizationId,
                definitionId,
                type,
                definitionInterfaceVersion,
                key,
                implementationVersion,
                capabilities,
                connectionRequirement,
                connectorKey,
                status,
                version,
                audit);
    }

    /** Checks the exact definition identity/version and requested capability subset. */
    public boolean supports(ProviderDefinition definition, ProviderCapabilities required) {
        ProviderDefinition candidate = Objects.requireNonNull(definition, "definition");
        ProviderCapabilities requiredCapabilities = Objects.requireNonNull(required, "required");
        return isActive()
                && candidate.isActive()
                && organizationId.equals(candidate.organizationId())
                && definitionId.equals(candidate.id())
                && type == candidate.type()
                && definitionInterfaceVersion.equals(candidate.interfaceVersion())
                && candidate.capabilities().includes(capabilities)
                && capabilities.includes(requiredCapabilities);
    }

    public ProviderImplementation disable(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        return transition(
                ProviderRegistrationStatus.ACTIVE,
                ProviderRegistrationStatus.DISABLED,
                expectedVersion,
                actor,
                occurredAt);
    }

    public ProviderImplementation activate(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        return transition(
                ProviderRegistrationStatus.DISABLED,
                ProviderRegistrationStatus.ACTIVE,
                expectedVersion,
                actor,
                occurredAt);
    }

    public ProviderImplementation archive(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        if (status == ProviderRegistrationStatus.ARCHIVED) {
            throw new InvalidStateTransitionException(
                    "ProviderImplementation", id, status, ProviderRegistrationStatus.ARCHIVED);
        }
        PrincipalId actorId = ProviderModelSupport.activeActor(
                actor, organizationId, "providerImplementation.updatedByPrincipalId");
        return copy(
                ProviderRegistrationStatus.ARCHIVED,
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    public boolean isActive() {
        return status == ProviderRegistrationStatus.ACTIVE;
    }

    private ProviderImplementation transition(
            ProviderRegistrationStatus required,
            ProviderRegistrationStatus target,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        if (status != required) {
            throw new InvalidStateTransitionException("ProviderImplementation", id, status, target);
        }
        PrincipalId actorId = ProviderModelSupport.activeActor(
                actor, organizationId, "providerImplementation.updatedByPrincipalId");
        return copy(target, version + 1, audit.modifiedBy(actorId, occurredAt));
    }

    private ProviderImplementation copy(
            ProviderRegistrationStatus targetStatus,
            long targetVersion,
            AuditMetadata targetAudit) {
        return new ProviderImplementation(
                id,
                organizationId,
                definitionId,
                type,
                definitionInterfaceVersion,
                key,
                implementationVersion,
                capabilities,
                connectionRequirement,
                connectorKey,
                targetStatus,
                targetVersion,
                targetAudit);
    }

    private void requireExpectedVersion(long expectedVersion) {
        if (version != ProviderModelSupport.nonNegativeVersion(
                expectedVersion, "expectedVersion")) {
            throw new OptimisticLockConflictException(
                    "ProviderImplementation", id, expectedVersion, version);
        }
    }

    private static ProviderDefinition requireActiveDefinition(ProviderDefinition definition) {
        ProviderDefinition required = Objects.requireNonNull(definition, "definition");
        if (!required.isActive()) {
            throw new DomainValidationException(
                    "providerImplementation.definitionId",
                    "must reference an active ProviderDefinition");
        }
        return required;
    }

    private static Optional<String> requireConnector(
            ProviderConnectionRequirement requirement, Optional<String> connectorKey) {
        Optional<String> required = Objects.requireNonNull(connectorKey, "connectorKey")
                .map(value -> ProviderModelSupport.key(value, "providerImplementation.connectorKey"));
        if ((requirement == ProviderConnectionRequirement.NONE) == required.isPresent()) {
            throw new DomainValidationException(
                    "providerImplementation.connectorKey",
                    "must be present exactly when a Connection is required");
        }
        return required;
    }

    public ProviderImplementationId id() { return id; }
    public OrganizationId organizationId() { return organizationId; }
    public ProviderDefinitionId definitionId() { return definitionId; }
    public ProviderType type() { return type; }
    public String definitionInterfaceVersion() { return definitionInterfaceVersion; }
    public String key() { return key; }
    public String implementationVersion() { return implementationVersion; }
    public ProviderCapabilities capabilities() { return capabilities; }
    public ProviderConnectionRequirement connectionRequirement() { return connectionRequirement; }
    public Optional<String> connectorKey() { return connectorKey; }
    public ProviderRegistrationStatus status() { return status; }
    public long version() { return version; }
    public AuditMetadata audit() { return audit; }
}
