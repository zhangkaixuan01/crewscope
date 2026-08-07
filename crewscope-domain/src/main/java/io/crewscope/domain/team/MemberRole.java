package io.crewscope.domain.team;

import io.crewscope.domain.shared.audit.LifecycleMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Auditable grant of one TeamRole to one TeamMember in a concrete scope. */
public final class MemberRole {

    private final MemberRoleId id;
    private final TeamScope teamScope;
    private final TeamMemberId teamMemberId;
    private final TeamRoleId teamRoleId;
    private final RoleScope roleScope;
    private final PrincipalId grantedByPrincipalId;
    private final UtcTimestamp grantedAt;
    private final UtcTimestamp validFrom;
    private final Optional<UtcTimestamp> expiresAt;
    private final Optional<UtcTimestamp> revokedAt;
    private final MemberRoleStatus status;
    private final long version;
    private final LifecycleMetadata lifecycle;

    private MemberRole(
            MemberRoleId id,
            TeamScope teamScope,
            TeamMemberId teamMemberId,
            TeamRoleId teamRoleId,
            RoleScope roleScope,
            PrincipalId grantedByPrincipalId,
            UtcTimestamp grantedAt,
            UtcTimestamp validFrom,
            Optional<UtcTimestamp> expiresAt,
            Optional<UtcTimestamp> revokedAt,
            MemberRoleStatus status,
            long version,
            LifecycleMetadata lifecycle) {
        this.id = Objects.requireNonNull(id, "id");
        this.teamScope = Objects.requireNonNull(teamScope, "teamScope");
        this.teamMemberId = Objects.requireNonNull(teamMemberId, "teamMemberId");
        this.teamRoleId = Objects.requireNonNull(teamRoleId, "teamRoleId");
        this.roleScope = Objects.requireNonNull(roleScope, "roleScope");
        this.grantedByPrincipalId = Objects.requireNonNull(grantedByPrincipalId, "grantedByPrincipalId");
        this.grantedAt = Objects.requireNonNull(grantedAt, "grantedAt");
        this.validFrom = requireValidFrom(grantedAt, validFrom);
        this.expiresAt = requireExpiresAt(this.validFrom, expiresAt);
        requireExpiryForStatus(status, this.expiresAt);
        this.revokedAt = requireRevokedAt(status, grantedAt, revokedAt);
        this.status = Objects.requireNonNull(status, "status");
        this.version = requireVersion(version);
        this.lifecycle = requireTerminalTimeline(
                this.status,
                this.expiresAt,
                this.revokedAt,
                lifecycle);
    }

    /** Grants an active role after checking Team ownership, scope type and role availability. */
    public static MemberRole grant(
            MemberRoleId id,
            TeamMember member,
            TeamRole role,
            RoleScope roleScope,
            PrincipalId grantedBy,
            UtcTimestamp grantedAt,
            UtcTimestamp validFrom,
            Optional<UtcTimestamp> expiresAt) {
        TeamRole requiredRole = Objects.requireNonNull(role, "role");
        if (requiredRole.isBuiltIn(BuiltInTeamRole.TEAM_OWNER)) {
            throw new DomainValidationException(
                    "memberRole.teamRoleId",
                    "TEAM_OWNER must be granted through the Team ownership flow");
        }
        return grantInternal(
                id,
                member,
                requiredRole,
                roleScope,
                grantedBy,
                grantedAt,
                validFrom,
                expiresAt);
    }

    /** Grants TEAM_OWNER only to the active member referenced by the Team ownership fact. */
    public static MemberRole grantOwner(
            MemberRoleId id,
            Team team,
            TeamMember member,
            TeamRole role,
            PrincipalId grantedBy,
            UtcTimestamp grantedAt) {
        Team requiredTeam = Objects.requireNonNull(team, "team");
        TeamMember requiredMember = Objects.requireNonNull(member, "member");
        TeamRole requiredRole = Objects.requireNonNull(role, "role");
        if (!requiredTeam.isActive()) {
            throw new DomainValidationException(
                    "memberRole.teamScope", "must reference an active Team");
        }
        if (!requiredTeam.scope().equals(requiredMember.scope())
                || !requiredMember.canParticipate()
                || !requiredTeam.isOwner(requiredMember.id())) {
            throw new DomainValidationException(
                    "memberRole.teamMemberId",
                    "must reference the active owner of this Team");
        }
        if (!requiredRole.isBuiltIn(BuiltInTeamRole.TEAM_OWNER)) {
            throw new DomainValidationException(
                    "memberRole.teamRoleId", "must reference the built-in TEAM_OWNER role");
        }
        return grantInternal(
                id,
                requiredMember,
                requiredRole,
                RoleScope.team(),
                grantedBy,
                grantedAt,
                grantedAt,
                Optional.empty());
    }

    private static MemberRole grantInternal(
            MemberRoleId id,
            TeamMember member,
            TeamRole role,
            RoleScope roleScope,
            PrincipalId grantedBy,
            UtcTimestamp grantedAt,
            UtcTimestamp validFrom,
            Optional<UtcTimestamp> expiresAt) {
        TeamMember requiredMember = Objects.requireNonNull(member, "member");
        TeamRole requiredRole = Objects.requireNonNull(role, "role");
        RoleScope requiredScope = Objects.requireNonNull(roleScope, "roleScope");
        if (!requiredMember.scope().equals(requiredRole.scope())) {
            throw new DomainValidationException(
                    "memberRole.teamScope", "member and role must belong to the same Team");
        }
        if (!requiredRole.isGrantable()) {
            throw new DomainValidationException(
                    "memberRole.teamRoleId", "must reference an active TeamRole");
        }
        if (requiredRole.scopeType() != requiredScope.type()) {
            throw new DomainValidationException(
                    "memberRole.scope", "must match the TeamRole scope type");
        }
        return new MemberRole(
                id,
                requiredMember.scope(),
                requiredMember.id(),
                requiredRole.id(),
                requiredScope,
                grantedBy,
                grantedAt,
                validFrom,
                expiresAt,
                Optional.empty(),
                MemberRoleStatus.ACTIVE,
                0,
                LifecycleMetadata.createdAt(grantedAt));
    }

    /** Reconstitutes historical active, revoked or expired grants. */
    public static MemberRole reconstitute(
            MemberRoleId id,
            TeamScope teamScope,
            TeamMemberId teamMemberId,
            TeamRoleId teamRoleId,
            RoleScope roleScope,
            PrincipalId grantedByPrincipalId,
            UtcTimestamp grantedAt,
            UtcTimestamp validFrom,
            Optional<UtcTimestamp> expiresAt,
            Optional<UtcTimestamp> revokedAt,
            MemberRoleStatus status,
            long version,
            LifecycleMetadata lifecycle) {
        return new MemberRole(
                id,
                teamScope,
                teamMemberId,
                teamRoleId,
                roleScope,
                grantedByPrincipalId,
                grantedAt,
                validFrom,
                expiresAt,
                revokedAt,
                status,
                version,
                lifecycle);
    }

    public MemberRole revoke(UtcTimestamp occurredAt) {
        ensureActive(MemberRoleStatus.REVOKED);
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        if (requiredTime.compareTo(grantedAt) < 0) {
            throw new DomainValidationException(
                    "memberRole.revokedAt", "must not be before grantedAt");
        }
        return copy(MemberRoleStatus.REVOKED, Optional.of(requiredTime), requiredTime);
    }

    /** Marks a time-bounded grant expired only after its configured expiry instant. */
    public MemberRole expire(UtcTimestamp occurredAt) {
        ensureActive(MemberRoleStatus.EXPIRED);
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        UtcTimestamp configuredExpiry = expiresAt.orElseThrow(() -> new DomainValidationException(
                "memberRole.expiresAt", "is required before a grant can expire"));
        if (requiredTime.compareTo(configuredExpiry) < 0) {
            throw new DomainValidationException(
                    "memberRole.expiresAt", "has not been reached");
        }
        return copy(MemberRoleStatus.EXPIRED, Optional.empty(), requiredTime);
    }

    /**
     * Evaluates whether this grant was effective at an instant; caller separately verifies the
     * current member and role state when making a live authorization decision.
     */
    public boolean isEffectiveAt(UtcTimestamp occurredAt) {
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        return requiredTime.compareTo(validFrom) >= 0
                && expiresAt.map(expiry -> requiredTime.compareTo(expiry) < 0).orElse(true)
                && revokedAt.map(revoked -> requiredTime.compareTo(revoked) < 0).orElse(true);
    }

    private MemberRole copy(
            MemberRoleStatus target,
            Optional<UtcTimestamp> targetRevokedAt,
            UtcTimestamp occurredAt) {
        return new MemberRole(
                id,
                teamScope,
                teamMemberId,
                teamRoleId,
                roleScope,
                grantedByPrincipalId,
                grantedAt,
                validFrom,
                expiresAt,
                targetRevokedAt,
                target,
                version + 1,
                lifecycle.modifiedAt(occurredAt));
    }

    private void ensureActive(MemberRoleStatus target) {
        if (status != MemberRoleStatus.ACTIVE) {
            throw new InvalidStateTransitionException("MemberRole", id, status, target);
        }
    }

    public MemberRoleId id() {
        return id;
    }

    public TeamScope teamScope() {
        return teamScope;
    }

    public TeamMemberId teamMemberId() {
        return teamMemberId;
    }

    public TeamRoleId teamRoleId() {
        return teamRoleId;
    }

    public RoleScope roleScope() {
        return roleScope;
    }

    public PrincipalId grantedByPrincipalId() {
        return grantedByPrincipalId;
    }

    public UtcTimestamp grantedAt() {
        return grantedAt;
    }

    public UtcTimestamp validFrom() {
        return validFrom;
    }

    public Optional<UtcTimestamp> expiresAt() {
        return expiresAt;
    }

    public Optional<UtcTimestamp> revokedAt() {
        return revokedAt;
    }

    public MemberRoleStatus status() {
        return status;
    }

    public long version() {
        return version;
    }

    public LifecycleMetadata lifecycle() {
        return lifecycle;
    }

    private static UtcTimestamp requireValidFrom(
            UtcTimestamp grantedAt, UtcTimestamp validFrom) {
        UtcTimestamp requiredValidFrom = Objects.requireNonNull(validFrom, "validFrom");
        if (requiredValidFrom.compareTo(grantedAt) < 0) {
            throw new DomainValidationException(
                    "memberRole.validFrom", "must not be before grantedAt");
        }
        return requiredValidFrom;
    }

    private static Optional<UtcTimestamp> requireExpiresAt(
            UtcTimestamp validFrom, Optional<UtcTimestamp> expiresAt) {
        Optional<UtcTimestamp> requiredExpiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (requiredExpiresAt.filter(expiry -> expiry.compareTo(validFrom) <= 0).isPresent()) {
            throw new DomainValidationException(
                    "memberRole.expiresAt", "must be after validFrom");
        }
        return requiredExpiresAt;
    }

    private static void requireExpiryForStatus(
            MemberRoleStatus status, Optional<UtcTimestamp> expiresAt) {
        if (Objects.requireNonNull(status, "status") == MemberRoleStatus.EXPIRED
                && expiresAt.isEmpty()) {
            throw new DomainValidationException(
                    "memberRole.expiresAt", "is required for an expired grant");
        }
    }

    private static Optional<UtcTimestamp> requireRevokedAt(
            MemberRoleStatus status,
            UtcTimestamp grantedAt,
            Optional<UtcTimestamp> revokedAt) {
        MemberRoleStatus requiredStatus = Objects.requireNonNull(status, "status");
        Optional<UtcTimestamp> requiredRevokedAt = Objects.requireNonNull(revokedAt, "revokedAt");
        if (requiredStatus == MemberRoleStatus.REVOKED && requiredRevokedAt.isEmpty()) {
            throw new DomainValidationException(
                    "memberRole.revokedAt", "is required for a revoked grant");
        }
        if (requiredStatus != MemberRoleStatus.REVOKED && requiredRevokedAt.isPresent()) {
            throw new DomainValidationException(
                    "memberRole.revokedAt", "is only allowed for a revoked grant");
        }
        if (requiredRevokedAt.filter(value -> value.compareTo(grantedAt) < 0).isPresent()) {
            throw new DomainValidationException(
                    "memberRole.revokedAt", "must not be before grantedAt");
        }
        return requiredRevokedAt;
    }

    private static long requireVersion(long value) {
        if (value < 0) {
            throw new DomainValidationException("memberRole.version", "must not be negative");
        }
        return value;
    }

    /** Rejects persistence snapshots whose terminal fact has not reached the row update time. */
    private static LifecycleMetadata requireTerminalTimeline(
            MemberRoleStatus status,
            Optional<UtcTimestamp> expiresAt,
            Optional<UtcTimestamp> revokedAt,
            LifecycleMetadata lifecycle) {
        LifecycleMetadata requiredLifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        Optional<UtcTimestamp> terminalAt = switch (status) {
            case REVOKED -> revokedAt;
            case EXPIRED -> expiresAt;
            case ACTIVE -> Optional.empty();
        };
        if (terminalAt.filter(time -> requiredLifecycle.updatedAt().compareTo(time) < 0).isPresent()) {
            throw new DomainValidationException(
                    "memberRole.lifecycle.updatedAt",
                    "must not be before the terminal role fact time");
        }
        return requiredLifecycle;
    }
}
