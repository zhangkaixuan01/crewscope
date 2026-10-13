package io.crewscope.domain.organization.role;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/**
 * An explicit organization-role grant (V68). Revocation is an irreversible terminal state; revoked
 * rows stay as the append-only audit face while the partial unique index keeps at most one ACTIVE
 * grant per (organization, principal, role).
 */
public final class OrganizationRoleGrant {

    static final int REASON_MAX_LENGTH = 500;

    private final OrganizationRoleGrantId id;
    private final OrganizationId organizationId;
    private final PrincipalId principalId;
    private final OrganizationRole role;
    private final GrantStatus status;
    private final PrincipalId grantedBy;
    private final UtcTimestamp grantedAt;
    private final UtcTimestamp revokedAt;
    private final PrincipalId revokedBy;
    private final String reason;
    private final long version;
    private final UtcTimestamp createdAt;
    private final PrincipalId createdBy;
    private final UtcTimestamp updatedAt;
    private final PrincipalId updatedBy;

    private OrganizationRoleGrant(
            OrganizationRoleGrantId id,
            OrganizationId organizationId,
            PrincipalId principalId,
            OrganizationRole role,
            GrantStatus status,
            PrincipalId grantedBy,
            UtcTimestamp grantedAt,
            UtcTimestamp revokedAt,
            PrincipalId revokedBy,
            String reason,
            long version,
            UtcTimestamp createdAt,
            PrincipalId createdBy,
            UtcTimestamp updatedAt,
            PrincipalId updatedBy) {
        this.id = Objects.requireNonNull(id, "id");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.principalId = Objects.requireNonNull(principalId, "principalId");
        this.role = Objects.requireNonNull(role, "role");
        this.status = Objects.requireNonNull(status, "status");
        this.grantedBy = Objects.requireNonNull(grantedBy, "grantedBy");
        this.grantedAt = Objects.requireNonNull(grantedAt, "grantedAt");
        this.revokedAt = revokedAt;
        this.revokedBy = revokedBy;
        this.reason = reason;
        if (version < 0) {
            throw new DomainValidationException("organizationRoleGrant.version", "must not be negative");
        }
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.updatedBy = Objects.requireNonNull(updatedBy, "updatedBy");
        if (status == GrantStatus.REVOKED) {
            Objects.requireNonNull(revokedAt, "revokedAt");
            Objects.requireNonNull(revokedBy, "revokedBy");
        } else if (revokedAt != null || revokedBy != null) {
            throw new DomainValidationException(
                    "organizationRoleGrant.revocation", "must be null while the grant is active");
        }
    }

    /** Creates a new ACTIVE grant; the persistence layer owns duplicate-active detection. */
    public static OrganizationRoleGrant grant(
            OrganizationId organizationId,
            PrincipalId principalId,
            OrganizationRole role,
            PrincipalId grantedBy,
            String reason,
            UtcTimestamp occurredAt) {
        return new OrganizationRoleGrant(
                OrganizationRoleGrantId.generate(),
                organizationId,
                principalId,
                role,
                GrantStatus.ACTIVE,
                grantedBy,
                occurredAt,
                null,
                null,
                requireReason(reason),
                0,
                occurredAt,
                grantedBy,
                occurredAt,
                grantedBy);
    }

    /** Restores a persisted grant; enforces the same invariants as the V68 table shape. */
    public static OrganizationRoleGrant reconstitute(
            OrganizationRoleGrantId id,
            OrganizationId organizationId,
            PrincipalId principalId,
            OrganizationRole role,
            GrantStatus status,
            PrincipalId grantedBy,
            UtcTimestamp grantedAt,
            UtcTimestamp revokedAt,
            PrincipalId revokedBy,
            String reason,
            long version,
            UtcTimestamp createdAt,
            PrincipalId createdBy,
            UtcTimestamp updatedAt,
            PrincipalId updatedBy) {
        return new OrganizationRoleGrant(
                id, organizationId, principalId, role, status, grantedBy, grantedAt,
                revokedAt, revokedBy, reason, version, createdAt, createdBy, updatedAt, updatedBy);
    }

    /**
     * Revokes the grant under optimistic versioning; irreversible.
     *
     * @return the revoked successor state at version {@code expectedVersion + 1}
     */
    public OrganizationRoleGrant revoke(
            long expectedVersion, PrincipalId revokedBy, String reason, UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        if (status != GrantStatus.ACTIVE) {
            throw new InvalidStateTransitionException(
                    "organizationRoleGrant", id, status, GrantStatus.REVOKED);
        }
        return new OrganizationRoleGrant(
                id,
                organizationId,
                principalId,
                role,
                GrantStatus.REVOKED,
                grantedBy,
                grantedAt,
                occurredAt,
                Objects.requireNonNull(revokedBy, "revokedBy"),
                requireReason(reason),
                expectedVersion + 1,
                createdAt,
                createdBy,
                occurredAt,
                revokedBy);
    }

    public OrganizationRoleGrantId id() {
        return id;
    }

    public OrganizationId organizationId() {
        return organizationId;
    }

    public PrincipalId principalId() {
        return principalId;
    }

    public OrganizationRole role() {
        return role;
    }

    public GrantStatus status() {
        return status;
    }

    public boolean isActive() {
        return status == GrantStatus.ACTIVE;
    }

    public PrincipalId grantedBy() {
        return grantedBy;
    }

    public UtcTimestamp grantedAt() {
        return grantedAt;
    }

    public Optional<UtcTimestamp> revokedAt() {
        return Optional.ofNullable(revokedAt);
    }

    public Optional<PrincipalId> revokedBy() {
        return Optional.ofNullable(revokedBy);
    }

    public Optional<String> reason() {
        return Optional.ofNullable(reason);
    }

    public long version() {
        return version;
    }

    public UtcTimestamp createdAt() {
        return createdAt;
    }

    public PrincipalId createdBy() {
        return createdBy;
    }

    public UtcTimestamp updatedAt() {
        return updatedAt;
    }

    public PrincipalId updatedBy() {
        return updatedBy;
    }

    private void requireExpectedVersion(long expectedVersion) {
        if (expectedVersion != version) {
            throw new io.crewscope.domain.shared.error.OptimisticLockConflictException(
                    "ORGANIZATION_ROLE_GRANT", id.value().toString(), expectedVersion, version);
        }
    }

    private static String requireReason(String reason) {
        if (reason == null) {
            return null;
        }
        String stripped = reason.strip();
        if (stripped.isEmpty()) {
            throw new DomainValidationException("organizationRoleGrant.reason", "must not be blank");
        }
        if (stripped.length() > REASON_MAX_LENGTH) {
            throw new DomainValidationException(
                    "organizationRoleGrant.reason",
                    "must not exceed " + REASON_MAX_LENGTH + " characters");
        }
        return stripped;
    }

    /** Lifecycle states of a grant; REVOKED is terminal. */
    public enum GrantStatus {
        ACTIVE,
        REVOKED
    }
}
