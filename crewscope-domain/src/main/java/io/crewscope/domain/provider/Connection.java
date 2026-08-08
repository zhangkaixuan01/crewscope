package io.crewscope.domain.provider;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Versioned authorization of one USER, TEAM or ORGANIZATION external identity. */
public final class Connection {

    private final ConnectionId id;
    private final OrganizationId organizationId;
    private final ProviderOwner owner;
    private final String connectorKey;
    private final String externalAccountReference;
    private final CredentialId credentialId;
    private final ConnectionStatus status;
    private final Optional<UtcTimestamp> expiresAt;
    private final Optional<String> terminalReason;
    private final long version;
    private final AuditMetadata audit;

    private Connection(
            ConnectionId id,
            OrganizationId organizationId,
            ProviderOwner owner,
            String connectorKey,
            String externalAccountReference,
            CredentialId credentialId,
            ConnectionStatus status,
            Optional<UtcTimestamp> expiresAt,
            Optional<String> terminalReason,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.owner = requireOwner(organizationId, owner);
        this.connectorKey = ProviderModelSupport.key(connectorKey, "connection.connectorKey");
        this.externalAccountReference = ProviderModelSupport.text(
                externalAccountReference, "connection.externalAccountReference", 500);
        this.credentialId = Objects.requireNonNull(credentialId, "credentialId");
        this.status = Objects.requireNonNull(status, "status");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.terminalReason = requireTerminalReason(status, terminalReason);
        this.version = ProviderModelSupport.nonNegativeVersion(version, "connection.version");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    public static Connection authorize(
            ConnectionId id,
            ProviderOwner owner,
            String connectorKey,
            String externalAccountReference,
            CredentialId credentialId,
            Optional<UtcTimestamp> expiresAt,
            Principal actor,
            UtcTimestamp occurredAt) {
        ProviderOwner requiredOwner = Objects.requireNonNull(owner, "owner");
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        Optional<UtcTimestamp> requiredExpiry = Objects.requireNonNull(expiresAt, "expiresAt");
        if (requiredExpiry.filter(value -> value.compareTo(requiredTime) <= 0).isPresent()) {
            throw new DomainValidationException(
                    "connection.expiresAt", "must be after the authorization time");
        }
        PrincipalId actorId = ProviderModelSupport.activeActor(
                actor, requiredOwner.organizationId(), "connection.createdByPrincipalId");
        return new Connection(
                id,
                requiredOwner.organizationId(),
                requiredOwner,
                connectorKey,
                externalAccountReference,
                credentialId,
                ConnectionStatus.ACTIVE,
                requiredExpiry,
                Optional.empty(),
                0,
                AuditMetadata.createdBy(actorId, requiredTime));
    }

    public static Connection reconstitute(
            ConnectionId id,
            OrganizationId organizationId,
            ProviderOwner owner,
            String connectorKey,
            String externalAccountReference,
            CredentialId credentialId,
            ConnectionStatus status,
            Optional<UtcTimestamp> expiresAt,
            Optional<String> terminalReason,
            long version,
            AuditMetadata audit) {
        return new Connection(
                id,
                organizationId,
                owner,
                connectorKey,
                externalAccountReference,
                credentialId,
                status,
                expiresAt,
                terminalReason,
                version,
                audit);
    }

    public Connection suspend(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        ensureStatus(ConnectionStatus.ACTIVE, ConnectionStatus.SUSPENDED);
        return transition(ConnectionStatus.SUSPENDED, Optional.empty(), actor, occurredAt);
    }

    public Connection activate(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        ensureStatus(ConnectionStatus.SUSPENDED, ConnectionStatus.ACTIVE);
        if (expiresAt.filter(value -> value.compareTo(occurredAt) <= 0).isPresent()) {
            throw new DomainValidationException(
                    "connection.expiresAt", "has expired and cannot be activated");
        }
        return transition(ConnectionStatus.ACTIVE, Optional.empty(), actor, occurredAt);
    }

    public Connection revoke(
            long expectedVersion, Principal actor, String reason, UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        ensureNonTerminal(ConnectionStatus.REVOKED);
        return transition(
                ConnectionStatus.REVOKED,
                Optional.of(ProviderModelSupport.text(reason, "connection.terminalReason", 500)),
                actor,
                occurredAt);
    }

    public Connection expire(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        ensureNonTerminal(ConnectionStatus.EXPIRED);
        UtcTimestamp expiry = expiresAt.orElseThrow(() -> new DomainValidationException(
                "connection.expiresAt", "must be present before expiration"));
        if (expiry.compareTo(occurredAt) > 0) {
            throw new DomainValidationException(
                    "connection.expiresAt", "has not yet been reached");
        }
        return transition(
                ConnectionStatus.EXPIRED,
                Optional.of("authorization expired"),
                actor,
                occurredAt);
    }

    public boolean isUsableAt(UtcTimestamp now) {
        UtcTimestamp required = Objects.requireNonNull(now, "now");
        return status == ConnectionStatus.ACTIVE
                && expiresAt.map(value -> value.compareTo(required) > 0).orElse(true);
    }

    private Connection transition(
            ConnectionStatus target,
            Optional<String> reason,
            Principal actor,
            UtcTimestamp occurredAt) {
        PrincipalId actorId = ProviderModelSupport.activeActor(
                actor, organizationId, "connection.updatedByPrincipalId");
        return new Connection(
                id,
                organizationId,
                owner,
                connectorKey,
                externalAccountReference,
                credentialId,
                target,
                expiresAt,
                reason,
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    private void requireExpectedVersion(long expectedVersion) {
        if (version != ProviderModelSupport.nonNegativeVersion(expectedVersion, "expectedVersion")) {
            throw new OptimisticLockConflictException("Connection", id, expectedVersion, version);
        }
    }

    private void ensureStatus(ConnectionStatus required, ConnectionStatus target) {
        if (status != required) {
            throw new InvalidStateTransitionException("Connection", id, status, target);
        }
    }

    private void ensureNonTerminal(ConnectionStatus target) {
        if (status.isTerminal()) {
            throw new InvalidStateTransitionException("Connection", id, status, target);
        }
    }

    private static ProviderOwner requireOwner(
            OrganizationId organizationId, ProviderOwner owner) {
        ProviderOwner required = Objects.requireNonNull(owner, "owner");
        if (!required.organizationId().equals(organizationId)) {
            throw new DomainValidationException(
                    "connection.owner", "must belong to the Connection Organization");
        }
        return required;
    }

    private static Optional<String> requireTerminalReason(
            ConnectionStatus status, Optional<String> reason) {
        Optional<String> required = Objects.requireNonNull(reason, "terminalReason")
                .map(value -> ProviderModelSupport.text(value, "connection.terminalReason", 500));
        if (status.isTerminal() != required.isPresent()) {
            throw new DomainValidationException(
                    "connection.terminalReason", "must be present exactly for a terminal status");
        }
        return required;
    }

    public ConnectionId id() { return id; }
    public OrganizationId organizationId() { return organizationId; }
    public ProviderOwner owner() { return owner; }
    public String connectorKey() { return connectorKey; }
    public String externalAccountReference() { return externalAccountReference; }
    public CredentialId credentialId() { return credentialId; }
    public ConnectionStatus status() { return status; }
    public Optional<UtcTimestamp> expiresAt() { return expiresAt; }
    public Optional<String> terminalReason() { return terminalReason; }
    public long version() { return version; }
    public AuditMetadata audit() { return audit; }
}
