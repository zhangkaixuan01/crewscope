package io.crewscope.application.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.identity.PrincipalProvisioningResult;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.IdempotencyConflictException;
import io.crewscope.domain.shared.error.LastOwnerProtectionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
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
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamMemberStatus;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamRoleId;
import io.crewscope.domain.team.UninitializedTeam;
import io.crewscope.domain.team.event.TeamOwnershipTransferred;
import io.crewscope.domain.workspace.PersonalAgentInitialization;
import io.crewscope.domain.workspace.Workspace;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** ADR-038 §1 command semantics: permissions, last-Owner protection, versions, idempotency. */
class TeamMemberLifecycleApplicationServiceTest {

  private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
  private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-09-09T04:00:00Z");

  @Test
  void suspendRequiresMemberManageAndAdvancesTheAuthorizationDimension() {
    Fixture fixture = new Fixture();
    TeamInitialization team = fixture.createTeam("lifecycle-team-1");
    Principal developer = fixture.addPrincipal("Developer");
    TeamMember member = fixture.addMember(team, developer, "add-dev-1");
    Principal outsider = fixture.addPrincipal("Outsider");
    fixture.addMember(team, outsider, "add-outsider-1");

    assertThrows(
        PolicyDeniedException.class,
        () ->
            fixture.service.suspendMember(
                fixture.context(outsider, false, "suspend-by-plain"),
                team.team().id(),
                member.id(),
                member.version()));
    assertEquals(
        TeamMemberStatus.ACTIVE, fixture.repository.members.get(member.id()).status());

    CommandExecution<TeamMember> execution =
        fixture.service.suspendMember(
            fixture.context(fixture.owner, false, "suspend-by-owner"),
            team.team().id(),
            member.id(),
            member.version());

    TeamMember suspended = execution.result().orElseThrow();
    assertEquals(TeamMemberStatus.SUSPENDED, suspended.status());
    assertEquals(member.version() + 1, suspended.version());
    assertEquals(member.authorizationVersion() + 1, suspended.authorizationVersion());
    assertEquals(
        "TEAM_MEMBER_SUSPENDED",
        fixture.repository.events.get(fixture.repository.events.size() - 1).eventType().value());
  }

  @Test
  void suspensionRevokesGrantsAndActivationRestoresOnlyTheMemberRole() {
    Fixture fixture = new Fixture();
    TeamInitialization team = fixture.createTeam("lifecycle-team-2");
    Principal developer = fixture.addPrincipal("Developer");
    TeamMember member = fixture.addMember(team, developer, "add-dev-2");
    TeamMember granted =
        fixture.service
            .grantRole(
                fixture.context(fixture.owner, false, "grant-team-lead-2"),
                team.team().id(),
                member.id(),
                member.version(),
                "TEAM_LEAD")
            .result()
            .orElseThrow();
    assertTrue(fixture.effectiveRoleKeys(granted).contains("TEAM_LEAD"));

    TeamMember suspended =
        fixture.service
            .suspendMember(
                fixture.context(fixture.owner, false, "suspend-dev-2"),
                team.team().id(),
                granted.id(),
                granted.version())
            .result()
            .orElseThrow();
    assertTrue(fixture.effectiveRoleKeys(suspended).isEmpty());

    TeamMember activated =
        fixture.service
            .activateMember(
                fixture.context(fixture.owner, false, "activate-dev-2"),
                team.team().id(),
                suspended.id(),
                suspended.version())
            .result()
            .orElseThrow();
    assertEquals(TeamMemberStatus.ACTIVE, activated.status());
    // Activation re-seats the built-in MEMBER grant only; TEAM_LEAD stays revoked.
    assertEquals(List.of("MEMBER"), fixture.effectiveRoleKeys(activated));
  }

  @Test
  void suspendAndRemoveRefuseTheCallerOwnMembershipAndPointAtLeave() {
    Fixture fixture = new Fixture();
    TeamInitialization team = fixture.createTeam("lifecycle-team-self");
    TeamMember ownerMember = team.ownerMember();
    int eventsBefore = fixture.repository.events.size();

    // Even with MEMBER_MANAGE, managing your own membership is leave's job — the guard fires
    // before the last-Owner defense, so a lone Owner sees the leave guidance, not a 409.
    PolicyDeniedException suspended =
        assertThrows(
            PolicyDeniedException.class,
            () ->
                fixture.service.suspendMember(
                    fixture.context(fixture.owner, false, "suspend-self"),
                    team.team().id(),
                    ownerMember.id(),
                    ownerMember.version()));
    assertTrue(suspended.getMessage().contains("leave"));
    PolicyDeniedException removed =
        assertThrows(
            PolicyDeniedException.class,
            () ->
                fixture.service.removeMember(
                    fixture.context(fixture.owner, false, "remove-self"),
                    team.team().id(),
                    ownerMember.id(),
                    ownerMember.version()));
    assertTrue(removed.getMessage().contains("leave"));
    assertEquals(TeamMemberStatus.ACTIVE, fixture.repository.members.get(ownerMember.id()).status());
    assertEquals(eventsBefore, fixture.repository.events.size());
  }

  @Test
  void removalBlocksTheLastEffectiveOwnerButNotWhenAnotherOwnerRemains() {
    Fixture fixture = new Fixture();
    TeamInitialization team = fixture.createTeam("lifecycle-team-3");
    Principal developer = fixture.addPrincipal("Developer");
    TeamMember member = fixture.addMember(team, developer, "add-dev-3");
    TeamMember ownerMember = team.ownerMember();

    assertThrows(
        LastOwnerProtectionException.class,
        () ->
            fixture.service.leaveTeam(
                fixture.context(fixture.owner, false, "leave-last-owner"),
                team.team().id(),
                ownerMember.version()));
    assertEquals(TeamMemberStatus.ACTIVE, fixture.repository.members.get(ownerMember.id()).status());

    // Handing ownership to a second member unblocks the first Owner's removal: the new Owner
    // commands it, and excluding the target still leaves one effective Owner.
    fixture.service.transferOwnership(
        fixture.context(fixture.owner, false, "promote-dev-3"),
        team.team().id(),
        member.id(),
        member.version());

    TeamMember removed =
        fixture.service
            .removeMember(
                fixture.context(developer, false, "remove-former-owner"),
                team.team().id(),
                ownerMember.id(),
                fixture.repository.members.get(ownerMember.id()).version())
            .result()
            .orElseThrow();
    assertEquals(TeamMemberStatus.REMOVED, removed.status());
  }

  @Test
  void staleIfMatchIsRejectedBeforeAnyDomainChange() {
    Fixture fixture = new Fixture();
    TeamInitialization team = fixture.createTeam("lifecycle-team-4");
    Principal developer = fixture.addPrincipal("Developer");
    TeamMember member = fixture.addMember(team, developer, "add-dev-4");

    assertThrows(
        OptimisticLockConflictException.class,
        () ->
            fixture.service.suspendMember(
                fixture.context(fixture.owner, false, "stale-suspend"),
                team.team().id(),
                member.id(),
                member.version() + 5));
    assertEquals(
        TeamMemberStatus.ACTIVE, fixture.repository.members.get(member.id()).status());
  }

  @Test
  void replayingTheSameIdempotencyKeyReturnsTheOriginalReceiptWithoutASecondEvent() {
    Fixture fixture = new Fixture();
    TeamInitialization team = fixture.createTeam("lifecycle-team-5");
    Principal developer = fixture.addPrincipal("Developer");
    TeamMember member = fixture.addMember(team, developer, "add-dev-5");
    TeamCommandContext context = fixture.context(fixture.owner, false, "suspend-replay-key");

    CommandExecution<TeamMember> first =
        fixture.service.suspendMember(context, team.team().id(), member.id(), member.version());
    CommandExecution<TeamMember> replay =
        fixture.service.suspendMember(context, team.team().id(), member.id(), member.version());

    assertTrue(first.result().isPresent());
    assertTrue(replay.replayed());
    assertFalse(replay.result().isPresent());
    assertEquals(first.receipt(), replay.receipt());
    long suspendEvents =
        fixture.repository.events.stream()
            .filter(event -> event.eventType().value().equals("TEAM_MEMBER_SUSPENDED"))
            .count();
    assertEquals(1, suspendEvents);
  }

  @Test
  void reusingAKeyWithADifferentPayloadIsAnIdempotencyConflict() {
    Fixture fixture = new Fixture();
    TeamInitialization team = fixture.createTeam("lifecycle-team-5b");
    Principal developer = fixture.addPrincipal("Developer");
    Principal other = fixture.addPrincipal("Other");
    TeamMember member = fixture.addMember(team, developer, "add-dev-5b");
    TeamMember second = fixture.addMember(team, other, "add-other-5b");
    TeamCommandContext context = fixture.context(fixture.owner, false, "conflicting-key");

    fixture.service.suspendMember(context, team.team().id(), member.id(), member.version());
    assertThrows(
        IdempotencyConflictException.class,
        () ->
            fixture.service.suspendMember(
                context, team.team().id(), second.id(), second.version()));
  }

  @Test
  void ownershipTransferMovesTheGrantFactAndBumpsBothAuthorizationDimensions() {
    Fixture fixture = new Fixture();
    TeamInitialization team = fixture.createTeam("lifecycle-team-6");
    Principal developer = fixture.addPrincipal("Developer");
    TeamMember member = fixture.addMember(team, developer, "add-dev-6");
    TeamMember previousOwner = team.ownerMember();

    CommandExecution<TeamMemberLifecycleApplicationService.OwnershipTransferResult> execution =
        fixture.service.transferOwnership(
            fixture.context(fixture.owner, false, "transfer-owner-6"),
            team.team().id(),
            member.id(),
            member.version());

    TeamMemberLifecycleApplicationService.OwnershipTransferResult result =
        execution.result().orElseThrow();
    assertEquals(member.id(), result.team().ownerMemberId());
    assertEquals(
        previousOwner.authorizationVersion() + 1, result.previousOwner().authorizationVersion());
    assertEquals(member.authorizationVersion() + 1, result.newOwner().authorizationVersion());

    TeamRole ownerRole = fixture.builtInRole(team, BuiltInTeamRole.TEAM_OWNER);
    boolean previousOwnerStillGranted =
        fixture.repository.grants.values().stream()
            .filter(grant -> grant.teamMemberId().equals(previousOwner.id()))
            .filter(grant -> grant.status() == MemberRoleStatus.ACTIVE)
            .anyMatch(grant -> grant.teamRoleId().equals(ownerRole.id()));
    assertFalse(previousOwnerStillGranted);
    boolean newOwnerGranted =
        fixture.repository.grants.values().stream()
            .filter(grant -> grant.teamMemberId().equals(member.id()))
            .filter(grant -> grant.status() == MemberRoleStatus.ACTIVE)
            .anyMatch(grant -> grant.teamRoleId().equals(ownerRole.id()));
    assertTrue(newOwnerGranted);

    DomainEventEnvelope<? extends DomainEvent> event =
        fixture.repository.events.get(fixture.repository.events.size() - 1);
    assertEquals("TEAM_OWNERSHIP_TRANSFERRED", event.eventType().value());
    TeamOwnershipTransferred payload = (TeamOwnershipTransferred) event.payload();
    assertEquals(previousOwner.id().value(), payload.fromMemberId());
    assertEquals(member.id().value(), payload.toMemberId());
    assertEquals(result.newOwner().authorizationVersion(), payload.authorizationVersionAfter());
  }

  @Test
  void roleGrantsNeedRoleManageAndRejectDepartingTargetsAndTeamOwner() {
    Fixture fixture = new Fixture();
    TeamInitialization team = fixture.createTeam("lifecycle-team-7");
    Principal developer = fixture.addPrincipal("Developer");
    TeamMember member = fixture.addMember(team, developer, "add-dev-7");
    Principal plain = fixture.addPrincipal("Plain");
    fixture.addMember(team, plain, "add-plain-7");

    assertThrows(
        PolicyDeniedException.class,
        () ->
            fixture.service.grantRole(
                fixture.context(plain, false, "grant-without-permission"),
                team.team().id(),
                member.id(),
                member.version(),
                "TEAM_LEAD"));

    assertThrows(
        DomainValidationException.class,
        () ->
            fixture.service.grantRole(
                fixture.context(fixture.owner, false, "grant-owner-role"),
                team.team().id(),
                member.id(),
                member.version(),
                "TEAM_OWNER"));

    TeamMember suspended =
        fixture.service
            .suspendMember(
                fixture.context(fixture.owner, false, "suspend-dev-7"),
                team.team().id(),
                member.id(),
                member.version())
            .result()
            .orElseThrow();
    assertThrows(
        DomainValidationException.class,
        () ->
            fixture.service.grantRole(
                fixture.context(fixture.owner, false, "grant-to-suspended"),
                team.team().id(),
                suspended.id(),
                suspended.version(),
                "TEAM_LEAD"));
  }

  @Test
  void revokingARoleAddressesTheExactGrantAndBumpsTheDimension() {
    Fixture fixture = new Fixture();
    TeamInitialization team = fixture.createTeam("lifecycle-team-8");
    Principal developer = fixture.addPrincipal("Developer");
    TeamMember member = fixture.addMember(team, developer, "add-dev-8");
    TeamMember granted =
        fixture.service
            .grantRole(
                fixture.context(fixture.owner, false, "grant-team-lead-8"),
                team.team().id(),
                member.id(),
                member.version(),
                "TEAM_LEAD")
            .result()
            .orElseThrow();

    UUID grantId =
        fixture.repository.grants.values().stream()
            .filter(grant -> grant.teamMemberId().equals(member.id()))
            .filter(grant -> grant.status() == MemberRoleStatus.ACTIVE)
            .filter(
                grant ->
                    !grant.teamRoleId()
                        .equals(fixture.builtInRole(team, BuiltInTeamRole.MEMBER).id()))
            .findFirst()
            .orElseThrow()
            .id()
            .value();

    TeamMember revoked =
        fixture.service
            .revokeRole(
                fixture.context(fixture.owner, false, "revoke-team-lead-8"),
                team.team().id(),
                granted.id(),
                granted.version(),
                new MemberRoleId(grantId))
            .result()
            .orElseThrow();

    assertEquals(granted.authorizationVersion() + 1, revoked.authorizationVersion());
    assertEquals(List.of("MEMBER"), fixture.effectiveRoleKeys(revoked));
    assertEquals(
        "MEMBER_ROLE_REVOKED",
        fixture.repository.events.get(fixture.repository.events.size() - 1).eventType().value());
  }

  @Test
  void effectiveOwnerCountJoinsMembershipStatusAndGrantValidity() {
    Fixture fixture = new Fixture();
    TeamInitialization team = fixture.createTeam("lifecycle-team-9");
    Principal developer = fixture.addPrincipal("Developer");
    TeamMember member = fixture.addMember(team, developer, "add-dev-9");
    TeamId teamId = team.team().id();
    TeamMember previousOwner = team.ownerMember();

    // Only the creator is an effective Owner: excluding the owner leaves nobody.
    assertEquals(1, fixture.repository.countEffectiveOwners(ORGANIZATION_ID, teamId, NOW, member.id()));
    assertEquals(
        0, fixture.repository.countEffectiveOwners(ORGANIZATION_ID, teamId, NOW, previousOwner.id()));

    fixture.service.transferOwnership(
        fixture.context(fixture.owner, false, "transfer-owner-9"),
        teamId,
        member.id(),
        member.version());
    // After the transfer exactly one effective Owner remains: the grant moved, the Team fact
    // moved, and the previous Owner's TEAM_OWNER grant is revoked.
    assertEquals(
        0, fixture.repository.countEffectiveOwners(ORGANIZATION_ID, teamId, NOW, member.id()));
    assertEquals(
        1, fixture.repository.countEffectiveOwners(ORGANIZATION_ID, teamId, NOW, previousOwner.id()));
    assertEquals(
        1,
        fixture.repository.countEffectiveOwners(
            ORGANIZATION_ID, teamId, NOW, new TeamMemberId(UUID.randomUUID())));
  }

  private static final class Fixture {

    private final InMemoryRepository repository = new InMemoryRepository();
    private final Principal owner = addPrincipal("Owner");
    private final TeamApplicationService application;
    private final TeamMemberLifecycleApplicationService service;

    private Fixture() {
      TimeProvider time = () -> NOW;
      TransactionExecutor transactions = new DirectTransactions();
      TeamMembershipQuery membershipQuery = repository::findMembersByTeam;
      TeamCreationService creation =
          new TeamCreationService(
              repository,
              repository,
              repository,
              repository,
              repository,
              repository,
              (team, workspace, actor) -> {},
              transactions,
              time,
              (actor, occurredAt) -> {},
              (organizationId, actor, occurredAt) -> {},
              (team, workspace, ownerMember, ownerUser) -> {});
      application =
          new TeamApplicationService(
              creation,
              repository,
              repository,
              repository,
              membershipQuery,
              repository,
              repository,
              repository,
              repository,
              repository,
              repository,
              repository,
              transactions,
              time);
      service =
          new TeamMemberLifecycleApplicationService(
              repository,
              repository,
              membershipQuery,
              repository,
              repository,
              repository,
              repository,
              repository,
              repository,
              transactions,
              time);
    }

    private Principal addPrincipal(String name) {
      Principal principal =
          Principal.create(
              PrincipalId.generate(),
              PrincipalScope.organization(ORGANIZATION_ID),
              PrincipalType.USER,
              Optional.empty(),
              name,
              Optional.empty(),
              PrincipalVisibility.ORGANIZATION,
              NOW);
      repository.principals.put(principal.id(), principal);
      return principal;
    }

    private TeamInitialization createTeam(String key) {
      return application
          .createTeam(context(owner, false, key), new CreateTeamCommand("Team"))
          .result()
          .orElseThrow();
    }

    private TeamMember addMember(TeamInitialization team, Principal principal, String key) {
      return application
          .addMember(
              context(owner, false, key),
              team.team().id(),
              new AddTeamMemberCommand(principal.id()))
          .result()
          .orElseThrow();
    }

    private TeamCommandContext context(Principal actor, boolean administrator, String key) {
      return new TeamCommandContext(
          new TeamAccessContext(actor, administrator),
          IdempotencyKey.from(key),
          UUID.randomUUID(),
          Optional.empty());
    }

    private TeamRole builtInRole(TeamInitialization team, BuiltInTeamRole builtIn) {
      return repository.roles.values().stream()
          .filter(role -> role.scope().teamId().equals(team.team().id()))
          .filter(role -> role.isBuiltIn(builtIn))
          .findFirst()
          .orElseThrow();
    }

    private List<String> effectiveRoleKeys(TeamMember member) {
      Map<TeamRoleId, TeamRole> rolesById = new HashMap<>(repository.roles);
      return repository.grants.values().stream()
          .filter(grant -> grant.teamMemberId().equals(member.id()))
          .filter(grant -> grant.status() == MemberRoleStatus.ACTIVE)
          .filter(grant -> grant.isEffectiveAt(NOW))
          .map(grant -> rolesById.get(grant.teamRoleId()))
          .filter(role -> role != null && role.isGrantable())
          .map(role -> role.key().value())
          .distinct()
          .sorted()
          .toList();
    }
  }

  private static final class DirectTransactions implements TransactionExecutor {

    @Override
    public <T> T required(Supplier<T> operation) {
      return operation.get();
    }
  }

  /** In-memory ports mirroring the JPA adapter semantics the service relies on. */
  private static final class InMemoryRepository
      implements TeamRepository,
          WorkspaceRepository,
          TeamMemberRepository,
          TeamRoleRepository,
          MemberRoleRepository,
          PrincipalRepository,
          DefaultPersonalAgentRepository,
          DomainEventStore,
          OutboxRepository,
          CommandReceiptStore {

    private final Map<TeamId, Team> teams = new LinkedHashMap<>();
    private final Map<TeamId, UninitializedTeam> legacyTeams = new HashMap<>();
    private final Map<WorkspaceId, Workspace> workspaces = new HashMap<>();
    private final Map<TeamMemberId, TeamMember> members = new LinkedHashMap<>();
    private final Map<TeamRoleId, TeamRole> roles = new LinkedHashMap<>();
    private final Map<MemberRoleId, MemberRole> grants = new LinkedHashMap<>();
    private final Map<PrincipalId, Principal> principals = new HashMap<>();
    private final Map<TeamMemberId, PersonalAgentInitialization> agents = new HashMap<>();
    private final List<DomainEventEnvelope<? extends DomainEvent>> events = new ArrayList<>();
    private final List<PendingOutboxEvent> outbox = new ArrayList<>();
    private final Map<String, ReceiptEntry> receipts = new HashMap<>();
    final Map<String, io.crewscope.application.command.CommandResult> results = new HashMap<>();

    @Override
    public void saveResult(io.crewscope.application.command.CommandResult result) {
      results.put(result.organizationId() + ":" + result.idempotencyKey(), result);
    }

    @Override
    public Optional<io.crewscope.application.command.CommandResult> findResult(
        OrganizationId organizationId, IdempotencyKey key, PrincipalId actorId) {
      return Optional.ofNullable(results.get(organizationId + ":" + key.value()))
          .filter(result -> result.actorId().equals(actorId));
    }

    @Override
    public Team create(Team team) {
      teams.put(team.id(), team);
      return team;
    }

    @Override
    public Team update(Team team) {
      legacyTeams.remove(team.id());
      teams.put(team.id(), team);
      return team;
    }

    @Override
    public Optional<Team> findById(OrganizationId organizationId, TeamId id) {
      return Optional.ofNullable(teams.get(id))
          .filter(team -> team.organizationId().equals(organizationId));
    }

    @Override
    public Optional<Team> lockById(OrganizationId organizationId, TeamId id) {
      return findById(organizationId, id);
    }

    @Override
    public List<Team> findActiveByMember(OrganizationId organizationId, PrincipalId principalId) {
      return teams.values().stream()
          .filter(Team::isActive)
          .filter(team -> team.organizationId().equals(organizationId))
          .filter(
              team ->
                  members.values().stream()
                      .anyMatch(
                          member ->
                              member.scope().teamId().equals(team.id())
                                  && member.userPrincipalId().equals(principalId)
                                  && member.canParticipate()))
          .toList();
    }

    @Override
    public Optional<UninitializedTeam> findUninitializedById(
        OrganizationId organizationId, TeamId id) {
      return Optional.ofNullable(legacyTeams.get(id))
          .filter(team -> team.organizationId().equals(organizationId));
    }

    @Override
    public Optional<UninitializedTeam> lockUninitializedById(
        OrganizationId organizationId, TeamId id) {
      return findUninitializedById(organizationId, id);
    }

    @Override
    public Workspace create(Workspace workspace) {
      workspaces.put(workspace.id(), workspace);
      return workspace;
    }

    @Override
    public Optional<Workspace> findById(OrganizationId organizationId, WorkspaceId id) {
      return Optional.ofNullable(workspaces.get(id))
          .filter(workspace -> workspace.scope().organizationId().equals(organizationId));
    }

    @Override
    public TeamMember create(TeamMember member) {
      members.put(member.id(), member);
      return member;
    }

    @Override
    public TeamMember update(TeamMember member) {
      members.put(member.id(), member);
      return member;
    }

    @Override
    public Optional<TeamMember> findById(OrganizationId organizationId, TeamMemberId id) {
      return Optional.ofNullable(members.get(id))
          .filter(member -> member.scope().organizationId().equals(organizationId));
    }

    List<TeamMember> findMembersByTeam(OrganizationId organizationId, TeamId teamId) {
      return members.values().stream()
          .filter(member -> member.scope().organizationId().equals(organizationId))
          .filter(member -> member.scope().teamId().equals(teamId))
          .toList();
    }

    @Override
    public List<TeamRole> createAll(List<TeamRole> values) {
      values.forEach(role -> roles.put(role.id(), role));
      return List.copyOf(values);
    }

    @Override
    public Optional<TeamRole> findById(OrganizationId organizationId, TeamRoleId id) {
      return Optional.ofNullable(roles.get(id))
          .filter(role -> role.scope().organizationId().equals(organizationId));
    }

    @Override
    public List<TeamRole> findByTeam(OrganizationId organizationId, TeamId teamId) {
      return roles.values().stream()
          .filter(role -> role.scope().organizationId().equals(organizationId))
          .filter(role -> role.scope().teamId().equals(teamId))
          .toList();
    }

    @Override
    public MemberRole create(MemberRole grant) {
      grants.put(grant.id(), grant);
      return grant;
    }

    @Override
    public MemberRole update(MemberRole grant) {
      grants.put(grant.id(), grant);
      return grant;
    }

    @Override
    public Optional<MemberRole> findById(OrganizationId organizationId, MemberRoleId id) {
      return Optional.ofNullable(grants.get(id))
          .filter(grant -> grant.teamScope().organizationId().equals(organizationId));
    }

    @Override
    public List<MemberRole> findByMember(OrganizationId organizationId, TeamMemberId memberId) {
      return grants.values().stream()
          .filter(grant -> grant.teamScope().organizationId().equals(organizationId))
          .filter(grant -> grant.teamMemberId().equals(memberId))
          .toList();
    }

    @Override
    public long countEffectiveOwners(
        OrganizationId organizationId, TeamId teamId, UtcTimestamp now, TeamMemberId excluding) {
      Optional<TeamRoleId> ownerRoleId =
          roles.values().stream()
              .filter(role -> role.scope().organizationId().equals(organizationId))
              .filter(role -> role.scope().teamId().equals(teamId))
              .filter(role -> role.isBuiltIn(BuiltInTeamRole.TEAM_OWNER))
              .map(TeamRole::id)
              .findFirst();
      if (ownerRoleId.isEmpty()) {
        return 0;
      }
      TeamRoleId required = ownerRoleId.orElseThrow();
      return members.values().stream()
          .filter(member -> member.scope().organizationId().equals(organizationId))
          .filter(member -> member.scope().teamId().equals(teamId))
          .filter(member -> member.status() == TeamMemberStatus.ACTIVE)
          .filter(member -> !member.id().equals(excluding))
          .filter(
              member ->
                  grants.values().stream()
                      .filter(grant -> grant.teamMemberId().equals(member.id()))
                      .filter(grant -> grant.teamRoleId().equals(required))
                      .filter(grant -> grant.roleScope().equals(RoleScope.team()))
                      .filter(grant -> grant.status() == MemberRoleStatus.ACTIVE)
                      .anyMatch(grant -> grant.isEffectiveAt(now)))
          .count();
    }

    @Override
    public Optional<Principal> findById(OrganizationId organizationId, PrincipalId principalId) {
      return Optional.ofNullable(principals.get(principalId))
          .filter(principal -> principal.scope().organizationId().equals(organizationId));
    }

    @Override
    public Optional<Principal> findByExternalIdentity(
        OrganizationId organizationId, String provider, String subject) {
      return Optional.empty();
    }

    @Override
    public boolean organizationExists(OrganizationId organizationId) {
      return ORGANIZATION_ID.equals(organizationId);
    }

    @Override
    public PrincipalProvisioningResult provisionUser(Principal candidate) {
      principals.put(candidate.id(), candidate);
      return new PrincipalProvisioningResult(candidate, true);
    }

    @Override
    public PersonalAgentInitialization initializeIfAbsent(PersonalAgentInitialization candidate) {
      TeamMemberId memberId = candidate.agentProfile().ownerMemberId().orElseThrow();
      return agents.computeIfAbsent(memberId, ignored -> candidate);
    }

    @Override
    public void append(DomainEventEnvelope<? extends DomainEvent> event) {
      events.add(event);
    }

    @Override
    public void enqueue(PendingOutboxEvent event) {
      outbox.add(event);
    }

    @Override
    public CommandReservation reserve(CommandReservationRequest request) {
      String key = request.organizationId() + ":" + request.idempotencyKey().value();
      ReceiptEntry existing = receipts.get(key);
      if (existing == null) {
        receipts.put(key, new ReceiptEntry(request, null));
        return CommandReservation.newlyAcquired();
      }
      if (!existing.request().commandType().equals(request.commandType())
          || !existing.request().requestHash().equals(request.requestHash())) {
        throw new IdempotencyConflictException(
            request.idempotencyKey().value(),
            existing.request().requestHash().value(),
            request.requestHash().value());
      }
      return CommandReservation.replay(existing.receipt());
    }

    @Override
    public void complete(
        OrganizationId organizationId,
        IdempotencyKey idempotencyKey,
        CommandReceipt receipt,
        UtcTimestamp completedAt) {
      String key = organizationId + ":" + idempotencyKey.value();
      receipts.put(key, new ReceiptEntry(receipts.get(key).request(), receipt));
    }

    private record ReceiptEntry(CommandReservationRequest request, CommandReceipt receipt) {}
  }
}
