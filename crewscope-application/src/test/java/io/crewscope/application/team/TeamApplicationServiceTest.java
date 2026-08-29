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
import io.crewscope.domain.identity.PrincipalStatus;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.IdempotencyConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.MemberRole;
import io.crewscope.domain.team.MemberRoleId;
import io.crewscope.domain.team.RoleScope;
import io.crewscope.domain.team.RoleScopeType;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamPermission;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamRoleId;
import io.crewscope.domain.team.TeamRoleKey;
import io.crewscope.domain.team.UninitializedTeam;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workspace.PersonalAgentInitialization;
import io.crewscope.domain.workspace.Workspace;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class TeamApplicationServiceTest {

  private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
  private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-08T03:00:00Z");

  @Test
  void createsCompleteFoundationAndReplaysTheOriginalReceipt() {
    Fixture fixture = new Fixture();
    TeamCommandContext context = fixture.context(fixture.owner, false, "create-team-1");

    CommandExecution<TeamInitialization> first =
        fixture.service.createTeam(context, new CreateTeamCommand("  Platform Crew  "));
    CommandExecution<TeamInitialization> replay =
        fixture.service.createTeam(context, new CreateTeamCommand("Platform Crew"));

    TeamInitialization created = first.result().orElseThrow();
    assertEquals("Platform Crew", created.team().name());
    assertEquals(1, fixture.repository.teams.size());
    assertEquals(1, fixture.repository.members.size());
    assertEquals(5, fixture.repository.roles.size());
    assertEquals(1, fixture.repository.grants.size());
    assertEquals(1, fixture.repository.agents.size());
    assertEquals("TEAM_CREATED", fixture.repository.events.get(0).eventType().value());
    assertTrue(replay.replayed());
    assertEquals(first.receipt(), replay.receipt());
    assertEquals(1, fixture.repository.events.size());
  }

  @Test
  void firstTeamCreationReplaysBeforeTheCompletedOnboardingGuard() {
    Fixture fixture = new Fixture();
    TeamCommandContext context = fixture.context(fixture.owner, false, "first-team-replay");

    CommandExecution<TeamInitialization> first =
        fixture.service.createFirstTeam(context, new CreateTeamCommand("First Team"));
    CommandExecution<TeamInitialization> replay =
        fixture.service.createFirstTeam(context, new CreateTeamCommand("First Team"));

    assertFalse(first.replayed());
    assertTrue(replay.replayed());
    assertEquals(first.receipt(), replay.receipt());
    assertEquals(1, fixture.repository.teams.size());
    assertEquals(1, fixture.repository.agents.size());
  }

  @Test
  void firstTeamCreationRejectsAnotherKeyAfterMembershipExists() {
    Fixture fixture = new Fixture();
    fixture.service.createFirstTeam(
        fixture.context(fixture.owner, false, "first-team-original"),
        new CreateTeamCommand("First Team"));

    assertThrows(
        FirstTeamAlreadyExistsException.class,
        () -> fixture.service.createFirstTeam(
            fixture.context(fixture.owner, false, "first-team-second"),
            new CreateTeamCommand("Unexpected Team")));
    assertEquals(1, fixture.repository.teams.size());
  }

  @Test
  void onboardingAndGeneralTeamCreationCannotShareAnIdempotencyReceipt() {
    Fixture fixture = new Fixture();
    TeamCommandContext context = fixture.context(fixture.owner, false, "team-route-boundary");

    fixture.service.createTeam(context, new CreateTeamCommand("General Team"));

    assertThrows(
        IdempotencyConflictException.class,
        () -> fixture.service.createFirstTeam(context, new CreateTeamCommand("General Team")));
  }

  @Test
  void rejectsChangedCreateContentForTheSameIdempotencyKey() {
    Fixture fixture = new Fixture();
    TeamCommandContext context = fixture.context(fixture.owner, false, "create-team-2");
    fixture.service.createTeam(context, new CreateTeamCommand("First"));

    assertThrows(
        IdempotencyConflictException.class,
        () -> fixture.service.createTeam(context, new CreateTeamCommand("Changed")));
  }

  @Test
  void includesCausationInTheTeamCommandRequestHash() {
    Fixture fixture = new Fixture();
    UUID firstCause = UUID.randomUUID();
    UUID secondCause = UUID.randomUUID();
    fixture.service.createTeam(
        fixture.context(
            fixture.owner, false, "create-team-causation", Optional.of(firstCause)),
        new CreateTeamCommand("Causal Team"));

    assertThrows(
        IdempotencyConflictException.class,
        () ->
            fixture.service.createTeam(
                fixture.context(
                    fixture.owner, false, "create-team-causation", Optional.of(secondCause)),
                new CreateTeamCommand("Causal Team")));
  }

  @Test
  void ownerAddsMemberWithMemberGrantAndDefaultPersonalAgent() {
    Fixture fixture = new Fixture();
    TeamInitialization team = fixture.createTeam("member-team-1");
    Principal target = fixture.addPrincipal("Developer");

    CommandExecution<TeamMember> execution =
        fixture.service.addMember(
            fixture.context(fixture.owner, false, "add-member-1"),
            team.team().id(),
            new AddTeamMemberCommand(target.id()));

    TeamMember member = execution.result().orElseThrow();
    assertEquals(target.id(), member.userPrincipalId());
    assertEquals(2, fixture.repository.members.size());
    assertEquals(2, fixture.repository.grants.size());
    assertEquals(2, fixture.repository.agents.size());
    assertEquals("TEAM_MEMBER_JOINED", fixture.repository.events.get(1).eventType().value());
  }

  @Test
  void memberWithoutManagementPermissionCannotAddAnotherMember() {
    Fixture fixture = new Fixture();
    TeamInitialization team = fixture.createTeam("member-team-2");
    Principal member = fixture.addPrincipal("Member");
    fixture.service.addMember(
        fixture.context(fixture.owner, false, "add-member-2"),
        team.team().id(),
        new AddTeamMemberCommand(member.id()));
    Principal target = fixture.addPrincipal("Target");

    assertThrows(
        PolicyDeniedException.class,
        () ->
            fixture.service.addMember(
                fixture.context(member, false, "unauthorized-add-1"),
                team.team().id(),
                new AddTeamMemberCommand(target.id())));
    assertEquals(2, fixture.repository.members.size());
  }

  @Test
  void workProjectScopedGrantCannotAuthorizeTeamMemberManagement() {
    Fixture fixture = new Fixture();
    TeamInitialization team = fixture.createTeam("member-team-project-scope");
    Principal member = fixture.addPrincipal("Project Manager");
    TeamMember membership =
        fixture
            .service
            .addMember(
                fixture.context(fixture.owner, false, "add-project-manager"),
                team.team().id(),
                new AddTeamMemberCommand(member.id()))
            .result()
            .orElseThrow();
    TeamRole projectRole =
        TeamRole.createCustom(
            TeamRoleId.generate(),
            team.team().scope(),
            new TeamRoleKey("PROJECT_MEMBER_MANAGER"),
            "Project Member Manager",
            Optional.empty(),
            Set.of(TeamPermission.MEMBER_MANAGE),
            RoleScopeType.WORK_PROJECT,
            NOW);
    fixture.repository.roles.put(projectRole.id(), projectRole);
    MemberRole projectGrant =
        MemberRole.grant(
            MemberRoleId.generate(),
            membership,
            projectRole,
            RoleScope.workProject(WorkProjectId.generate()),
            fixture.owner.id(),
            NOW,
            NOW,
            Optional.empty());
    fixture.repository.grants.put(projectGrant.id(), projectGrant);
    Principal target = fixture.addPrincipal("Target");

    assertThrows(
        PolicyDeniedException.class,
        () ->
            fixture.service.addMember(
                fixture.context(member, false, "project-scope-add"),
                team.team().id(),
                new AddTeamMemberCommand(target.id())));
    assertEquals(2, fixture.repository.members.size());
  }

  @Test
  void rejectsDuplicateMembershipAndDisabledTargetPrincipal() {
    Fixture fixture = new Fixture();
    TeamInitialization team = fixture.createTeam("member-team-3");
    Principal target = fixture.addPrincipal("Target");
    fixture.service.addMember(
        fixture.context(fixture.owner, false, "add-member-3"),
        team.team().id(),
        new AddTeamMemberCommand(target.id()));

    assertThrows(
        DomainValidationException.class,
        () ->
            fixture.service.addMember(
                fixture.context(fixture.owner, false, "duplicate-member-1"),
                team.team().id(),
                new AddTeamMemberCommand(target.id())));

    Principal disabled =
        fixture.addPrincipal("Disabled").transitionTo(PrincipalStatus.DISABLED, NOW);
    fixture.repository.principals.put(disabled.id(), disabled);
    assertThrows(
        PolicyDeniedException.class,
        () ->
            fixture.service.addMember(
                fixture.context(fixture.owner, false, "disabled-member-1"),
                team.team().id(),
                new AddTeamMemberCommand(disabled.id())));
  }

  @Test
  void queriesAreMembershipScopedAndStopAfterMembershipSuspension() {
    Fixture fixture = new Fixture();
    TeamInitialization team = fixture.createTeam("query-team-1");

    assertEquals(
        1,
        fixture
            .service
            .listTeams(new TeamAccessContext(fixture.owner, false), ORGANIZATION_ID)
            .size());
    assertEquals(
        team.team().id(),
        fixture
            .service
            .getTeam(new TeamAccessContext(fixture.owner, false), ORGANIZATION_ID, team.team().id())
            .id());

    TeamMember suspended = team.ownerMember().suspend(NOW);
    fixture.repository.members.put(suspended.id(), suspended);
    assertTrue(
        fixture
            .service
            .listTeams(new TeamAccessContext(fixture.owner, false), ORGANIZATION_ID)
            .isEmpty());
    assertThrows(
        PolicyDeniedException.class,
        () ->
            fixture.service.getTeam(
                new TeamAccessContext(fixture.owner, false), ORGANIZATION_ID, team.team().id()));
  }

  @Test
  void onlyPlatformAdministratorCanReadAndCompleteLegacyTeam() {
    Fixture fixture = new Fixture();
    TeamInitialization source = TeamInitialization.create(fixture.owner, "Legacy", NOW);
    UninitializedTeam legacy =
        new UninitializedTeam(
            source.team().id(),
            source.team().organizationId(),
            source.team().name(),
            source.team().status(),
            source.team().version(),
            source.team().audit());
    fixture.repository.legacyTeams.put(legacy.id(), legacy);
    Principal selectedOwner = fixture.addPrincipal("Legacy Owner");

    assertThrows(
        PolicyDeniedException.class,
        () ->
            fixture.service.getTeam(
                new TeamAccessContext(fixture.owner, false), ORGANIZATION_ID, legacy.id()));
    TeamView pending =
        fixture.service.getTeam(
            new TeamAccessContext(fixture.owner, true), ORGANIZATION_ID, legacy.id());
    assertEquals(TeamInitializationStatus.INITIALIZATION_REQUIRED, pending.initializationStatus());

    CommandExecution<TeamInitialization> completed =
        fixture.service.completeInitialization(
            fixture.context(fixture.owner, true, "complete-legacy-1"),
            legacy.id(),
            new CompleteTeamInitializationCommand(selectedOwner.id()));
    assertEquals(legacy.id(), completed.result().orElseThrow().team().id());
    assertEquals(
        selectedOwner.id(), completed.result().orElseThrow().ownerMember().userPrincipalId());
    assertEquals(
        fixture.owner.id(),
        completed.result().orElseThrow().team().audit().updatedBy().orElseThrow());
    assertFalse(fixture.repository.legacyTeams.containsKey(legacy.id()));
    assertEquals(
        TeamInitializationStatus.READY,
        fixture
            .service
            .getTeam(new TeamAccessContext(selectedOwner, false), ORGANIZATION_ID, legacy.id())
            .initializationStatus());
  }

  private static final class Fixture {

    private final InMemoryRepository repository = new InMemoryRepository();
    private final Principal owner = addPrincipal("Owner");
    private final TeamApplicationService service;

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
              transactions,
              time);
      service =
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
      return service
          .createTeam(context(owner, false, key), new CreateTeamCommand("Team"))
          .result()
          .orElseThrow();
    }

    private TeamCommandContext context(Principal actor, boolean administrator, String key) {
      return context(actor, administrator, key, Optional.empty());
    }

    private TeamCommandContext context(
        Principal actor, boolean administrator, String key, Optional<UUID> causationId) {
      return new TeamCommandContext(
          new TeamAccessContext(actor, administrator),
          IdempotencyKey.from(key),
          UUID.randomUUID(),
          causationId);
    }
  }

  private static final class InMemoryRepository
      implements TeamRepository,
          WorkspaceRepository,
          TeamMemberRepository,
          TeamRoleRepository,
          MemberRoleRepository,
          PrincipalRepository,
          DefaultPersonalAgentRepository,
          AgentProfileRepository,
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
    public List<Team> findActiveByMember(
        OrganizationId organizationId, PrincipalId userPrincipalId) {
      return teams.values().stream()
          .filter(Team::isActive)
          .filter(team -> team.organizationId().equals(organizationId))
          .filter(
              team ->
                  members.values().stream()
                      .anyMatch(
                          member ->
                              member.scope().teamId().equals(team.id())
                                  && member.userPrincipalId().equals(userPrincipalId)
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

    private List<TeamMember> findMembersByTeam(OrganizationId organizationId, TeamId teamId) {
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
    public MemberRole create(MemberRole role) {
      grants.put(role.id(), role);
      return role;
    }

    @Override
    public List<MemberRole> findByMember(OrganizationId organizationId, TeamMemberId memberId) {
      return grants.values().stream()
          .filter(role -> role.teamScope().organizationId().equals(organizationId))
          .filter(role -> role.teamMemberId().equals(memberId))
          .toList();
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
      Principal existing =
          findByExternalIdentity(
                  candidate.scope().organizationId(),
                  candidate.externalIdentity().orElseThrow().provider(),
                  candidate.externalIdentity().orElseThrow().subject())
              .orElse(null);
      if (existing != null) {
        return new PrincipalProvisioningResult(existing, false);
      }
      principals.put(candidate.id(), candidate);
      return new PrincipalProvisioningResult(candidate, true);
    }

    @Override
    public PersonalAgentInitialization initializeIfAbsent(PersonalAgentInitialization candidate) {
      TeamMemberId memberId = candidate.agentProfile().ownerMemberId().orElseThrow();
      return agents.computeIfAbsent(memberId, ignored -> candidate);
    }

    @Override
    public AgentProfile create(AgentProfile profile) {
      throw new UnsupportedOperationException();
    }

    @Override
    public AgentProfile update(AgentProfile profile) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<AgentProfile> findById(OrganizationId organizationId, AgentProfileId id) {
      return agents.values().stream()
          .map(PersonalAgentInitialization::agentProfile)
          .filter(profile -> profile.id().equals(id))
          .filter(profile -> profile.scope().organizationId().equals(organizationId))
          .findFirst();
    }

    @Override
    public Optional<AgentProfile> findActiveDefaultPersonal(
        OrganizationId organizationId, TeamMemberId ownerMemberId) {
      return Optional.ofNullable(agents.get(ownerMemberId))
          .map(PersonalAgentInitialization::agentProfile);
    }

    @Override
    public Optional<AgentProfile> findActiveByAgentPrincipalId(
        OrganizationId organizationId, PrincipalId agentPrincipalId) {
      return agents.values().stream()
          .map(PersonalAgentInitialization::agentProfile)
          .filter(profile -> profile.scope().organizationId().equals(organizationId))
          .filter(profile -> profile.agentPrincipalId().equals(agentPrincipalId))
          .filter(profile -> profile.status()
              == io.crewscope.domain.workspace.AgentProfileStatus.ACTIVE)
          .findFirst();
    }

    @Override
    public List<AgentProfile> findPage(
        OrganizationId organizationId, int offset, int limit) {
      return agents.values().stream()
          .map(PersonalAgentInitialization::agentProfile)
          .filter(profile -> profile.scope().organizationId().equals(organizationId))
          .skip(offset)
          .limit(limit)
          .toList();
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
      String key = request.organizationId() + ":" + request.idempotencyKey();
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
      String key = organizationId + ":" + idempotencyKey;
      receipts.put(key, new ReceiptEntry(receipts.get(key).request(), receipt));
    }

    private record ReceiptEntry(CommandReservationRequest request, CommandReceipt receipt) {}
  }

  private static final class DirectTransactions implements TransactionExecutor {

    @Override
    public <T> T required(Supplier<T> operation) {
      return operation.get();
    }
  }
}
