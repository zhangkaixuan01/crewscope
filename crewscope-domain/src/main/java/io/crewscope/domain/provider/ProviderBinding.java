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

/** Versioned choice of Provider implementation, external identity and effective access scope. */
public final class ProviderBinding {

    private final ProviderBindingId id;
    private final OrganizationId organizationId;
    private final ProviderBindingTarget target;
    private final ProviderOwner owner;
    private final ProviderDefinitionId definitionId;
    private final long definitionVersion;
    private final ProviderType providerType;
    private final ProviderImplementationId implementationId;
    private final long implementationVersion;
    private final Optional<ConnectionId> connectionId;
    private final Optional<Long> connectionVersion;
    private final Optional<ConnectionGrantId> connectionGrantId;
    private final Optional<Long> connectionGrantVersion;
    private final Optional<ProviderExecutionIdentity> executionIdentity;
    private final ProviderAccessScope effectiveAccess;
    private final boolean defaultUsage;
    private final ProviderRegistrationStatus status;
    private final long version;
    private final AuditMetadata audit;

    private ProviderBinding(
            ProviderBindingId id,
            OrganizationId organizationId,
            ProviderBindingTarget target,
            ProviderOwner owner,
            ProviderDefinitionId definitionId,
            long definitionVersion,
            ProviderType providerType,
            ProviderImplementationId implementationId,
            long implementationVersion,
            Optional<ConnectionId> connectionId,
            Optional<Long> connectionVersion,
            Optional<ConnectionGrantId> connectionGrantId,
            Optional<Long> connectionGrantVersion,
            Optional<ProviderExecutionIdentity> executionIdentity,
            ProviderAccessScope effectiveAccess,
            boolean defaultUsage,
            ProviderRegistrationStatus status,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.target = requireTarget(organizationId, target);
        this.owner = requireOwner(this.target, owner);
        this.definitionId = Objects.requireNonNull(definitionId, "definitionId");
        this.definitionVersion = ProviderModelSupport.nonNegativeVersion(
                definitionVersion, "providerBinding.definitionVersion");
        this.providerType = Objects.requireNonNull(providerType, "providerType");
        this.implementationId = Objects.requireNonNull(implementationId, "implementationId");
        this.implementationVersion = ProviderModelSupport.nonNegativeVersion(
                implementationVersion, "providerBinding.implementationVersion");
        this.connectionId = Objects.requireNonNull(connectionId, "connectionId");
        this.connectionVersion = requireVersionReference(connectionVersion, "connectionVersion");
        this.connectionGrantId = Objects.requireNonNull(
                connectionGrantId, "connectionGrantId");
        this.connectionGrantVersion = requireVersionReference(
                connectionGrantVersion, "connectionGrantVersion");
        this.executionIdentity = Objects.requireNonNull(executionIdentity, "executionIdentity");
        requireConnectionShape();
        this.effectiveAccess = Objects.requireNonNull(effectiveAccess, "effectiveAccess");
        this.defaultUsage = defaultUsage;
        this.status = Objects.requireNonNull(status, "status");
        this.version = ProviderModelSupport.nonNegativeVersion(version, "providerBinding.version");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** Creates a binding and permanently narrows requested access through its ConnectionGrant. */
    public static ProviderBinding bind(
            ProviderBindingId id,
            ProviderBindingTarget target,
            ProviderOwner owner,
            ProviderDefinition definition,
            ProviderImplementation implementation,
            Optional<Connection> connection,
            Optional<ConnectionGrant> connectionGrant,
            ProviderAccessScope requestedAccess,
            boolean defaultUsage,
            Principal actor,
            UtcTimestamp occurredAt) {
        ProviderBindingTarget requiredTarget = Objects.requireNonNull(target, "target");
        ProviderOwner requiredOwner = requireOwner(requiredTarget, owner);
        BindingResolution resolution = BindingResolution.resolve(
                requiredTarget,
                requiredOwner,
                definition,
                implementation,
                connection,
                connectionGrant,
                requestedAccess,
                occurredAt);
        PrincipalId actorId = ProviderModelSupport.activeActor(
                actor,
                requiredTarget.organizationId(),
                "providerBinding.createdByPrincipalId");
        return new ProviderBinding(
                id,
                requiredTarget.organizationId(),
                requiredTarget,
                requiredOwner,
                resolution.definition().id(),
                resolution.definition().version(),
                resolution.definition().type(),
                resolution.implementation().id(),
                resolution.implementation().version(),
                resolution.connection().map(Connection::id),
                resolution.connection().map(Connection::version),
                resolution.grant().map(ConnectionGrant::id),
                resolution.grant().map(ConnectionGrant::version),
                resolution.connection().map(value -> executionIdentity(value.owner().type())),
                resolution.effectiveAccess(),
                defaultUsage,
                ProviderRegistrationStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(actorId, occurredAt));
    }

    public static ProviderBinding reconstitute(
            ProviderBindingId id,
            OrganizationId organizationId,
            ProviderBindingTarget target,
            ProviderOwner owner,
            ProviderDefinitionId definitionId,
            long definitionVersion,
            ProviderType providerType,
            ProviderImplementationId implementationId,
            long implementationVersion,
            Optional<ConnectionId> connectionId,
            Optional<Long> connectionVersion,
            Optional<ConnectionGrantId> connectionGrantId,
            Optional<Long> connectionGrantVersion,
            Optional<ProviderExecutionIdentity> executionIdentity,
            ProviderAccessScope effectiveAccess,
            boolean defaultUsage,
            ProviderRegistrationStatus status,
            long version,
            AuditMetadata audit) {
        return new ProviderBinding(
                id,
                organizationId,
                target,
                owner,
                definitionId,
                definitionVersion,
                providerType,
                implementationId,
                implementationVersion,
                connectionId,
                connectionVersion,
                connectionGrantId,
                connectionGrantVersion,
                executionIdentity,
                effectiveAccess,
                defaultUsage,
                status,
                version,
                audit);
    }

    /** Returns access only while every pinned registry and authorization fact is still current. */
    public Optional<ProviderAccessScope> currentAccess(
            ProviderDefinition definition,
            ProviderImplementation implementation,
            Optional<Connection> connection,
            Optional<ConnectionGrant> grant,
            UtcTimestamp now) {
        if (status != ProviderRegistrationStatus.ACTIVE) {
            return Optional.empty();
        }
        try {
            BindingResolution resolution = BindingResolution.resolve(
                    target,
                    owner,
                    definition,
                    implementation,
                    connection,
                    grant,
                    effectiveAccess,
                    now);
            if (!definitionId.equals(resolution.definition().id())
                    || definitionVersion != resolution.definition().version()
                    || providerType != resolution.definition().type()
                    || !implementationId.equals(resolution.implementation().id())
                    || implementationVersion != resolution.implementation().version()
                    || !connectionId.equals(resolution.connection().map(Connection::id))
                    || !connectionVersion.equals(resolution.connection().map(Connection::version))
                    || !connectionGrantId.equals(resolution.grant().map(ConnectionGrant::id))
                    || !connectionGrantVersion.equals(resolution.grant().map(ConnectionGrant::version))
                    || !executionIdentity.equals(resolution.connection()
                            .map(value -> executionIdentity(value.owner().type())))) {
                return Optional.empty();
            }
            return Optional.of(resolution.effectiveAccess());
        } catch (DomainValidationException exception) {
            return Optional.empty();
        }
    }

    public ProviderBinding disable(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        if (status != ProviderRegistrationStatus.ACTIVE) {
            throw new InvalidStateTransitionException(
                    "ProviderBinding", id, status, ProviderRegistrationStatus.DISABLED);
        }
        return transition(ProviderRegistrationStatus.DISABLED, actor, occurredAt);
    }

    public ProviderBinding activate(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        if (status != ProviderRegistrationStatus.DISABLED) {
            throw new InvalidStateTransitionException(
                    "ProviderBinding", id, status, ProviderRegistrationStatus.ACTIVE);
        }
        return transition(ProviderRegistrationStatus.ACTIVE, actor, occurredAt);
    }

    public ProviderBinding archive(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        if (status == ProviderRegistrationStatus.ARCHIVED) {
            throw new InvalidStateTransitionException(
                    "ProviderBinding", id, status, ProviderRegistrationStatus.ARCHIVED);
        }
        return transition(ProviderRegistrationStatus.ARCHIVED, actor, occurredAt);
    }

    private ProviderBinding transition(
            ProviderRegistrationStatus targetStatus,
            Principal actor,
            UtcTimestamp occurredAt) {
        PrincipalId actorId = ProviderModelSupport.activeActor(
                actor, organizationId, "providerBinding.updatedByPrincipalId");
        return new ProviderBinding(
                id,
                organizationId,
                target,
                owner,
                definitionId,
                definitionVersion,
                providerType,
                implementationId,
                implementationVersion,
                connectionId,
                connectionVersion,
                connectionGrantId,
                connectionGrantVersion,
                executionIdentity,
                effectiveAccess,
                defaultUsage,
                targetStatus,
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    private void requireExpectedVersion(long expectedVersion) {
        if (version != ProviderModelSupport.nonNegativeVersion(expectedVersion, "expectedVersion")) {
            throw new OptimisticLockConflictException(
                    "ProviderBinding", id, expectedVersion, version);
        }
    }

    private void requireConnectionShape() {
        boolean allPresent = connectionId.isPresent()
                && connectionVersion.isPresent()
                && connectionGrantId.isPresent()
                && connectionGrantVersion.isPresent()
                && executionIdentity.isPresent();
        boolean allEmpty = connectionId.isEmpty()
                && connectionVersion.isEmpty()
                && connectionGrantId.isEmpty()
                && connectionGrantVersion.isEmpty()
                && executionIdentity.isEmpty();
        if (!allPresent && !allEmpty) {
            throw new DomainValidationException(
                    "providerBinding.connectionId",
                    "Connection, Grant, versions and execution identity must be all present or all empty");
        }
    }

    private static ProviderBindingTarget requireTarget(
            OrganizationId organizationId, ProviderBindingTarget target) {
        ProviderBindingTarget required = Objects.requireNonNull(target, "target");
        if (!organizationId.equals(required.organizationId())) {
            throw new DomainValidationException(
                    "providerBinding.target", "must belong to the Binding Organization");
        }
        return required;
    }

    private static ProviderOwner requireOwner(
            ProviderBindingTarget target, ProviderOwner owner) {
        ProviderOwner required = Objects.requireNonNull(owner, "owner");
        if (!target.organizationId().equals(required.organizationId())
                || (required.type() == ProviderOwnerType.TEAM
                        && required.teamId().filter(target.teamId()::equals).isEmpty())) {
            throw new DomainValidationException(
                    "providerBinding.owner",
                    "must belong to the target Organization and, for TEAM ownership, target Team");
        }
        return required;
    }

    private static Optional<Long> requireVersionReference(
            Optional<Long> version, String field) {
        return Objects.requireNonNull(version, field)
                .map(value -> ProviderModelSupport.nonNegativeVersion(
                        value, "providerBinding." + field));
    }

    private static ProviderExecutionIdentity executionIdentity(ProviderOwnerType type) {
        return switch (type) {
            case USER -> ProviderExecutionIdentity.DELEGATED_USER;
            case TEAM -> ProviderExecutionIdentity.TEAM_SERVICE_ACCOUNT;
            case ORGANIZATION -> ProviderExecutionIdentity.ORGANIZATION_SERVICE_ACCOUNT;
        };
    }

    private record BindingResolution(
            ProviderDefinition definition,
            ProviderImplementation implementation,
            Optional<Connection> connection,
            Optional<ConnectionGrant> grant,
            ProviderAccessScope effectiveAccess) {

        private static BindingResolution resolve(
                ProviderBindingTarget target,
                ProviderOwner owner,
                ProviderDefinition definition,
                ProviderImplementation implementation,
                Optional<Connection> connection,
                Optional<ConnectionGrant> grant,
                ProviderAccessScope requested,
                UtcTimestamp now) {
            ProviderDefinition requiredDefinition = Objects.requireNonNull(
                    definition, "definition");
            ProviderImplementation requiredImplementation = Objects.requireNonNull(
                    implementation, "implementation");
            Optional<Connection> requiredConnection = Objects.requireNonNull(
                    connection, "connection");
            Optional<ConnectionGrant> requiredGrant = Objects.requireNonNull(grant, "grant");
            ProviderAccessScope requiredAccess = Objects.requireNonNull(requested, "requested");
            if (!target.organizationId().equals(requiredDefinition.organizationId())
                    || !requiredImplementation.supports(
                            requiredDefinition, requiredAccess.capabilities())) {
                throw new DomainValidationException(
                        "providerBinding.implementationId",
                        "must be an active compatible implementation in the target Organization");
            }
            if (requiredImplementation.connectionRequirement()
                    == ProviderConnectionRequirement.NONE) {
                if (requiredConnection.isPresent() || requiredGrant.isPresent()) {
                    throw new DomainValidationException(
                            "providerBinding.connectionId",
                            "must be empty for a connectionless Provider implementation");
                }
                return new BindingResolution(
                        requiredDefinition,
                        requiredImplementation,
                        Optional.empty(),
                        Optional.empty(),
                        requiredAccess);
            }
            Connection resolvedConnection = requiredConnection.orElseThrow(() ->
                    new DomainValidationException(
                            "providerBinding.connectionId",
                            "is required by the Provider implementation"));
            ConnectionGrant resolvedGrant = requiredGrant.orElseThrow(() ->
                    new DomainValidationException(
                            "providerBinding.connectionGrantId",
                            "is required by the Provider implementation"));
            if (!target.organizationId().equals(resolvedConnection.organizationId())
                    || requiredImplementation.connectorKey()
                            .filter(resolvedConnection.connectorKey()::equals)
                            .isEmpty()
                    || !owner.equals(resolvedGrant.grantee())) {
                throw new DomainValidationException(
                        "providerBinding.connectionId",
                        "must match the implementation connector, target Organization and Binding owner Grant");
            }
            ProviderAccessScope effective = resolvedGrant
                    .effectiveAccess(requiredAccess, resolvedConnection, now)
                    .orElseThrow(() -> new DomainValidationException(
                            "providerBinding.effectiveAccess",
                            "has no active capability and resource intersection with the ConnectionGrant"));
            return new BindingResolution(
                    requiredDefinition,
                    requiredImplementation,
                    Optional.of(resolvedConnection),
                    Optional.of(resolvedGrant),
                    effective);
        }
    }

    public ProviderBindingId id() { return id; }
    public OrganizationId organizationId() { return organizationId; }
    public ProviderBindingTarget target() { return target; }
    public ProviderOwner owner() { return owner; }
    public ProviderDefinitionId definitionId() { return definitionId; }
    public long definitionVersion() { return definitionVersion; }
    public ProviderType providerType() { return providerType; }
    public ProviderImplementationId implementationId() { return implementationId; }
    public long implementationVersion() { return implementationVersion; }
    public Optional<ConnectionId> connectionId() { return connectionId; }
    public Optional<Long> connectionVersion() { return connectionVersion; }
    public Optional<ConnectionGrantId> connectionGrantId() { return connectionGrantId; }
    public Optional<Long> connectionGrantVersion() { return connectionGrantVersion; }
    public Optional<ProviderExecutionIdentity> executionIdentity() { return executionIdentity; }
    public ProviderAccessScope effectiveAccess() { return effectiveAccess; }
    public boolean defaultUsage() { return defaultUsage; }
    public ProviderRegistrationStatus status() { return status; }
    public long version() { return version; }
    public AuditMetadata audit() { return audit; }
}
