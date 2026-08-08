package io.crewscope.domain.provider;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Versioned Organization registry entry for one stable Provider capability contract. */
public final class ProviderDefinition {

    private final ProviderDefinitionId id;
    private final OrganizationId organizationId;
    private final String key;
    private final ProviderType type;
    private final String interfaceVersion;
    private final String displayName;
    private final ProviderCapabilities capabilities;
    private final ProviderRegistrationStatus status;
    private final long version;
    private final AuditMetadata audit;

    private ProviderDefinition(
            ProviderDefinitionId id,
            OrganizationId organizationId,
            String key,
            ProviderType type,
            String interfaceVersion,
            String displayName,
            ProviderCapabilities capabilities,
            ProviderRegistrationStatus status,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.key = ProviderModelSupport.key(key, "providerDefinition.key");
        this.type = Objects.requireNonNull(type, "type");
        this.interfaceVersion = ProviderModelSupport.version(
                interfaceVersion, "providerDefinition.interfaceVersion");
        this.displayName = ProviderModelSupport.text(
                displayName, "providerDefinition.displayName", 200);
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.status = Objects.requireNonNull(status, "status");
        this.version = ProviderModelSupport.nonNegativeVersion(
                version, "providerDefinition.version");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    public static ProviderDefinition create(
            ProviderDefinitionId id,
            OrganizationId organizationId,
            String key,
            ProviderType type,
            String interfaceVersion,
            String displayName,
            ProviderCapabilities capabilities,
            Principal actor,
            UtcTimestamp occurredAt) {
        PrincipalId actorId = ProviderModelSupport.activeActor(
                actor, organizationId, "providerDefinition.createdByPrincipalId");
        return new ProviderDefinition(
                id,
                organizationId,
                key,
                type,
                interfaceVersion,
                displayName,
                capabilities,
                ProviderRegistrationStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(actorId, occurredAt));
    }

    public static ProviderDefinition reconstitute(
            ProviderDefinitionId id,
            OrganizationId organizationId,
            String key,
            ProviderType type,
            String interfaceVersion,
            String displayName,
            ProviderCapabilities capabilities,
            ProviderRegistrationStatus status,
            long version,
            AuditMetadata audit) {
        return new ProviderDefinition(
                id,
                organizationId,
                key,
                type,
                interfaceVersion,
                displayName,
                capabilities,
                status,
                version,
                audit);
    }

    public ProviderDefinition disable(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        return transition(
                ProviderRegistrationStatus.ACTIVE,
                ProviderRegistrationStatus.DISABLED,
                expectedVersion,
                actor,
                occurredAt);
    }

    public ProviderDefinition activate(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        return transition(
                ProviderRegistrationStatus.DISABLED,
                ProviderRegistrationStatus.ACTIVE,
                expectedVersion,
                actor,
                occurredAt);
    }

    public ProviderDefinition archive(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        if (status == ProviderRegistrationStatus.ARCHIVED) {
            throw new InvalidStateTransitionException(
                    "ProviderDefinition", id, status, ProviderRegistrationStatus.ARCHIVED);
        }
        PrincipalId actorId = ProviderModelSupport.activeActor(
                actor, organizationId, "providerDefinition.updatedByPrincipalId");
        return copy(
                ProviderRegistrationStatus.ARCHIVED,
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    public boolean isActive() {
        return status == ProviderRegistrationStatus.ACTIVE;
    }

    private ProviderDefinition transition(
            ProviderRegistrationStatus required,
            ProviderRegistrationStatus target,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        if (status != required) {
            throw new InvalidStateTransitionException("ProviderDefinition", id, status, target);
        }
        PrincipalId actorId = ProviderModelSupport.activeActor(
                actor, organizationId, "providerDefinition.updatedByPrincipalId");
        return copy(target, version + 1, audit.modifiedBy(actorId, occurredAt));
    }

    private ProviderDefinition copy(
            ProviderRegistrationStatus targetStatus,
            long targetVersion,
            AuditMetadata targetAudit) {
        return new ProviderDefinition(
                id,
                organizationId,
                key,
                type,
                interfaceVersion,
                displayName,
                capabilities,
                targetStatus,
                targetVersion,
                targetAudit);
    }

    private void requireExpectedVersion(long expectedVersion) {
        if (version != ProviderModelSupport.nonNegativeVersion(
                expectedVersion, "expectedVersion")) {
            throw new OptimisticLockConflictException(
                    "ProviderDefinition", id, expectedVersion, version);
        }
    }

    public ProviderDefinitionId id() { return id; }
    public OrganizationId organizationId() { return organizationId; }
    public String key() { return key; }
    public ProviderType type() { return type; }
    public String interfaceVersion() { return interfaceVersion; }
    public String displayName() { return displayName; }
    public ProviderCapabilities capabilities() { return capabilities; }
    public ProviderRegistrationStatus status() { return status; }
    public long version() { return version; }
    public AuditMetadata audit() { return audit; }
}
