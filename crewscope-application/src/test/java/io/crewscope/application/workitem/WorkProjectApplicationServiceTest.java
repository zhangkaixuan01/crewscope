package io.crewscope.application.workitem;

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
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.IdempotencyConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.BuiltInTeamRole;
import io.crewscope.domain.team.MemberRole;
import io.crewscope.domain.team.MemberRoleId;
import io.crewscope.domain.team.RoleScope;
import io.crewscope.domain.team.RoleScopeType;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamPermission;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamRoleId;
import io.crewscope.domain.team.TeamRoleKey;
import io.crewscope.domain.team.UninitializedTeam;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.WorkProjectKey;
import io.crewscope.domain.workitem.WorkProjectKeyConflictException;
import io.crewscope.domain.workspace.Workspace;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class WorkProjectApplicationServiceTest {

  private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-08T05:00:00Z");

  @Test
  void createsProjectEventAndOutboxThenReplaysTheOriginalReceipt() {
    Fixture fixture = new Fixture();
    TeamCommandContext context = fixture.commandContext("create-project-1");

    CommandExecution<WorkProject> first =
        fixture.service.create(
            context,
            fixture.initialization.team().id(),
            new CreateWorkProjectCommand("CRW", "  CrewScope  "));
    CommandExecution<WorkProject> replay =
        fixture.service.create(
            context,
            fixture.initialization.team().id(),
            new CreateWorkProjectCommand("CRW", "CrewScope"));

    WorkProject project = first.result().orElseThrow();
    assertEquals("CRW", project.key().value());
    assertEquals("CrewScope", project.name());
    assertEquals(fixture.initialization.defaultWorkspace().id(), project.scope().workspaceId());
    assertEquals(fixture.actor.id(), project.audit().createdBy().orElseThrow());
    assertEquals("WORK_PROJECT_CREATED", fixture.store.events.get(0).eventType().value());
    assertEquals(project.id().value(), fixture.store.events.get(0).aggregate().id());
    assertEquals(1, fixture.store.outbox.size());
    assertTrue(replay.replayed());
    assertEquals(first.receipt(), replay.receipt());
    assertEquals(1, fixture.store.projects.size());
  }

  @Test
  void rejectsChangedIdempotentContentAndAKeyAlreadyUsedByAnotherCommand() {
    Fixture fixture = new Fixture();
    TeamId teamId = fixture.initialization.team().id();
    fixture.service.create(
        fixture.commandContext("create-project-2"),
        teamId,
        new CreateWorkProjectCommand("OPS", "Operations"));

    assertThrows(
        IdempotencyConflictException.class,
        () ->
            fixture.service.create(
                fixture.commandContext("create-project-2"),
                teamId,
                new CreateWorkProjectCommand("OPS", "Changed")));
    assertThrows(
        WorkProjectKeyConflictException.class,
        () ->
            fixture.service.create(
                fixture.commandContext("create-project-3"),
                teamId,
                new CreateWorkProjectCommand("OPS", "Duplicate")));
    assertEquals(1, fixture.store.projects.size());
    assertEquals(1, fixture.store.events.size());
  }

  @Test
  void includesCausationInTheProjectCommandRequestHash() {
    Fixture fixture = new Fixture();
    TeamId teamId = fixture.initialization.team().id();
    fixture.service.create(
        fixture.commandContext("create-project-causation", Optional.of(UUID.randomUUID())),
        teamId,
        new CreateWorkProjectCommand("CAU", "Causation"));

    assertThrows(
        IdempotencyConflictException.class,
        () ->
            fixture.service.create(
                fixture.commandContext(
                    "create-project-causation", Optional.of(UUID.randomUUID())),
                teamId,
                new CreateWorkProjectCommand("CAU", "Causation")));
  }

  @Test
  void requiresTeamScopedProjectManagementPermission() {
    Fixture fixture = new Fixture();
    TeamRole memberRole =
        TeamRole.createBuiltIn(
            TeamRoleId.generate(),
            fixture.initialization.team().scope(),
            BuiltInTeamRole.MEMBER,
            NOW);
    fixture.store.roles = List.of(memberRole);
    fixture.store.grants =
        List.of(
            MemberRole.grant(
                MemberRoleId.generate(),
                fixture.initialization.ownerMember(),
                memberRole,
                RoleScope.team(),
                fixture.actor.id(),
                NOW,
                NOW,
                Optional.empty()));

    assertThrows(
        PolicyDeniedException.class,
        () ->
            fixture.service.create(
                fixture.commandContext("create-project-member"),
                fixture.initialization.team().id(),
                new CreateWorkProjectCommand("WEB", "Web")));
    assertTrue(fixture.store.projects.isEmpty());
  }

  @Test
  void doesNotPromoteAWorkProjectScopedGrantToTeamProjectCreation() {
    Fixture fixture = new Fixture();
    TeamRole scopedRole =
        TeamRole.createCustom(
            TeamRoleId.generate(),
            fixture.initialization.team().scope(),
            new TeamRoleKey("SCOPED_PROJECT_MANAGER"),
            "Scoped Project Manager",
            Optional.empty(),
            Set.of(TeamPermission.WORK_PROJECT_MANAGE),
            RoleScopeType.WORK_PROJECT,
            NOW);
    fixture.store.roles = List.of(scopedRole);
    fixture.store.grants =
        List.of(
            MemberRole.grant(
                MemberRoleId.generate(),
                fixture.initialization.ownerMember(),
                scopedRole,
                RoleScope.workProject(WorkProjectId.generate()),
                fixture.actor.id(),
                NOW,
                NOW,
                Optional.empty()));

    assertThrows(
        PolicyDeniedException.class,
        () ->
            fixture.service.create(
                fixture.commandContext("create-project-scoped"),
                fixture.initialization.team().id(),
                new CreateWorkProjectCommand("API", "API")));
  }

  @Test
  void authorizesListDetailAndKeyChecksByActiveMembershipAndPassesCursor() {
    Fixture fixture = new Fixture();
    WorkProject project = fixture.createProject("DOC", "Documentation");
    WorkProjectCursor cursor = new WorkProjectCursor(NOW, project.id());

    WorkProjectPage page =
        fixture.service.list(
            fixture.access(),
            fixture.organizationId,
            fixture.initialization.team().id(),
            Optional.of(cursor),
            25);

    assertEquals(project.id(), page.items().get(0).id());
    assertEquals(Optional.of(cursor), fixture.store.lastQuery.cursor());
    assertEquals(25, fixture.store.lastQuery.limit());
    assertEquals(
        project.id(),
        fixture
            .service
            .get(
                fixture.access(),
                fixture.organizationId,
                fixture.initialization.team().id(),
                project.id())
            .id());
    assertFalse(
        fixture
            .service
            .keyAvailability(
                fixture.access(),
                fixture.organizationId,
                fixture.initialization.team().id(),
                project.key())
            .available());
    assertTrue(
        fixture
            .service
            .keyAvailability(
                fixture.access(),
                fixture.organizationId,
                fixture.initialization.team().id(),
                new WorkProjectKey("NEW"))
            .available());

    fixture.store.members = List.of(fixture.initialization.ownerMember().suspend(NOW));
    assertThrows(
        PolicyDeniedException.class,
        () ->
            fixture.service.list(
                fixture.access(),
                fixture.organizationId,
                fixture.initialization.team().id(),
                Optional.empty(),
                25));
  }

  @Test
  void hidesAProjectThatBelongsToAnotherTeam() {
    Fixture fixture = new Fixture();
    WorkProject project = fixture.createProject("SEC", "Security");

    assertThrows(
        AggregateNotFoundException.class,
        () ->
            fixture.service.get(
                fixture.access(),
                fixture.organizationId,
                TeamId.generate(),
                project.id()));
  }

  private static final class Fixture {
    private final OrganizationId organizationId = OrganizationId.generate();
    private final Principal actor =
        Principal.create(
            PrincipalId.generate(),
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            "Owner",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);
    private final TeamInitialization initialization = TeamInitialization.create(actor, "Team", NOW);
    private final Store store = new Store(initialization, actor);
    private final TeamMembershipQuery membershipQuery =
        (organization, team) -> store.members;
    private final TeamRoleRepository roleRepository =
        new TeamRoleRepository() {
          @Override
          public List<TeamRole> createAll(List<TeamRole> values) {
            store.roles = List.copyOf(values);
            return store.roles;
          }

          @Override
          public List<TeamRole> findByTeam(OrganizationId organization, TeamId team) {
            return store.roles;
          }
        };
    private final WorkProjectApplicationService service =
        new WorkProjectApplicationService(
            store,
            store,
            store,
            membershipQuery,
            roleRepository,
            store,
            store,
            store,
            store,
            new DirectTransactionExecutor(),
            () -> NOW);

    private TeamAccessContext access() {
      return new TeamAccessContext(actor, false);
    }

    private TeamCommandContext commandContext(String key) {
      return commandContext(key, Optional.empty());
    }

    private TeamCommandContext commandContext(String key, Optional<UUID> causationId) {
      return new TeamCommandContext(
          access(), IdempotencyKey.from(key), UUID.randomUUID(), causationId);
    }

    private WorkProject createProject(String key, String name) {
      WorkProject project =
          WorkProject.create(
              WorkProjectId.generate(),
              new WorkProjectKey(key),
              name,
              initialization.team(),
              initialization.defaultWorkspace(),
              actor,
              NOW);
      store.create(project);
      return project;
    }
  }

  private static final class Store
      implements WorkProjectRepository,
          TeamRepository,
          WorkspaceRepository,
          MemberRoleRepository,
          DomainEventStore,
          OutboxRepository,
          CommandReceiptStore {

    private final TeamInitialization initialization;
    private final Map<WorkProjectId, WorkProject> projects = new LinkedHashMap<>();
    private final Map<String, ReceiptEntry> receipts = new HashMap<>();
    private final List<DomainEventEnvelope<? extends DomainEvent>> events = new ArrayList<>();
    private final List<PendingOutboxEvent> outbox = new ArrayList<>();
    private List<TeamMember> members;
    private List<TeamRole> roles;
    private List<MemberRole> grants;
    private WorkProjectQuery lastQuery;

    private Store(TeamInitialization initialization, Principal actor) {
      this.initialization = initialization;
      this.members = List.of(initialization.ownerMember());
      TeamRole ownerRole =
          TeamRole.createBuiltIn(
              TeamRoleId.generate(),
              initialization.team().scope(),
              BuiltInTeamRole.TEAM_OWNER,
              NOW);
      this.roles = List.of(ownerRole);
      this.grants =
          List.of(
              MemberRole.grantOwner(
                  MemberRoleId.generate(),
                  initialization.team(),
                  initialization.ownerMember(),
                  ownerRole,
                  actor.id(),
                  NOW));
    }

    @Override
    public WorkProject create(WorkProject project) {
      projects.put(project.id(), project);
      return project;
    }

    @Override
    public WorkProject update(WorkProject project) {
      projects.put(project.id(), project);
      return project;
    }

    @Override
    public Optional<WorkProject> findById(OrganizationId organizationId, WorkProjectId id) {
      return Optional.ofNullable(projects.get(id))
          .filter(project -> project.scope().organizationId().equals(organizationId));
    }

    @Override
    public Optional<WorkProject> findByKey(
        OrganizationId organizationId, TeamId teamId, WorkProjectKey key) {
      return projects.values().stream()
          .filter(project -> project.scope().organizationId().equals(organizationId))
          .filter(project -> project.scope().teamId().equals(teamId))
          .filter(project -> project.key().equals(key))
          .findFirst();
    }

    @Override
    public List<WorkProject> findByTeam(OrganizationId organizationId, TeamId teamId) {
      return projects.values().stream()
          .filter(project -> project.scope().organizationId().equals(organizationId))
          .filter(project -> project.scope().teamId().equals(teamId))
          .toList();
    }

    @Override
    public WorkProjectPage findPage(WorkProjectQuery query) {
      lastQuery = query;
      List<WorkProject> values =
          findByTeam(query.organizationId(), query.teamId()).stream()
              .sorted(
                  Comparator.comparing((WorkProject value) -> value.audit().updatedAt())
                      .thenComparing(value -> value.id().toString())
                      .reversed())
              .limit(query.limit())
              .toList();
      return new WorkProjectPage(values, Optional.empty());
    }

    @Override
    public Team create(Team team) {
      return team;
    }

    @Override
    public Optional<Team> findById(OrganizationId organizationId, TeamId id) {
      return Optional.of(initialization.team())
          .filter(team -> team.organizationId().equals(organizationId) && team.id().equals(id));
    }

    @Override
    public Optional<Team> lockById(OrganizationId organizationId, TeamId id) {
      return findById(organizationId, id);
    }

    @Override
    public Optional<UninitializedTeam> findUninitializedById(
        OrganizationId organizationId, TeamId id) {
      return Optional.empty();
    }

    @Override
    public Workspace create(Workspace workspace) {
      return workspace;
    }

    @Override
    public Optional<Workspace> findById(OrganizationId organizationId, WorkspaceId id) {
      return Optional.of(initialization.defaultWorkspace())
          .filter(
              workspace ->
                  workspace.scope().organizationId().equals(organizationId)
                      && workspace.id().equals(id));
    }

    @Override
    public MemberRole create(MemberRole memberRole) {
      grants = new ArrayList<>(grants);
      grants.add(memberRole);
      return memberRole;
    }

    @Override
    public List<MemberRole> findByMember(
        OrganizationId organizationId, io.crewscope.domain.team.TeamMemberId memberId) {
      return grants;
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
      if (!existing.request.commandType().equals(request.commandType())
          || !existing.request.requestHash().equals(request.requestHash())) {
        throw new IdempotencyConflictException(
            request.idempotencyKey().value(),
            existing.request.requestHash().value(),
            request.requestHash().value());
      }
      return CommandReservation.replay(existing.receipt);
    }

    @Override
    public void complete(
        OrganizationId organizationId,
        IdempotencyKey idempotencyKey,
        CommandReceipt receipt,
        UtcTimestamp completedAt) {
      String key = organizationId + ":" + idempotencyKey;
      ReceiptEntry existing = receipts.get(key);
      receipts.put(key, new ReceiptEntry(existing.request, receipt));
    }

    private record ReceiptEntry(CommandReservationRequest request, CommandReceipt receipt) {}
  }

  private static final class DirectTransactionExecutor implements TransactionExecutor {

    @Override
    public <T> T required(Supplier<T> operation) {
      return operation.get();
    }
  }
}
