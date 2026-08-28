package io.crewscope.domain.team;

import io.crewscope.domain.identity.AccountOrganizationBinding;
import io.crewscope.domain.identity.NormalizedEmail;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.audit.LifecycleMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** One-time invitation to join one Team with one product-owned Team role. */
public final class TeamInvitation {

    private static final Map<TeamInvitationStatus, Set<TeamInvitationStatus>> ALLOWED_TRANSITIONS =
            Map.of(
                    TeamInvitationStatus.PENDING,
                    EnumSet.of(
                            TeamInvitationStatus.ACCEPTED,
                            TeamInvitationStatus.REVOKED,
                            TeamInvitationStatus.EXPIRED),
                    TeamInvitationStatus.ACCEPTED,
                    EnumSet.noneOf(TeamInvitationStatus.class),
                    TeamInvitationStatus.REVOKED,
                    EnumSet.noneOf(TeamInvitationStatus.class),
                    TeamInvitationStatus.EXPIRED,
                    EnumSet.noneOf(TeamInvitationStatus.class));

    private final TeamInvitationId id;
    private final TeamScope scope;
    private final PrincipalId invitedByPrincipalId;
    private final Optional<NormalizedEmail> targetEmail;
    private final BuiltInTeamRole targetRole;
    private final InvitationTokenDigest tokenDigest;
    private final UtcTimestamp expiresAt;
    private final TeamInvitationStatus status;
    private final Optional<UserAccountId> acceptedByAccountId;
    private final Optional<TeamMemberId> acceptedMemberId;
    private final Optional<UtcTimestamp> resolvedAt;
    private final long version;
    private final LifecycleMetadata lifecycle;

    private TeamInvitation(
            TeamInvitationId id,
            TeamScope scope,
            PrincipalId invitedByPrincipalId,
            Optional<NormalizedEmail> targetEmail,
            BuiltInTeamRole targetRole,
            InvitationTokenDigest tokenDigest,
            UtcTimestamp expiresAt,
            TeamInvitationStatus status,
            Optional<UserAccountId> acceptedByAccountId,
            Optional<TeamMemberId> acceptedMemberId,
            Optional<UtcTimestamp> resolvedAt,
            long version,
            LifecycleMetadata lifecycle) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.invitedByPrincipalId = Objects.requireNonNull(
                invitedByPrincipalId, "invitedByPrincipalId");
        this.targetEmail = Objects.requireNonNull(targetEmail, "targetEmail");
        this.targetRole = requireTargetRole(targetRole);
        this.tokenDigest = Objects.requireNonNull(tokenDigest, "tokenDigest");
        this.expiresAt = requireExpiry(expiresAt, lifecycle);
        this.status = Objects.requireNonNull(status, "status");
        this.acceptedByAccountId = Objects.requireNonNull(
                acceptedByAccountId, "acceptedByAccountId");
        this.acceptedMemberId = Objects.requireNonNull(acceptedMemberId, "acceptedMemberId");
        this.resolvedAt = requireTerminalShape(
                this.status,
                this.acceptedByAccountId,
                this.acceptedMemberId,
                resolvedAt,
                this.expiresAt,
                lifecycle);
        this.version = requireVersion(this.status, version);
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    /** Creates a pending invitation for an active Team and active USER inviter. */
    public static TeamInvitation issue(
            TeamInvitationId id,
            Team team,
            Principal invitedBy,
            Optional<NormalizedEmail> targetEmail,
            BuiltInTeamRole targetRole,
            InvitationTokenDigest tokenDigest,
            UtcTimestamp expiresAt,
            UtcTimestamp occurredAt) {
        Team requiredTeam = requireActiveTeam(team);
        Principal inviter = requireInviter(invitedBy, requiredTeam.scope());
        UtcTimestamp issuedAt = Objects.requireNonNull(occurredAt, "occurredAt");
        return new TeamInvitation(
                id,
                requiredTeam.scope(),
                inviter.id(),
                targetEmail,
                targetRole,
                tokenDigest,
                expiresAt,
                TeamInvitationStatus.PENDING,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                LifecycleMetadata.createdAt(issuedAt));
    }

    /** Reconstitutes a committed invitation without replaying a terminal transition. */
    public static TeamInvitation reconstitute(
            TeamInvitationId id,
            TeamScope scope,
            PrincipalId invitedByPrincipalId,
            Optional<NormalizedEmail> targetEmail,
            BuiltInTeamRole targetRole,
            InvitationTokenDigest tokenDigest,
            UtcTimestamp expiresAt,
            TeamInvitationStatus status,
            Optional<UserAccountId> acceptedByAccountId,
            Optional<TeamMemberId> acceptedMemberId,
            Optional<UtcTimestamp> resolvedAt,
            long version,
            LifecycleMetadata lifecycle) {
        return new TeamInvitation(
                id,
                scope,
                invitedByPrincipalId,
                targetEmail,
                targetRole,
                tokenDigest,
                expiresAt,
                status,
                acceptedByAccountId,
                acceptedMemberId,
                resolvedAt,
                version,
                lifecycle);
    }

    /**
     * Consumes this invitation for one stable active Membership after validating the full identity
     * and Team chain. The caller commits membership, role grant and invitation in one transaction.
     */
    public TeamInvitation accept(
            UserAccount account,
            AccountOrganizationBinding binding,
            Principal userPrincipal,
            Team team,
            TeamMember membership,
            InvitationTokenDigest presentedDigest,
            UtcTimestamp occurredAt) {
        ensureTransition(TeamInvitationStatus.ACCEPTED);
        UtcTimestamp acceptedAt = requireBeforeExpiry(occurredAt, "accept");
        UserAccount requiredAccount = Objects.requireNonNull(account, "account");
        if (!requiredAccount.canAuthenticate()) {
            throw new DomainValidationException(
                    "teamInvitation.account", "must be ACTIVE");
        }
        targetEmail.ifPresent(email -> {
            if (!email.equals(requiredAccount.normalizedEmail())) {
                throw new DomainValidationException(
                        "teamInvitation.targetEmail", "must match the accepting Account");
            }
        });
        AccountOrganizationBinding requiredBinding =
                Objects.requireNonNull(binding, "binding");
        Principal requiredPrincipal = Objects.requireNonNull(userPrincipal, "userPrincipal");
        if (!requiredBinding.isUsable()
                || !requiredBinding.accountId().equals(requiredAccount.id())
                || !requiredBinding.organizationId().equals(scope.organizationId())
                || !requiredBinding.isCompatibleWith(requiredPrincipal)) {
            throw new DomainValidationException(
                    "teamInvitation.binding",
                    "must resolve the Account to an active USER Principal in this Organization");
        }
        Team requiredTeam = requireActiveTeam(team);
        if (!scope.equals(requiredTeam.scope())) {
            throw new DomainValidationException(
                    "teamInvitation.team", "must match the invitation Team");
        }
        TeamMember requiredMembership = Objects.requireNonNull(membership, "membership");
        if (!scope.equals(requiredMembership.scope())
                || !requiredMembership.canParticipate()
                || !requiredMembership.userPrincipalId().equals(requiredPrincipal.id())) {
            throw new DomainValidationException(
                    "teamInvitation.membership",
                    "must be the active Membership for the bound USER Principal");
        }
        if (!tokenDigest.matches(presentedDigest)) {
            throw new DomainValidationException(
                    "teamInvitation.tokenDigest", "must match the invitation");
        }
        return copy(
                TeamInvitationStatus.ACCEPTED,
                Optional.of(requiredAccount.id()),
                Optional.of(requiredMembership.id()),
                acceptedAt);
    }

    /** Revokes an unconsumed invitation before its expiry boundary. */
    public TeamInvitation revoke(UtcTimestamp occurredAt) {
        ensureTransition(TeamInvitationStatus.REVOKED);
        return copy(
                TeamInvitationStatus.REVOKED,
                Optional.empty(),
                Optional.empty(),
                requireBeforeExpiry(occurredAt, "revoke"));
    }

    /** Marks an unconsumed invitation expired at or after its configured boundary. */
    public TeamInvitation expire(UtcTimestamp occurredAt) {
        ensureTransition(TeamInvitationStatus.EXPIRED);
        UtcTimestamp expiredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        if (expiredAt.compareTo(expiresAt) < 0) {
            throw new DomainValidationException(
                    "teamInvitation.expiresAt", "has not been reached");
        }
        return copy(
                TeamInvitationStatus.EXPIRED,
                Optional.empty(),
                Optional.empty(),
                expiredAt);
    }

    public boolean isPendingAt(UtcTimestamp occurredAt) {
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        return status == TeamInvitationStatus.PENDING && requiredTime.compareTo(expiresAt) < 0;
    }

    public boolean targets(UserAccount account) {
        UserAccount required = Objects.requireNonNull(account, "account");
        return targetEmail.map(email -> email.equals(required.normalizedEmail())).orElse(true);
    }

    private TeamInvitation copy(
            TeamInvitationStatus target,
            Optional<UserAccountId> targetAccountId,
            Optional<TeamMemberId> targetMemberId,
            UtcTimestamp occurredAt) {
        return new TeamInvitation(
                id,
                scope,
                invitedByPrincipalId,
                targetEmail,
                targetRole,
                tokenDigest,
                expiresAt,
                target,
                targetAccountId,
                targetMemberId,
                Optional.of(occurredAt),
                nextVersion(),
                lifecycle.modifiedAt(occurredAt));
    }

    private void ensureTransition(TeamInvitationStatus target) {
        Objects.requireNonNull(target, "target");
        if (!ALLOWED_TRANSITIONS.get(status).contains(target)) {
            throw new InvalidStateTransitionException("TeamInvitation", id, status, target);
        }
    }

    private UtcTimestamp requireBeforeExpiry(UtcTimestamp occurredAt, String operation) {
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        if (requiredTime.compareTo(expiresAt) >= 0) {
            throw new DomainValidationException(
                    "teamInvitation.expiresAt", "must be in the future to " + operation);
        }
        return requiredTime;
    }

    public TeamInvitationId id() {
        return id;
    }

    public TeamScope scope() {
        return scope;
    }

    public PrincipalId invitedByPrincipalId() {
        return invitedByPrincipalId;
    }

    public Optional<NormalizedEmail> targetEmail() {
        return targetEmail;
    }

    public BuiltInTeamRole targetRole() {
        return targetRole;
    }

    public InvitationTokenDigest tokenDigest() {
        return tokenDigest;
    }

    public UtcTimestamp expiresAt() {
        return expiresAt;
    }

    public TeamInvitationStatus status() {
        return status;
    }

    public Optional<UserAccountId> acceptedByAccountId() {
        return acceptedByAccountId;
    }

    public Optional<TeamMemberId> acceptedMemberId() {
        return acceptedMemberId;
    }

    public Optional<UtcTimestamp> resolvedAt() {
        return resolvedAt;
    }

    public long version() {
        return version;
    }

    public LifecycleMetadata lifecycle() {
        return lifecycle;
    }

    private static Team requireActiveTeam(Team team) {
        Team required = Objects.requireNonNull(team, "team");
        if (!required.isActive()) {
            throw new DomainValidationException("teamInvitation.team", "must be ACTIVE");
        }
        return required;
    }

    private static Principal requireInviter(Principal invitedBy, TeamScope scope) {
        Principal required = Objects.requireNonNull(invitedBy, "invitedBy");
        if (required.type() != PrincipalType.USER
                || !required.canAct()
                || !required.scope().organizationId().equals(scope.organizationId())
                || required.scope().teamId().isPresent()) {
            throw new DomainValidationException(
                    "teamInvitation.invitedByPrincipalId",
                    "must reference an active Organization USER Principal");
        }
        return required;
    }

    private static BuiltInTeamRole requireTargetRole(BuiltInTeamRole value) {
        BuiltInTeamRole required = Objects.requireNonNull(value, "targetRole");
        if (required == BuiltInTeamRole.TEAM_OWNER) {
            throw new DomainValidationException(
                    "teamInvitation.targetRole",
                    "TEAM_OWNER must use the Team ownership transfer flow");
        }
        return required;
    }

    private static UtcTimestamp requireExpiry(
            UtcTimestamp expiresAt, LifecycleMetadata lifecycle) {
        UtcTimestamp requiredExpiry = Objects.requireNonNull(expiresAt, "expiresAt");
        LifecycleMetadata requiredLifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        if (requiredExpiry.compareTo(requiredLifecycle.createdAt()) <= 0) {
            throw new DomainValidationException(
                    "teamInvitation.expiresAt", "must be after the invitation creation time");
        }
        return requiredExpiry;
    }

    private static Optional<UtcTimestamp> requireTerminalShape(
            TeamInvitationStatus status,
            Optional<UserAccountId> acceptedAccount,
            Optional<TeamMemberId> acceptedMember,
            Optional<UtcTimestamp> resolvedAt,
            UtcTimestamp expiresAt,
            LifecycleMetadata lifecycle) {
        Optional<UtcTimestamp> requiredResolvedAt = Objects.requireNonNull(resolvedAt, "resolvedAt");
        boolean accepted = status == TeamInvitationStatus.ACCEPTED;
        if (acceptedAccount.isPresent() != accepted
                || acceptedMember.isPresent() != accepted
                || requiredResolvedAt.isPresent() != status.isTerminal()) {
            throw new DomainValidationException(
                    "teamInvitation.status", "must match its acceptance and resolution facts");
        }
        requiredResolvedAt.ifPresent(resolved -> {
            if (resolved.compareTo(lifecycle.createdAt()) < 0
                    || !resolved.equals(lifecycle.updatedAt())) {
                throw new DomainValidationException(
                        "teamInvitation.resolvedAt", "must close the invitation lifecycle");
            }
            if (status == TeamInvitationStatus.EXPIRED && resolved.compareTo(expiresAt) < 0) {
                throw new DomainValidationException(
                        "teamInvitation.resolvedAt", "must be at or after expiresAt");
            }
            if (status != TeamInvitationStatus.EXPIRED && resolved.compareTo(expiresAt) >= 0) {
                throw new DomainValidationException(
                        "teamInvitation.resolvedAt", "must be before expiresAt");
            }
        });
        return requiredResolvedAt;
    }

    private long nextVersion() {
        if (version == Long.MAX_VALUE) {
            throw new DomainValidationException("teamInvitation.version", "must not overflow");
        }
        return version + 1;
    }

    private static long requireVersion(TeamInvitationStatus status, long value) {
        long requiredVersion = status == TeamInvitationStatus.PENDING ? 0 : 1;
        if (value != requiredVersion) {
            throw new DomainValidationException(
                    "teamInvitation.version",
                    "must be " + requiredVersion + " when status is " + status);
        }
        return value;
    }
}
