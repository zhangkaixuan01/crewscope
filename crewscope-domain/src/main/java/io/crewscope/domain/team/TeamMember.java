package io.crewscope.domain.team;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
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

/** A user's durable identity and participation state inside one Team. */
public final class TeamMember {

    private static final Map<TeamMemberStatus, Set<TeamMemberStatus>> ALLOWED_TRANSITIONS = Map.of(
            TeamMemberStatus.INVITED,
            EnumSet.of(TeamMemberStatus.ACTIVE, TeamMemberStatus.REMOVED),
            TeamMemberStatus.ACTIVE,
            EnumSet.of(
                    TeamMemberStatus.SUSPENDED,
                    TeamMemberStatus.LEFT,
                    TeamMemberStatus.REMOVED),
            TeamMemberStatus.SUSPENDED,
            EnumSet.of(
                    TeamMemberStatus.ACTIVE,
                    TeamMemberStatus.LEFT,
                    TeamMemberStatus.REMOVED),
            TeamMemberStatus.LEFT,
            EnumSet.of(TeamMemberStatus.ACTIVE, TeamMemberStatus.REMOVED),
            TeamMemberStatus.REMOVED,
            EnumSet.of(TeamMemberStatus.INVITED));

    private final TeamMemberId id;
    private final TeamScope scope;
    private final PrincipalId userPrincipalId;
    private final TeamMemberStatus status;
    private final TeamJoinMethod joinMethod;
    private final Optional<PrincipalId> invitedByPrincipalId;
    private final Optional<UtcTimestamp> joinedAt;
    private final Optional<UtcTimestamp> lastActiveAt;
    private final long version;
    private final LifecycleMetadata lifecycle;

    private TeamMember(
            TeamMemberId id,
            TeamScope scope,
            PrincipalId userPrincipalId,
            TeamMemberStatus status,
            TeamJoinMethod joinMethod,
            Optional<PrincipalId> invitedByPrincipalId,
            Optional<UtcTimestamp> joinedAt,
            Optional<UtcTimestamp> lastActiveAt,
            long version,
            LifecycleMetadata lifecycle) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.userPrincipalId = Objects.requireNonNull(userPrincipalId, "userPrincipalId");
        this.status = Objects.requireNonNull(status, "status");
        this.joinMethod = Objects.requireNonNull(joinMethod, "joinMethod");
        this.invitedByPrincipalId = requireInviter(joinMethod, invitedByPrincipalId);
        this.joinedAt = requireJoinedAt(status, joinedAt);
        this.lastActiveAt = requireLastActiveAt(this.joinedAt, lastActiveAt);
        this.version = requireVersion(version);
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    /** Creates a pending invitation for an active USER Principal. */
    public static TeamMember invite(
            TeamMemberId id,
            TeamScope scope,
            Principal userPrincipal,
            PrincipalId invitedBy,
            UtcTimestamp occurredAt) {
        return new TeamMember(
                id,
                scope,
                requireActiveUser(userPrincipal, scope),
                TeamMemberStatus.INVITED,
                TeamJoinMethod.INVITATION,
                Optional.of(Objects.requireNonNull(invitedBy, "invitedBy")),
                Optional.empty(),
                Optional.empty(),
                0,
                LifecycleMetadata.createdAt(occurredAt));
    }

    /** Creates an active membership from a trusted non-invitation identity source. */
    public static TeamMember join(
            TeamMemberId id,
            TeamScope scope,
            Principal userPrincipal,
            TeamJoinMethod joinMethod,
            UtcTimestamp occurredAt) {
        TeamJoinMethod requiredMethod = Objects.requireNonNull(joinMethod, "joinMethod");
        if (requiredMethod == TeamJoinMethod.INVITATION) {
            throw new DomainValidationException(
                    "teamMember.joinMethod", "INVITATION must enter through the invitation flow");
        }
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        return new TeamMember(
                id,
                scope,
                requireActiveUser(userPrincipal, scope),
                TeamMemberStatus.ACTIVE,
                requiredMethod,
                Optional.empty(),
                Optional.of(requiredTime),
                Optional.empty(),
                0,
                LifecycleMetadata.createdAt(requiredTime));
    }

    /** Reconstitutes a committed membership without replaying a join or state transition. */
    public static TeamMember reconstitute(
            TeamMemberId id,
            TeamScope scope,
            PrincipalId userPrincipalId,
            TeamMemberStatus status,
            TeamJoinMethod joinMethod,
            Optional<PrincipalId> invitedByPrincipalId,
            Optional<UtcTimestamp> joinedAt,
            Optional<UtcTimestamp> lastActiveAt,
            long version,
            LifecycleMetadata lifecycle) {
        return new TeamMember(
                id,
                scope,
                userPrincipalId,
                status,
                joinMethod,
                invitedByPrincipalId,
                joinedAt,
                lastActiveAt,
                version,
                lifecycle);
    }

    /** Activates an invited, suspended or previously left member after rechecking its USER. */
    public TeamMember activate(Principal userPrincipal, UtcTimestamp occurredAt) {
        ensureTransition(TeamMemberStatus.ACTIVE);
        requireCurrentUser(userPrincipal);
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        boolean startsNewParticipation = status == TeamMemberStatus.INVITED || status == TeamMemberStatus.LEFT;
        return copy(
                TeamMemberStatus.ACTIVE,
                startsNewParticipation ? Optional.of(requiredTime) : joinedAt,
                startsNewParticipation ? Optional.empty() : lastActiveAt,
                requiredTime);
    }

    /**
     * Starts a new invitation for an administratively removed membership. The stable membership ID
     * is reused because the database permits only one membership per Team and USER Principal.
     */
    public TeamMember reinvite(
            Principal userPrincipal, PrincipalId invitedBy, UtcTimestamp occurredAt) {
        ensureTransition(TeamMemberStatus.INVITED);
        requireCurrentUser(userPrincipal);
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        return copy(
                TeamMemberStatus.INVITED,
                TeamJoinMethod.INVITATION,
                Optional.of(Objects.requireNonNull(invitedBy, "invitedBy")),
                Optional.empty(),
                Optional.empty(),
                requiredTime);
    }

    public TeamMember suspend(UtcTimestamp occurredAt) {
        return transitionTo(TeamMemberStatus.SUSPENDED, occurredAt);
    }

    public TeamMember leave(UtcTimestamp occurredAt) {
        return transitionTo(TeamMemberStatus.LEFT, occurredAt);
    }

    public TeamMember remove(UtcTimestamp occurredAt) {
        return transitionTo(TeamMemberStatus.REMOVED, occurredAt);
    }

    /** Records presence only while the member can participate in Team work. */
    public TeamMember recordActivity(UtcTimestamp occurredAt) {
        if (!canParticipate()) {
            throw new DomainValidationException(
                    "teamMember.lastActiveAt", "can only be recorded for an active member");
        }
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        if (lastActiveAt.filter(previous -> requiredTime.compareTo(previous) < 0).isPresent()) {
            throw new DomainValidationException(
                    "teamMember.lastActiveAt", "must not be before the current lastActiveAt");
        }
        return new TeamMember(
                id,
                scope,
                userPrincipalId,
                status,
                joinMethod,
                invitedByPrincipalId,
                joinedAt,
                Optional.of(requiredTime),
                version + 1,
                lifecycle.modifiedAt(requiredTime));
    }

    public boolean canParticipate() {
        return status == TeamMemberStatus.ACTIVE;
    }

    private TeamMember transitionTo(TeamMemberStatus target, UtcTimestamp occurredAt) {
        ensureTransition(target);
        return copy(target, joinedAt, lastActiveAt, occurredAt);
    }

    private TeamMember copy(
            TeamMemberStatus target,
            Optional<UtcTimestamp> targetJoinedAt,
            Optional<UtcTimestamp> targetLastActiveAt,
            UtcTimestamp occurredAt) {
        return copy(
                target,
                joinMethod,
                invitedByPrincipalId,
                targetJoinedAt,
                targetLastActiveAt,
                occurredAt);
    }

    private TeamMember copy(
            TeamMemberStatus target,
            TeamJoinMethod targetJoinMethod,
            Optional<PrincipalId> targetInvitedByPrincipalId,
            Optional<UtcTimestamp> targetJoinedAt,
            Optional<UtcTimestamp> targetLastActiveAt,
            UtcTimestamp occurredAt) {
        return new TeamMember(
                id,
                scope,
                userPrincipalId,
                target,
                targetJoinMethod,
                targetInvitedByPrincipalId,
                targetJoinedAt,
                targetLastActiveAt,
                version + 1,
                lifecycle.modifiedAt(occurredAt));
    }

    private void ensureTransition(TeamMemberStatus target) {
        Objects.requireNonNull(target, "target");
        if (!ALLOWED_TRANSITIONS.get(status).contains(target)) {
            throw new InvalidStateTransitionException("TeamMember", id, status, target);
        }
    }

    public TeamMemberId id() {
        return id;
    }

    public TeamScope scope() {
        return scope;
    }

    public PrincipalId userPrincipalId() {
        return userPrincipalId;
    }

    public TeamMemberStatus status() {
        return status;
    }

    public TeamJoinMethod joinMethod() {
        return joinMethod;
    }

    public Optional<PrincipalId> invitedByPrincipalId() {
        return invitedByPrincipalId;
    }

    public Optional<UtcTimestamp> joinedAt() {
        return joinedAt;
    }

    public Optional<UtcTimestamp> lastActiveAt() {
        return lastActiveAt;
    }

    public long version() {
        return version;
    }

    public LifecycleMetadata lifecycle() {
        return lifecycle;
    }

    private void requireCurrentUser(Principal principal) {
        PrincipalId currentPrincipalId = requireActiveUser(principal, scope);
        if (!userPrincipalId.equals(currentPrincipalId)) {
            throw new DomainValidationException(
                    "teamMember.userPrincipalId", "must match the membership USER Principal");
        }
    }

    private static PrincipalId requireActiveUser(Principal principal, TeamScope scope) {
        Principal requiredPrincipal = Objects.requireNonNull(principal, "userPrincipal");
        if (requiredPrincipal.type() != PrincipalType.USER) {
            throw new DomainValidationException(
                    "teamMember.userPrincipalId", "must reference a USER Principal");
        }
        if (!requiredPrincipal.canAct()) {
            throw new DomainValidationException(
                    "teamMember.userPrincipalId", "must reference an active Principal");
        }
        if (!requiredPrincipal.scope().organizationId().equals(scope.organizationId())) {
            throw new DomainValidationException(
                    "teamMember.userPrincipalId", "must belong to the membership Organization");
        }
        return requiredPrincipal.id();
    }

    private static Optional<PrincipalId> requireInviter(
            TeamJoinMethod joinMethod, Optional<PrincipalId> invitedBy) {
        Optional<PrincipalId> requiredInviter = Objects.requireNonNull(invitedBy, "invitedBy");
        if (joinMethod == TeamJoinMethod.INVITATION && requiredInviter.isEmpty()) {
            throw new DomainValidationException(
                    "teamMember.invitedByPrincipalId", "is required for an invitation");
        }
        if (joinMethod != TeamJoinMethod.INVITATION && requiredInviter.isPresent()) {
            throw new DomainValidationException(
                    "teamMember.invitedByPrincipalId", "is only allowed for an invitation");
        }
        return requiredInviter;
    }

    private static Optional<UtcTimestamp> requireJoinedAt(
            TeamMemberStatus status, Optional<UtcTimestamp> joinedAt) {
        Optional<UtcTimestamp> requiredJoinedAt = Objects.requireNonNull(joinedAt, "joinedAt");
        if (status == TeamMemberStatus.ACTIVE && requiredJoinedAt.isEmpty()) {
            throw new DomainValidationException(
                    "teamMember.joinedAt", "is required for an active member");
        }
        return requiredJoinedAt;
    }

    private static Optional<UtcTimestamp> requireLastActiveAt(
            Optional<UtcTimestamp> joinedAt, Optional<UtcTimestamp> lastActiveAt) {
        Optional<UtcTimestamp> requiredLastActiveAt =
                Objects.requireNonNull(lastActiveAt, "lastActiveAt");
        if (requiredLastActiveAt.isPresent() && joinedAt.isEmpty()) {
            throw new DomainValidationException(
                    "teamMember.lastActiveAt", "requires a joinedAt timestamp");
        }
        if (requiredLastActiveAt.isPresent()
                && requiredLastActiveAt.orElseThrow().compareTo(joinedAt.orElseThrow()) < 0) {
            throw new DomainValidationException(
                    "teamMember.lastActiveAt", "must not be before joinedAt");
        }
        return requiredLastActiveAt;
    }

    private static long requireVersion(long value) {
        if (value < 0) {
            throw new DomainValidationException("teamMember.version", "must not be negative");
        }
        return value;
    }
}
