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

/** Versioned capability and resource delegation from one external Connection. */
public final class ConnectionGrant {

    private final ConnectionGrantId id;
    private final OrganizationId organizationId;
    private final ConnectionId connectionId;
    private final ProviderOwner connectionOwner;
    private final ProviderOwner grantee;
    private final ProviderAccessScope grantedAccess;
    private final UtcTimestamp validFrom;
    private final Optional<UtcTimestamp> expiresAt;
    private final ConnectionGrantStatus status;
    private final Optional<String> terminalReason;
    private final long version;
    private final AuditMetadata audit;

    private ConnectionGrant(
            ConnectionGrantId id,
            OrganizationId organizationId,
            ConnectionId connectionId,
            ProviderOwner connectionOwner,
            ProviderOwner grantee,
            ProviderAccessScope grantedAccess,
            UtcTimestamp validFrom,
            Optional<UtcTimestamp> expiresAt,
            ConnectionGrantStatus status,
            Optional<String> terminalReason,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.connectionId = Objects.requireNonNull(connectionId, "connectionId");
        this.connectionOwner = requireOwner(organizationId, connectionOwner, "connectionOwner");
        this.grantee = requireOwner(organizationId, grantee, "grantee");
        if (!this.connectionOwner.canGrantTo(this.grantee)) {
            throw new DomainValidationException(
                    "connectionGrant.grantee",
                    "must be the same owner or a narrower owner delegated by the Organization");
        }
        this.grantedAccess = Objects.requireNonNull(grantedAccess, "grantedAccess");
        this.validFrom = Objects.requireNonNull(validFrom, "validFrom");
        this.expiresAt = requireExpiry(this.validFrom, expiresAt);
        this.status = Objects.requireNonNull(status, "status");
        this.terminalReason = requireTerminalReason(status, terminalReason);
        this.version = ProviderModelSupport.nonNegativeVersion(
                version, "connectionGrant.version");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    public static ConnectionGrant grant(
            ConnectionGrantId id,
            Connection connection,
            ProviderOwner grantee,
            ProviderAccessScope grantedAccess,
            UtcTimestamp validFrom,
            Optional<UtcTimestamp> expiresAt,
            Principal actor,
            UtcTimestamp occurredAt) {
        Connection requiredConnection = Objects.requireNonNull(connection, "connection");
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        if (!requiredConnection.isUsableAt(requiredTime)) {
            throw new DomainValidationException(
                    "connectionGrant.connectionId", "must reference a usable Connection");
        }
        PrincipalId actorId = ProviderModelSupport.activeActor(
                actor,
                requiredConnection.organizationId(),
                "connectionGrant.createdByPrincipalId");
        return new ConnectionGrant(
                id,
                requiredConnection.organizationId(),
                requiredConnection.id(),
                requiredConnection.owner(),
                grantee,
                grantedAccess,
                validFrom,
                expiresAt,
                ConnectionGrantStatus.ACTIVE,
                Optional.empty(),
                0,
                AuditMetadata.createdBy(actorId, requiredTime));
    }

    public static ConnectionGrant reconstitute(
            ConnectionGrantId id,
            OrganizationId organizationId,
            ConnectionId connectionId,
            ProviderOwner connectionOwner,
            ProviderOwner grantee,
            ProviderAccessScope grantedAccess,
            UtcTimestamp validFrom,
            Optional<UtcTimestamp> expiresAt,
            ConnectionGrantStatus status,
            Optional<String> terminalReason,
            long version,
            AuditMetadata audit) {
        return new ConnectionGrant(
                id,
                organizationId,
                connectionId,
                connectionOwner,
                grantee,
                grantedAccess,
                validFrom,
                expiresAt,
                status,
                terminalReason,
                version,
                audit);
    }

    /** Returns the exact request/grant intersection only while both facts remain usable. */
    public Optional<ProviderAccessScope> effectiveAccess(
            ProviderAccessScope requested, Connection connection, UtcTimestamp now) {
        Connection requiredConnection = Objects.requireNonNull(connection, "connection");
        UtcTimestamp requiredTime = Objects.requireNonNull(now, "now");
        if (status != ConnectionGrantStatus.ACTIVE
                || !connectionId.equals(requiredConnection.id())
                || !connectionOwner.equals(requiredConnection.owner())
                || !requiredConnection.isUsableAt(requiredTime)
                || validFrom.compareTo(requiredTime) > 0
                || expiresAt.map(value -> value.compareTo(requiredTime) <= 0).orElse(false)) {
            return Optional.empty();
        }
        return grantedAccess.intersection(Objects.requireNonNull(requested, "requested"));
    }

    public ConnectionGrant revoke(
            long expectedVersion, Principal actor, String reason, UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        ensureActive(ConnectionGrantStatus.REVOKED);
        return transition(
                ConnectionGrantStatus.REVOKED,
                ProviderModelSupport.text(reason, "connectionGrant.terminalReason", 500),
                actor,
                occurredAt);
    }

    public ConnectionGrant expire(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        ensureActive(ConnectionGrantStatus.EXPIRED);
        UtcTimestamp expiry = expiresAt.orElseThrow(() -> new DomainValidationException(
                "connectionGrant.expiresAt", "must be present before expiration"));
        if (expiry.compareTo(occurredAt) > 0) {
            throw new DomainValidationException(
                    "connectionGrant.expiresAt", "has not yet been reached");
        }
        return transition(
                ConnectionGrantStatus.EXPIRED,
                "grant expired",
                actor,
                occurredAt);
    }

    private ConnectionGrant transition(
            ConnectionGrantStatus target,
            String reason,
            Principal actor,
            UtcTimestamp occurredAt) {
        PrincipalId actorId = ProviderModelSupport.activeActor(
                actor, organizationId, "connectionGrant.updatedByPrincipalId");
        return new ConnectionGrant(
                id,
                organizationId,
                connectionId,
                connectionOwner,
                grantee,
                grantedAccess,
                validFrom,
                expiresAt,
                target,
                Optional.of(reason),
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    private void requireExpectedVersion(long expectedVersion) {
        if (version != ProviderModelSupport.nonNegativeVersion(expectedVersion, "expectedVersion")) {
            throw new OptimisticLockConflictException(
                    "ConnectionGrant", id, expectedVersion, version);
        }
    }

    private void ensureActive(ConnectionGrantStatus target) {
        if (status != ConnectionGrantStatus.ACTIVE) {
            throw new InvalidStateTransitionException("ConnectionGrant", id, status, target);
        }
    }

    private static ProviderOwner requireOwner(
            OrganizationId organizationId, ProviderOwner owner, String field) {
        ProviderOwner required = Objects.requireNonNull(owner, field);
        if (!organizationId.equals(required.organizationId())) {
            throw new DomainValidationException(
                    "connectionGrant." + field, "must belong to the Grant Organization");
        }
        return required;
    }

    private static Optional<UtcTimestamp> requireExpiry(
            UtcTimestamp validFrom, Optional<UtcTimestamp> expiresAt) {
        Optional<UtcTimestamp> required = Objects.requireNonNull(expiresAt, "expiresAt");
        if (required.filter(value -> value.compareTo(validFrom) <= 0).isPresent()) {
            throw new DomainValidationException(
                    "connectionGrant.expiresAt", "must be after validFrom");
        }
        return required;
    }

    private static Optional<String> requireTerminalReason(
            ConnectionGrantStatus status, Optional<String> reason) {
        Optional<String> required = Objects.requireNonNull(reason, "terminalReason")
                .map(value -> ProviderModelSupport.text(
                        value, "connectionGrant.terminalReason", 500));
        if (status.isTerminal() != required.isPresent()) {
            throw new DomainValidationException(
                    "connectionGrant.terminalReason",
                    "must be present exactly for a terminal status");
        }
        return required;
    }

    public ConnectionGrantId id() { return id; }
    public OrganizationId organizationId() { return organizationId; }
    public ConnectionId connectionId() { return connectionId; }
    public ProviderOwner connectionOwner() { return connectionOwner; }
    public ProviderOwner grantee() { return grantee; }
    public ProviderAccessScope grantedAccess() { return grantedAccess; }
    public UtcTimestamp validFrom() { return validFrom; }
    public Optional<UtcTimestamp> expiresAt() { return expiresAt; }
    public ConnectionGrantStatus status() { return status; }
    public Optional<String> terminalReason() { return terminalReason; }
    public long version() { return version; }
    public AuditMetadata audit() { return audit; }
}
