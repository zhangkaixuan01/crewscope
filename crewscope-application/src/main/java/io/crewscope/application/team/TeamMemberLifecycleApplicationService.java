package io.crewscope.application.team;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.LastOwnerProtectionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.BuiltInTeamRole;
import io.crewscope.domain.team.MemberRole;
import io.crewscope.domain.team.MemberRoleId;
import io.crewscope.domain.team.MemberRoleStatus;
import io.crewscope.domain.team.RoleScope;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamPermission;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.event.MemberRoleGranted;
import io.crewscope.domain.team.event.MemberRoleRevoked;
import io.crewscope.domain.team.event.TeamMemberActivated;
import io.crewscope.domain.team.event.TeamMemberLeft;
import io.crewscope.domain.team.event.TeamMemberRemoved;
import io.crewscope.domain.team.event.TeamMemberSuspended;
import io.crewscope.domain.team.event.TeamOwnershipTransferred;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Member lifecycle commands under ADR-038 §1: every command validates the actor, locks the Team
 * row, re-reads membership facts inside the lock, protects the last effective Owner, applies the
 * domain transition and publishes facts — in one transaction, serialized Team-first.
 */
public final class TeamMemberLifecycleApplicationService {

  private static final String TEAM_AGGREGATE = "TEAM";
  private static final String TEAM_MEMBER_AGGREGATE = "TEAM_MEMBER";
  private static final String SUSPEND_TEAM_MEMBER = "SUSPEND_TEAM_MEMBER";
  private static final String ACTIVATE_TEAM_MEMBER = "ACTIVATE_TEAM_MEMBER";
  private static final String REMOVE_TEAM_MEMBER = "REMOVE_TEAM_MEMBER";
  private static final String LEAVE_TEAM = "LEAVE_TEAM";
  private static final String TRANSFER_TEAM_OWNERSHIP = "TRANSFER_TEAM_OWNERSHIP";
  private static final String GRANT_MEMBER_ROLE = "GRANT_MEMBER_ROLE";
  private static final String REVOKE_MEMBER_ROLE = "REVOKE_MEMBER_ROLE";

  private final TeamRepository teamRepository;
  private final TeamMemberRepository teamMemberRepository;
  private final TeamMembershipQuery membershipQuery;
  private final TeamRoleRepository teamRoleRepository;
  private final MemberRoleRepository memberRoleRepository;
  private final PrincipalRepository principalRepository;
  private final DomainEventStore domainEventStore;
  private final OutboxRepository outboxRepository;
  private final CommandReceiptStore receiptStore;
  private final TransactionExecutor transactionExecutor;
  private final TimeProvider timeProvider;

  public TeamMemberLifecycleApplicationService(
      TeamRepository teamRepository,
      TeamMemberRepository teamMemberRepository,
      TeamMembershipQuery membershipQuery,
      TeamRoleRepository teamRoleRepository,
      MemberRoleRepository memberRoleRepository,
      PrincipalRepository principalRepository,
      DomainEventStore domainEventStore,
      OutboxRepository outboxRepository,
      CommandReceiptStore receiptStore,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider) {
    this.teamRepository = Objects.requireNonNull(teamRepository, "teamRepository");
    this.teamMemberRepository =
        Objects.requireNonNull(teamMemberRepository, "teamMemberRepository");
    this.membershipQuery = Objects.requireNonNull(membershipQuery, "membershipQuery");
    this.teamRoleRepository = Objects.requireNonNull(teamRoleRepository, "teamRoleRepository");
    this.memberRoleRepository =
        Objects.requireNonNull(memberRoleRepository, "memberRoleRepository");
    this.principalRepository =
        Objects.requireNonNull(principalRepository, "principalRepository");
    this.domainEventStore = Objects.requireNonNull(domainEventStore, "domainEventStore");
    this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
    this.receiptStore = Objects.requireNonNull(receiptStore, "receiptStore");
    this.transactionExecutor =
        Objects.requireNonNull(transactionExecutor, "transactionExecutor");
    this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
  }

  /** Suspends one member under MEMBER_MANAGE; active grants are revoked so activation rebuilds from MEMBER. */
  public CommandExecution<TeamMember> suspendMember(
      TeamCommandContext context, TeamId teamId, TeamMemberId memberId, long expectedVersion) {
    TeamCommandContext trusted = requireCommandContext(context);
    TeamId requiredTeamId = Objects.requireNonNull(teamId, "teamId");
    TeamMemberId requiredMemberId = Objects.requireNonNull(memberId, "memberId");
    return execute(
        trusted,
        SUSPEND_TEAM_MEMBER,
        requestHash(trusted, SUSPEND_TEAM_MEMBER, requiredTeamId, requiredMemberId.toString()),
        commandId ->
            lifecycleInTransaction(
                trusted,
                commandId,
                requiredTeamId,
                requiredMemberId,
                expectedVersion,
                TeamPermission.MEMBER_MANAGE,
                "manage Team members",
                true,
                true,
                (caller, target, now) -> {
                  TeamMember suspended = target.suspend(now);
                  revokeActiveGrants(suspended, now);
                  return new LifecycleOutcome(
                      suspended,
                      "TEAM_MEMBER_SUSPENDED",
                      TeamMemberSuspended.from(suspended));
                }));
  }

  /**
   * Restores participation with the default MEMBER role only; custom roles revoked by the exit
   * are not resurrected by activation.
   */
  public CommandExecution<TeamMember> activateMember(
      TeamCommandContext context, TeamId teamId, TeamMemberId memberId, long expectedVersion) {
    TeamCommandContext trusted = requireCommandContext(context);
    TeamId requiredTeamId = Objects.requireNonNull(teamId, "teamId");
    TeamMemberId requiredMemberId = Objects.requireNonNull(memberId, "memberId");
    return execute(
        trusted,
        ACTIVATE_TEAM_MEMBER,
        requestHash(trusted, ACTIVATE_TEAM_MEMBER, requiredTeamId, requiredMemberId.toString()),
        commandId ->
            lifecycleInTransaction(
                trusted,
                commandId,
                requiredTeamId,
                requiredMemberId,
                expectedVersion,
                TeamPermission.MEMBER_MANAGE,
                "manage Team members",
                false,
                false,
                (caller, target, now) -> {
                  Principal user =
                      principalRepository
                          .findById(target.scope().organizationId(), target.userPrincipalId())
                          .orElseThrow(
                              () ->
                                  new AggregateNotFoundException(
                                      "Principal", target.userPrincipalId()));
                  TeamMember activated = target.activate(user, now);
                  ensureDefaultMemberRole(activated, trusted.access().actor().id(), now);
                  return new LifecycleOutcome(
                      activated, "TEAM_MEMBER_ACTIVATED", TeamMemberActivated.from(activated));
                }));
  }

  /** Removes one member and revokes every active grant so re-invitation starts from MEMBER. */
  public CommandExecution<TeamMember> removeMember(
      TeamCommandContext context, TeamId teamId, TeamMemberId memberId, long expectedVersion) {
    TeamCommandContext trusted = requireCommandContext(context);
    TeamId requiredTeamId = Objects.requireNonNull(teamId, "teamId");
    TeamMemberId requiredMemberId = Objects.requireNonNull(memberId, "memberId");
    return execute(
        trusted,
        REMOVE_TEAM_MEMBER,
        requestHash(trusted, REMOVE_TEAM_MEMBER, requiredTeamId, requiredMemberId.toString()),
        commandId ->
            lifecycleInTransaction(
                trusted,
                commandId,
                requiredTeamId,
                requiredMemberId,
                expectedVersion,
                TeamPermission.MEMBER_MANAGE,
                "manage Team members",
                true,
                true,
                (caller, target, now) -> {
                  TeamMember removed = target.remove(now);
                  revokeActiveGrants(removed, now);
                  return new LifecycleOutcome(
                      removed, "TEAM_MEMBER_REMOVED", TeamMemberRemoved.from(removed));
                }));
  }

  /** Lets the actor leave their own membership; no one can leave as the last effective Owner. */
  public CommandExecution<TeamMember> leaveTeam(
      TeamCommandContext context, TeamId teamId, long expectedVersion) {
    TeamCommandContext trusted = requireCommandContext(context);
    TeamId requiredTeamId = Objects.requireNonNull(teamId, "teamId");
    return execute(
        trusted,
        LEAVE_TEAM,
        requestHash(trusted, LEAVE_TEAM, requiredTeamId, ""),
        commandId -> {
          Principal actor = trusted.access().actor();
          OrganizationId organizationId = actor.scope().organizationId();
          Team team = requireLockedTeam(organizationId, requiredTeamId);
          UtcTimestamp now = timeProvider.now();
          TeamMember caller = requireActiveMember(actor, team);
          requireVersion(caller, expectedVersion);
          requireNotLastOwner(organizationId, requiredTeamId, caller, now);
          TeamMember left = caller.leave(now);
          revokeActiveGrants(left, now);
          TeamMember committed = teamMemberRepository.update(left);
          return completedMember(
              trusted,
              commandId,
              committed,
              EventType.from("TEAM_MEMBER_LEFT"),
              TeamMemberLeft.from(committed),
              team);
        });
  }

  /** Moves TEAM ownership to another active member: Team fact, grant, revoke, one transaction. */
  public CommandExecution<OwnershipTransferResult> transferOwnership(
      TeamCommandContext context,
      TeamId teamId,
      TeamMemberId targetMemberId,
      long targetExpectedVersion) {
    TeamCommandContext trusted = requireCommandContext(context);
    TeamId requiredTeamId = Objects.requireNonNull(teamId, "teamId");
    TeamMemberId requiredTargetId = Objects.requireNonNull(targetMemberId, "targetMemberId");
    return execute(
        trusted,
        TRANSFER_TEAM_OWNERSHIP,
        requestHash(
            trusted, TRANSFER_TEAM_OWNERSHIP, requiredTeamId, requiredTargetId.toString()),
        commandId -> {
          Principal actor = trusted.access().actor();
          OrganizationId organizationId = actor.scope().organizationId();
          Team team = requireLockedTeam(organizationId, requiredTeamId);
          UtcTimestamp now = timeProvider.now();
          TeamMember caller = requireActiveMember(actor, team);
          requirePermission(caller, TeamPermission.ROLE_MANAGE, now, "manage Team roles");
          requireCurrentOwner(caller, now);
          TeamMember target = requireMember(organizationId, requiredTeamId, requiredTargetId);
          requireVersion(target, targetExpectedVersion);
          TeamRole ownerRole =
              requireBuiltInRole(organizationId, requiredTeamId, BuiltInTeamRole.TEAM_OWNER);
          // Team fact first, grant before revoke: a failure anywhere rolls the whole transfer back.
          Team transferred =
              teamRepository.update(team.transferOwnership(target, actor.id(), now));
          memberRoleRepository.create(
              MemberRole.grantOwner(
                  MemberRoleId.generate(), transferred, target, ownerRole, actor.id(), now));
          TeamMember newOwner = teamMemberRepository.update(target.markAuthorizationChanged(now));
          TeamMember previousOwner =
              teamMemberRepository.update(caller.markAuthorizationChanged(now));
          revokeOwnerGrants(previousOwner, ownerRole, now);
          OwnershipTransferResult result =
              new OwnershipTransferResult(transferred, previousOwner, newOwner);
          return completed(
              trusted,
              commandId,
              result,
              TEAM_AGGREGATE,
              transferred.id(),
              transferred.version(),
              EventType.from("TEAM_OWNERSHIP_TRANSFERRED"),
              new TeamOwnershipTransferred(
                  previousOwner.id().value(),
                  newOwner.id().value(),
                  newOwner.authorizationVersion()),
              transferred.id(),
              Optional.empty());
        });
  }

  /** Grants one team-scoped role; TEAM_OWNER moves only through the ownership transfer flow. */
  public CommandExecution<TeamMember> grantRole(
      TeamCommandContext context,
      TeamId teamId,
      TeamMemberId memberId,
      long expectedVersion,
      String roleKey) {
    TeamCommandContext trusted = requireCommandContext(context);
    TeamId requiredTeamId = Objects.requireNonNull(teamId, "teamId");
    TeamMemberId requiredMemberId = Objects.requireNonNull(memberId, "memberId");
    String requiredRoleKey = requireRoleKey(roleKey);
    return execute(
        trusted,
        GRANT_MEMBER_ROLE,
        requestHash(
            trusted,
            GRANT_MEMBER_ROLE,
            requiredTeamId,
            requiredMemberId + ":" + requiredRoleKey),
        commandId ->
            lifecycleInTransaction(
                trusted,
                commandId,
                requiredTeamId,
                requiredMemberId,
                expectedVersion,
                TeamPermission.ROLE_MANAGE,
                "manage Team roles",
                false,
                false,
                (caller, target, now) -> {
                  TeamRole role =
                      requireRoleByKey(
                          target.scope().organizationId(), requiredTeamId, requiredRoleKey);
                  if (role.isBuiltIn(BuiltInTeamRole.TEAM_OWNER)) {
                    throw new DomainValidationException(
                        "memberRole.teamRoleId",
                        "TEAM_OWNER must be granted through the Team ownership flow");
                  }
                  // A departing member must not receive new authority on the way out: a grant is
                  // only ever written against current participation facts read inside the lock.
                  if (!target.canParticipate()) {
                    throw new DomainValidationException(
                        "teamMember.status", "must be ACTIVE before a role can be granted");
                  }
                  requireNoEffectiveGrant(target, role, now);
                  memberRoleRepository.create(
                      MemberRole.grant(
                          MemberRoleId.generate(),
                          target,
                          role,
                          RoleScope.team(),
                          trusted.access().actor().id(),
                          now,
                          now,
                          Optional.empty()));
                  TeamMember granted = target.markAuthorizationChanged(now);
                  return new LifecycleOutcome(
                      granted,
                      "MEMBER_ROLE_GRANTED",
                      MemberRoleGranted.from(granted, role.key().value()));
                }));
  }

  /** Revokes one grant; revoking a TEAM_OWNER grant additionally needs a current Owner actor. */
  public CommandExecution<TeamMember> revokeRole(
      TeamCommandContext context,
      TeamId teamId,
      TeamMemberId memberId,
      long expectedVersion,
      MemberRoleId grantId) {
    TeamCommandContext trusted = requireCommandContext(context);
    TeamId requiredTeamId = Objects.requireNonNull(teamId, "teamId");
    TeamMemberId requiredMemberId = Objects.requireNonNull(memberId, "memberId");
    MemberRoleId requiredGrantId = Objects.requireNonNull(grantId, "grantId");
    return execute(
        trusted,
        REVOKE_MEMBER_ROLE,
        requestHash(
            trusted,
            REVOKE_MEMBER_ROLE,
            requiredTeamId,
            requiredMemberId + ":" + requiredGrantId),
        commandId ->
            lifecycleInTransaction(
                trusted,
                commandId,
                requiredTeamId,
                requiredMemberId,
                expectedVersion,
                TeamPermission.ROLE_MANAGE,
                "manage Team roles",
                false,
                false,
                (caller, target, now) -> {
                  MemberRole grant =
                      memberRoleRepository
                          .findById(target.scope().organizationId(), requiredGrantId)
                          .filter(value -> value.teamMemberId().equals(target.id()))
                          .orElseThrow(
                              () -> new AggregateNotFoundException("MemberRole", requiredGrantId));
                  TeamRole ownerRole =
                      findBuiltInRole(
                          target.scope().organizationId(),
                          requiredTeamId,
                          BuiltInTeamRole.TEAM_OWNER)
                          .orElse(null);
                  if (ownerRole != null && grant.teamRoleId().equals(ownerRole.id())) {
                    requireCurrentOwner(caller, now);
                    requireNotLastOwner(
                        target.scope().organizationId(), requiredTeamId, target, now);
                  }
                  TeamRole role =
                      teamRoleRepository
                          .findById(target.scope().organizationId(), grant.teamRoleId())
                          .orElseThrow(
                              () -> new AggregateNotFoundException("TeamRole", grant.teamRoleId()));
                  memberRoleRepository.update(grant.revoke(now));
                  TeamMember revoked = target.markAuthorizationChanged(now);
                  return new LifecycleOutcome(
                      revoked,
                      "MEMBER_ROLE_REVOKED",
                      MemberRoleRevoked.from(revoked, role.key().value()));
                }));
  }

  /** Team fact plus both membership sides of one completed ownership transfer. */
  public record OwnershipTransferResult(Team team, TeamMember previousOwner, TeamMember newOwner) {}

  private record LifecycleOutcome(TeamMember member, String eventType, DomainEvent event) {}

  @FunctionalInterface
  private interface LifecycleTransition {
    LifecycleOutcome apply(TeamMember caller, TeamMember target, UtcTimestamp now);
  }

  /**
   * Shared lifecycle body: actor check, Team lock, in-lock permission re-read, If-Match on the
   * target, Owner-specific defenses, domain change, facts. {@code forbidSelfTarget} marks the
   * commands that manage someone else's membership — leaving is the only self-exit. {@code
   * protectsLastOwner} marks the commands that end participation and therefore have to prove
   * another Owner remains.
   */
  private CommandExecution<TeamMember> lifecycleInTransaction(
      TeamCommandContext context,
      UUID commandId,
      TeamId teamId,
      TeamMemberId memberId,
      long expectedVersion,
      TeamPermission permission,
      String policyAction,
      boolean forbidSelfTarget,
      boolean protectsLastOwner,
      LifecycleTransition transition) {
    Principal actor = context.access().actor();
    OrganizationId organizationId = actor.scope().organizationId();
    Team team = requireLockedTeam(organizationId, teamId);
    UtcTimestamp now = timeProvider.now();
    TeamMember caller = requireActiveMember(actor, team);
    requirePermission(caller, permission, now, policyAction);
    TeamMember target = requireMember(organizationId, teamId, memberId);
    if (forbidSelfTarget && caller.id().equals(target.id())) {
      throw new PolicyDeniedException("suspend or remove your own membership — leave instead");
    }
    requireVersion(target, expectedVersion);
    if (team.ownerMemberId().equals(target.id())) {
      requireCurrentOwner(caller, now);
    }
    if (protectsLastOwner) {
      requireNotLastOwner(organizationId, teamId, target, now);
    }
    LifecycleOutcome outcome = transition.apply(caller, target, now);
    TeamMember committed = teamMemberRepository.update(outcome.member());
    return completedMember(
        context,
        commandId,
        committed,
        EventType.from(outcome.eventType()),
        outcome.event(),
        team);
  }

  private void revokeActiveGrants(TeamMember member, UtcTimestamp now) {
    memberRoleRepository
        .findByMember(member.scope().organizationId(), member.id())
        .stream()
        .filter(grant -> grant.status() == MemberRoleStatus.ACTIVE)
        .filter(grant -> grant.isEffectiveAt(now))
        .forEach(grant -> memberRoleRepository.update(grant.revoke(now)));
  }

  private void ensureDefaultMemberRole(TeamMember member, PrincipalId grantedBy, UtcTimestamp now) {
    TeamRole memberRole =
        requireBuiltInRole(
            member.scope().organizationId(), member.scope().teamId(), BuiltInTeamRole.MEMBER);
    requireNoEffectiveGrant(member, memberRole, now);
    memberRoleRepository.create(
        MemberRole.grant(
            MemberRoleId.generate(),
            member,
            memberRole,
            RoleScope.team(),
            grantedBy,
            now,
            now,
            Optional.empty()));
  }

  private void requireNoEffectiveGrant(TeamMember member, TeamRole role, UtcTimestamp now) {
    boolean effective =
        memberRoleRepository
            .findByMember(member.scope().organizationId(), member.id())
            .stream()
            .filter(grant -> grant.teamRoleId().equals(role.id()))
            .filter(grant -> grant.roleScope().equals(RoleScope.team()))
            .filter(grant -> grant.status() == MemberRoleStatus.ACTIVE)
            .anyMatch(grant -> grant.isEffectiveAt(now));
    if (effective) {
      throw new DomainValidationException(
          "memberRole.teamRoleId", "an effective grant of this role already exists");
    }
  }

  private void revokeOwnerGrants(TeamMember previousOwner, TeamRole ownerRole, UtcTimestamp now) {
    memberRoleRepository
        .findByMember(previousOwner.scope().organizationId(), previousOwner.id())
        .stream()
        .filter(grant -> grant.teamRoleId().equals(ownerRole.id()))
        .filter(grant -> grant.roleScope().equals(RoleScope.team()))
        .filter(grant -> grant.status() == MemberRoleStatus.ACTIVE)
        .filter(grant -> grant.isEffectiveAt(now))
        .forEach(grant -> memberRoleRepository.update(grant.revoke(now)));
  }

  /**
   * Blocks participation-ending commands that would leave no ACTIVE member with an effective
   * TEAM_OWNER grant. Ownership is a grant fact joined to membership status, never a count of
   * role strings. Call only inside the Team lock, where two concurrent Owner exits serialize.
   */
  private void requireNotLastOwner(
      OrganizationId organizationId, TeamId teamId, TeamMember target, UtcTimestamp now) {
    TeamRole ownerRole =
        findBuiltInRole(organizationId, teamId, BuiltInTeamRole.TEAM_OWNER).orElse(null);
    if (ownerRole == null) {
      return;
    }
    boolean targetIsOwner =
        memberRoleRepository.findByMember(organizationId, target.id()).stream()
            .filter(grant -> grant.teamRoleId().equals(ownerRole.id()))
            .filter(grant -> grant.roleScope().equals(RoleScope.team()))
            .filter(grant -> grant.status() == MemberRoleStatus.ACTIVE)
            .anyMatch(grant -> grant.isEffectiveAt(now));
    if (!targetIsOwner) {
      return;
    }
    if (memberRoleRepository.countEffectiveOwners(organizationId, teamId, now, target.id()) == 0) {
      throw new LastOwnerProtectionException(teamId.value().toString(), target.id());
    }
  }

  /** Effective TEAM_OWNER ownership is a grant fact, never a role-string count. */
  private void requireCurrentOwner(TeamMember caller, UtcTimestamp now) {
    OrganizationId organizationId = caller.scope().organizationId();
    TeamRole ownerRole =
        findBuiltInRole(organizationId, caller.scope().teamId(), BuiltInTeamRole.TEAM_OWNER)
            .orElseThrow(
                () ->
                    new DomainValidationException(
                        "teamRole.TEAM_OWNER", "built-in role is missing"));
    boolean owner =
        memberRoleRepository.findByMember(organizationId, caller.id()).stream()
            .filter(grant -> grant.teamRoleId().equals(ownerRole.id()))
            .filter(grant -> grant.roleScope().equals(RoleScope.team()))
            .filter(grant -> grant.status() == MemberRoleStatus.ACTIVE)
            .anyMatch(grant -> grant.isEffectiveAt(now));
    if (!owner) {
      throw new PolicyDeniedException("act as the current Team Owner");
    }
  }

  private TeamRole requireBuiltInRole(
      OrganizationId organizationId, TeamId teamId, BuiltInTeamRole builtIn) {
    return findBuiltInRole(organizationId, teamId, builtIn)
        .orElseThrow(
            () ->
                new DomainValidationException(
                    "teamRole." + builtIn.name(), "built-in role is missing"));
  }

  private Optional<TeamRole> findBuiltInRole(
      OrganizationId organizationId, TeamId teamId, BuiltInTeamRole builtIn) {
    return teamRoleRepository.findByTeam(organizationId, teamId).stream()
        .filter(role -> role.isBuiltIn(builtIn))
        .filter(TeamRole::isGrantable)
        .findFirst();
  }

  private TeamRole requireRoleByKey(OrganizationId organizationId, TeamId teamId, String roleKey) {
    return teamRoleRepository.findByTeam(organizationId, teamId).stream()
        .filter(role -> role.key().value().equals(roleKey))
        .filter(TeamRole::isGrantable)
        .findFirst()
        .orElseThrow(
            () ->
                new DomainValidationException(
                    "memberRole.roleKey", "must reference an active TeamRole"));
  }

  private static String requireRoleKey(String roleKey) {
    if (roleKey == null || roleKey.isBlank()) {
      throw new DomainValidationException("memberRole.roleKey", "must not be blank");
    }
    return roleKey.strip();
  }

  private static void requireVersion(TeamMember target, long expectedVersion) {
    if (target.version() != expectedVersion) {
      throw new OptimisticLockConflictException(
          "TeamMember", target.id(), expectedVersion, target.version());
    }
  }

  private TeamMember requireMember(
      OrganizationId organizationId, TeamId teamId, TeamMemberId memberId) {
    TeamMember member =
        teamMemberRepository
            .findById(organizationId, memberId)
            .orElseThrow(() -> new AggregateNotFoundException("TeamMember", memberId));
    if (!member.scope().teamId().equals(teamId)) {
      throw new AggregateNotFoundException("TeamMember", memberId);
    }
    return member;
  }

  private Team requireLockedTeam(OrganizationId organizationId, TeamId teamId) {
    if (teamRepository.findUninitializedById(organizationId, teamId).isPresent()) {
      throw new DomainValidationException("team.initializationStatus", "must be READY");
    }
    return teamRepository
        .lockById(organizationId, teamId)
        .orElseThrow(() -> new AggregateNotFoundException("Team", teamId));
  }

  private TeamMember requireActiveMember(Principal actor, Team team) {
    if (actor.type() != PrincipalType.USER
        || !actor.canAct()
        || !actor.scope().organizationId().equals(team.organizationId())) {
      throw new PolicyDeniedException("access this Team");
    }
    return membershipQuery.findByTeam(team.organizationId(), team.id()).stream()
        .filter(member -> member.userPrincipalId().equals(actor.id()))
        .filter(TeamMember::canParticipate)
        .findFirst()
        .orElseThrow(() -> new PolicyDeniedException("access this Team"));
  }

  private void requirePermission(
      TeamMember member, TeamPermission permission, UtcTimestamp now, String policyAction) {
    List<TeamRole> roles =
        teamRoleRepository.findByTeam(member.scope().organizationId(), member.scope().teamId());
    boolean allowed =
        memberRoleRepository
            .findByMember(member.scope().organizationId(), member.id())
            .stream()
            .filter(grant -> grant.status() == MemberRoleStatus.ACTIVE)
            .filter(grant -> grant.isEffectiveAt(now))
            .filter(grant -> grant.roleScope().equals(RoleScope.team()))
            .map(
                grant ->
                    roles.stream()
                        .filter(role -> role.id().equals(grant.teamRoleId()))
                        .findFirst()
                        .orElse(null))
            .filter(Objects::nonNull)
            .filter(TeamRole::isGrantable)
            .anyMatch(role -> role.permissions().contains(permission));
    if (!allowed) {
      throw new PolicyDeniedException(policyAction);
    }
  }

  private static CommandRequestHash requestHash(
      TeamCommandContext context, String commandType, TeamId teamId, String target) {
    return CommandRequestHash.sha256(
        commandType,
        context.access().actor().id().toString(),
        teamId.toString(),
        context.causationId().map(UUID::toString).orElse(""),
        target);
  }

  private static TeamCommandContext requireCommandContext(TeamCommandContext context) {
    return Objects.requireNonNull(context, "context");
  }

  private <T> CommandExecution<T> execute(
      TeamCommandContext context,
      String commandType,
      CommandRequestHash requestHash,
      Function<UUID, CommandExecution<T>> command) {
    return transactionExecutor.required(
        () -> {
          UtcTimestamp now = timeProvider.now();
          UUID commandId = UUID.randomUUID();
          CommandReservation reservation =
              receiptStore.reserve(
                  new CommandReservationRequest(
                      context.access().actor().scope().organizationId(),
                      context.idempotencyKey(),
                      commandType,
                      requestHash,
                      commandId,
                      context.correlationId(),
                      now));
          if (!reservation.acquired()) {
            return CommandExecution.replayed(reservation.receipt().orElseThrow());
          }
          return command.apply(commandId);
        });
  }

  private CommandExecution<TeamMember> completedMember(
      TeamCommandContext context,
      UUID commandId,
      TeamMember member,
      EventType eventType,
      DomainEvent payload,
      Team team) {
    return completed(
        context,
        commandId,
        member,
        TEAM_MEMBER_AGGREGATE,
        member.id(),
        member.version(),
        eventType,
        payload,
        team.id(),
        Optional.empty());
  }

  private <T> CommandExecution<T> completed(
      TeamCommandContext context,
      UUID commandId,
      T result,
      String aggregateType,
      AggregateId aggregateId,
      long aggregateVersion,
      EventType eventType,
      DomainEvent payload,
      TeamId teamId,
      Optional<WorkspaceId> workspaceId) {
    UtcTimestamp occurredAt = timeProvider.now();
    UUID eventId = UUID.randomUUID();
    DomainEventEnvelope<DomainEvent> event =
        new DomainEventEnvelope<>(
            eventId,
            eventType,
            SchemaVersion.V1,
            context.access().actor().scope().organizationId(),
            Optional.of(teamId),
            workspaceId,
            AggregateReference.of(aggregateType, aggregateId),
            aggregateVersion,
            EventActor.principal(EventActorType.USER, context.access().actor().id()),
            context.correlationId(),
            context.causationId(),
            Optional.of(context.idempotencyKey().value()),
            occurredAt,
            payload);
    domainEventStore.append(event);
    outboxRepository.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
    CommandReceipt receipt =
        new CommandReceipt(commandId, eventId, aggregateVersion, context.correlationId());
    receiptStore.complete(
        context.access().actor().scope().organizationId(),
        context.idempotencyKey(),
        receipt,
        occurredAt);
    return CommandExecution.completed(result, receipt);
  }
}
