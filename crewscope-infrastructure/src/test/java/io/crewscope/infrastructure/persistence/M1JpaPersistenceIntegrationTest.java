package io.crewscope.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.identity.IdentityMappingRequest;
import io.crewscope.application.identity.IdentityMappingResult;
import io.crewscope.application.identity.IdentityMappingService;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.responsibility.AssignResponsibilityCommand;
import io.crewscope.application.responsibility.GateReviewerAssignmentService;
import io.crewscope.application.responsibility.ReleaseResponsibilityCommand;
import io.crewscope.application.responsibility.ReplaceOwnerCommand;
import io.crewscope.application.responsibility.ResponsibilityAssignmentService;
import io.crewscope.application.responsibility.ResponsibilityCommandService;
import io.crewscope.application.responsibility.ResponsibilityQueryService;
import io.crewscope.application.team.AddTeamMemberCommand;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.CreateTeamCommand;
import io.crewscope.application.team.DefaultPersonalAgentRepository;
import io.crewscope.application.team.DefaultPersonalAgentService;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamApplicationService;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamCreationService;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemCommentRepository;
import io.crewscope.application.workitem.WorkItemRepository;
import io.crewscope.application.workitem.WorkItemResourceLinkRepository;
import io.crewscope.application.workitem.WorkItemTimelineEvent;
import io.crewscope.application.workitem.WorkItemTimelinePage;
import io.crewscope.application.workitem.WorkItemTimelineRepository;
import io.crewscope.application.workitem.WorkItemTimelineService;
import io.crewscope.application.workitem.AddWorkItemCommentCommand;
import io.crewscope.application.workitem.CreateNativeWorkItemCommand;
import io.crewscope.application.workitem.CreateWorkProjectCommand;
import io.crewscope.application.workitem.LinkWorkItemResourceCommand;
import io.crewscope.application.workitem.TransitionWorkItemCommand;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.application.workitem.WorkItemCollaborationService;
import io.crewscope.application.workitem.WorkItemCommandService;
import io.crewscope.application.workitem.WorkItemDetails;
import io.crewscope.application.workitem.WorkItemQueryService;
import io.crewscope.application.workitem.WorkProjectApplicationService;
import io.crewscope.application.workitem.WorkProjectPage;
import io.crewscope.application.workitem.WorkProjectQuery;
import io.crewscope.application.workitem.WorkProjectRepository;
import io.crewscope.domain.identity.ExternalIdentity;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityConflictException;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.responsibility.ActiveOwnerExpectation;
import io.crewscope.domain.responsibility.ReviewerEligibilityPolicy;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamJoinMethod;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamRoleStatus;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemComment;
import io.crewscope.domain.workitem.WorkItemCommentId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemKeyConflictException;
import io.crewscope.domain.workitem.WorkItemLabel;
import io.crewscope.domain.workitem.WorkItemPriority;
import io.crewscope.domain.workitem.WorkItemResourceLink;
import io.crewscope.domain.workitem.WorkItemResourceLinkId;
import io.crewscope.domain.workitem.WorkItemResourceType;
import io.crewscope.domain.workitem.WorkItemSource;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkItemType;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.WorkProjectKey;
import io.crewscope.domain.workitem.WorkProjectKeyConflictException;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileStatus;
import io.crewscope.domain.workspace.PersonalAgentInitialization;
import io.crewscope.domain.workspace.Workspace;
import io.crewscope.domain.workspace.WorkspaceScope;
import io.crewscope.domain.workspace.WorkspaceStatus;
import io.crewscope.infrastructure.persistence.command.JdbcCommandReceiptStore;
import io.crewscope.infrastructure.persistence.event.JdbcDomainEventStore;
import io.crewscope.infrastructure.persistence.event.JdbcOutboxRepository;
import io.crewscope.infrastructure.persistence.responsibility.JpaResponsibilityAssignmentRepositoryAdapter;
import io.crewscope.infrastructure.persistence.responsibility.ResponsibilityPersistenceMapper;
import io.crewscope.infrastructure.persistence.team.JpaAgentProfileRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.JpaMemberRoleRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.JpaPrincipalRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.JpaTeamMemberRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.JpaTeamRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.JpaTeamRoleRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.JpaWorkspaceRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.TeamInitializationRequiredException;
import io.crewscope.infrastructure.persistence.team.TeamPersistenceMapper;
import io.crewscope.infrastructure.persistence.workitem.JpaWorkItemCommentRepositoryAdapter;
import io.crewscope.infrastructure.persistence.workitem.JpaWorkItemRepositoryAdapter;
import io.crewscope.infrastructure.persistence.workitem.JpaWorkItemResourceLinkRepositoryAdapter;
import io.crewscope.infrastructure.persistence.workitem.JdbcWorkItemTimelineRepository;
import io.crewscope.infrastructure.persistence.workitem.JpaWorkProjectRepositoryAdapter;
import io.crewscope.infrastructure.persistence.workitem.WorkItemEntityMapper;
import io.crewscope.infrastructure.persistence.workitem.WorkPersistenceMapper;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import io.crewscope.infrastructure.transaction.SpringTransactionExecutor;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Proves the complete M1 persistence graph against migrated PostgreSQL. */
@SpringBootTest(
    classes = M1JpaPersistenceIntegrationTest.TestApplication.class,
    properties = {
      "spring.flyway.schemas=crewscope",
      "spring.flyway.default-schema=crewscope",
      "spring.flyway.create-schemas=true",
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.jpa.properties.hibernate.default_schema=crewscope",
      "spring.jpa.open-in-view=false"
    })
class M1JpaPersistenceIntegrationTest extends AbstractPostgresRedisContainerIntegrationTest {
  private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-08T02:00:00Z");
  private static final UtcTimestamp LATER = UtcTimestamp.parse("2026-08-08T03:00:00Z");

  @Autowired private TeamRepository teamRepository;
  @Autowired private WorkspaceRepository workspaceRepository;
  @Autowired private TeamMemberRepository teamMemberRepository;
  @Autowired private TeamMembershipQuery teamMembershipQuery;
  @Autowired private TeamRoleRepository teamRoleRepository;
  @Autowired private MemberRoleRepository memberRoleRepository;
  @Autowired private DefaultPersonalAgentRepository defaultPersonalAgentRepository;
  @Autowired private AgentProfileRepository agentProfileRepository;
  @Autowired private WorkProjectRepository projectRepository;
  @Autowired private WorkItemRepository workItemRepository;
  @Autowired private WorkItemCommentRepository commentRepository;
  @Autowired private WorkItemResourceLinkRepository resourceLinkRepository;
  @Autowired private WorkItemTimelineRepository workItemTimelineRepository;
  @Autowired private ResponsibilityAssignmentRepository assignmentRepository;
  @Autowired private TransactionExecutor transactionExecutor;
  @Autowired private PrincipalRepository principalRepository;
  @Autowired private DomainEventStore domainEventStore;
  @Autowired private OutboxRepository outboxRepository;
  @Autowired private CommandReceiptStore commandReceiptStore;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void resetBusinessData() {
    jdbcTemplate.execute("TRUNCATE TABLE crewscope.organization CASCADE");
  }

  @Test
  void persistsTheCompleteTeamFoundationAndReturnsOneDefaultPersonalAgentForRetries() {
    Foundation foundation = createFoundation("team-foundation");
    TeamInitialization value = foundation.initialization();

    assertEquals(
        value.team().id(),
        teamRepository.findById(foundation.organizationId(), value.team().id()).orElseThrow().id());
    assertEquals(
        List.of(value.team().id()),
        teamRepository
            .findActiveByMember(foundation.organizationId(), foundation.creator().id())
            .stream()
            .map(io.crewscope.domain.team.Team::id)
            .toList());
    assertEquals(
        value.defaultWorkspace().scope(),
        workspaceRepository
            .findById(foundation.organizationId(), value.defaultWorkspace().id())
            .orElseThrow()
            .scope());
    assertEquals(
        value.ownerMember().userPrincipalId(),
        teamMemberRepository
            .findById(foundation.organizationId(), value.ownerMember().id())
            .orElseThrow()
            .userPrincipalId());
    assertEquals(
        5, teamRoleRepository.findByTeam(foundation.organizationId(), value.team().id()).size());
    assertEquals(
        1,
        memberRoleRepository
            .findByMember(foundation.organizationId(), value.ownerMember().id())
            .size());

    DefaultPersonalAgentService retryService =
        new DefaultPersonalAgentService(
            defaultPersonalAgentRepository, transactionExecutor, () -> LATER);
    var retried =
        retryService.ensureDefault(
            value.ownerMember(), value.defaultWorkspace(), foundation.creator());

    assertEquals(value.ownerPersonalAgent().agentPrincipal().id(), retried.agentPrincipal().id());
    assertEquals(value.ownerPersonalAgent().agentProfile().id(), retried.agentProfile().id());
    assertTrue(
        agentProfileRepository
            .findActiveDefaultPersonal(foundation.organizationId(), value.ownerMember().id())
            .isPresent());
    assertEquals(
        1,
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crewscope.agent_profile", Integer.class));

    assertEquals(
        1, teamRepository.update(value.team().archive(foundation.creator().id(), LATER)).version());
    assertEquals(
        1,
        workspaceRepository
            .update(value.defaultWorkspace().archive(foundation.creator().id(), LATER))
            .version());
    assertEquals(
        1, teamMemberRepository.update(value.ownerMember().recordActivity(LATER)).version());
    assertEquals(
        TeamRoleStatus.DISABLED,
        teamRoleRepository
            .update(value.builtInRoles().get(0).transitionTo(TeamRoleStatus.DISABLED, LATER))
            .status());
    assertEquals(1, memberRoleRepository.update(value.ownerRole().revoke(LATER)).version());
    assertEquals(
        1,
        agentProfileRepository
            .update(
                value.ownerPersonalAgent().agentProfile().disable(foundation.creator().id(), LATER))
            .version());
  }

  @Test
  void commitsTeamFoundationEventOutboxAndReceiptAndReplaysAtomically() {
    OrganizationId organizationId = OrganizationId.generate();
    jdbcTemplate.update(
        "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Atomic', 'ACTIVE')",
        organizationId.value());
    Principal creator = createUser(organizationId, "Atomic Owner");
    TeamCreationService creationService =
        new TeamCreationService(
            teamRepository,
            workspaceRepository,
            teamMemberRepository,
            teamRoleRepository,
            memberRoleRepository,
            defaultPersonalAgentRepository,
            transactionExecutor,
            () -> NOW);
    TeamApplicationService service =
        new TeamApplicationService(
            creationService,
            teamRepository,
            workspaceRepository,
            teamMemberRepository,
            teamMembershipQuery,
            teamRoleRepository,
            memberRoleRepository,
            principalRepository,
            defaultPersonalAgentRepository,
            domainEventStore,
            outboxRepository,
            commandReceiptStore,
            transactionExecutor,
            () -> NOW);
    TeamCommandContext context =
        new TeamCommandContext(
            new TeamAccessContext(creator, false),
            IdempotencyKey.from("create-team-atomic-1"),
            UUID.randomUUID(),
            Optional.empty());

    var first = service.createTeam(context, new CreateTeamCommand("Atomic Team"));
    var replay = service.createTeam(context, new CreateTeamCommand("Atomic Team"));

    assertFalse(first.replayed());
    assertTrue(replay.replayed());
    assertEquals(first.receipt(), replay.receipt());
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crewscope.domain_event WHERE event_type = 'TEAM_CREATED'",
            Integer.class));
    assertEquals(
        1,
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crewscope.outbox_event", Integer.class));
    assertEquals(
        "COMPLETED",
        jdbcTemplate.queryForObject("SELECT status FROM crewscope.command_receipt", String.class));
  }

  @Test
  void serializesConcurrentLegacyTeamCompletionToOneFoundation() throws Exception {
    OrganizationId organizationId = OrganizationId.generate();
    jdbcTemplate.update(
        "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Legacy Atomic',"
            + " 'ACTIVE')",
        organizationId.value());
    Principal administrator = createUser(organizationId, "Administrator");
    Principal selectedOwner = createUser(organizationId, "Selected Owner");
    TeamId legacyTeamId = TeamId.generate();
    jdbcTemplate.update(
        """
        INSERT INTO crewscope.team (
            id, organization_id, name, status, version, created_at, updated_at
        ) VALUES (?, ?, 'Legacy Concurrent', 'ACTIVE', 0, ?, ?)
        """,
        legacyTeamId.value(),
        organizationId.value(),
        Timestamp.from(NOW.value()),
        Timestamp.from(NOW.value()));
    TeamApplicationService service = teamApplicationService();
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger completed = new AtomicInteger();
    AtomicInteger rejected = new AtomicInteger();
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first =
          executor.submit(
              () ->
                  completeLegacy(
                      service,
                      administrator,
                      selectedOwner,
                      legacyTeamId,
                      "legacy-concurrent-1",
                      start,
                      completed,
                      rejected));
      var second =
          executor.submit(
              () ->
                  completeLegacy(
                      service,
                      administrator,
                      selectedOwner,
                      legacyTeamId,
                      "legacy-concurrent-2",
                      start,
                      completed,
                      rejected));
      start.countDown();
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }

    assertEquals(1, completed.get());
    assertEquals(1, rejected.get());
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crewscope.team_member WHERE team_id = ?",
            Integer.class,
            legacyTeamId.value()));
    assertEquals(
        5,
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crewscope.team_role WHERE team_id = ?",
            Integer.class,
            legacyTeamId.value()));
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crewscope.domain_event WHERE event_type ="
                + " 'TEAM_INITIALIZATION_COMPLETED'",
            Integer.class));
  }

  @Test
  void serializesConcurrentMemberJoinToOneMembershipAndStableDomainRejection() throws Exception {
    Foundation foundation = createFoundation("member-join-concurrency");
    Principal target = createUser(foundation.organizationId(), "Concurrent Member");
    TeamApplicationService service = teamApplicationService();
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger completed = new AtomicInteger();
    AtomicInteger rejected = new AtomicInteger();
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first =
          executor.submit(
              () ->
                  addMember(
                      service,
                      foundation,
                      target,
                      "member-concurrent-1",
                      start,
                      completed,
                      rejected));
      var second =
          executor.submit(
              () ->
                  addMember(
                      service,
                      foundation,
                      target,
                      "member-concurrent-2",
                      start,
                      completed,
                      rejected));
      start.countDown();
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }

    assertEquals(1, completed.get());
    assertEquals(1, rejected.get());
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM crewscope.team_member
            WHERE team_id = ? AND user_principal_id = ?
            """,
            Integer.class,
            foundation.initialization().team().id().value(),
            target.id().value()));
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crewscope.domain_event WHERE event_type = 'TEAM_MEMBER_JOINED'",
            Integer.class));
  }

  @Test
  void atomicallyMapsAConcurrentExternalSubjectWithoutInferringTeamMembership() throws Exception {
    OrganizationId organizationId = OrganizationId.generate();
    jdbcTemplate.update(
        "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Identity', 'ACTIVE')",
        organizationId.value());
    IdentityMappingService service =
        new IdentityMappingService(
            principalRepository,
            domainEventStore,
            outboxRepository,
            transactionExecutor,
            () -> NOW);
    CountDownLatch start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first = executor.submit(() -> mapIdentity(service, organizationId, start));
      var second = executor.submit(() -> mapIdentity(service, organizationId, start));
      start.countDown();

      IdentityMappingResult firstResult = first.get(10, TimeUnit.SECONDS);
      IdentityMappingResult secondResult = second.get(10, TimeUnit.SECONDS);
      assertEquals(firstResult.principal().id(), secondResult.principal().id());
      assertEquals(1, (firstResult.created() ? 1 : 0) + (secondResult.created() ? 1 : 0));
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }

    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM crewscope.principal
            WHERE organization_id = ? AND identity_provider = 'oidc/company'
              AND external_subject = 'private-concurrent-subject'
            """,
            Integer.class,
            organizationId.value()));
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crewscope.domain_event WHERE event_type = 'USER_IDENTITY_MAPPED'",
            Integer.class));
    assertEquals(
        1,
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crewscope.outbox_event", Integer.class));
    assertEquals(
        0,
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crewscope.team_member", Integer.class));
    String payload =
        jdbcTemplate.queryForObject(
            """
            SELECT payload::text FROM crewscope.domain_event
            WHERE event_type = 'USER_IDENTITY_MAPPED'
            """,
            String.class);
    assertFalse(payload.contains("private-concurrent-subject"));
  }

  @Test
  void rejectsWorkspaceAndAgentProfileUpdatesFromMismatchedImmutableScopes() {
    Foundation foundation = createFoundation("update-scope");
    Workspace workspace = foundation.initialization().defaultWorkspace();
    Workspace wrongTeamWorkspace =
        Workspace.reconstitute(
            workspace.id(),
            WorkspaceScope.team(foundation.organizationId(), new TeamId(UUID.randomUUID())),
            workspace.type(),
            workspace.ownerPrincipalId(),
            workspace.name(),
            WorkspaceStatus.ARCHIVED,
            workspace.version() + 1,
            workspace.audit().modifiedBy(foundation.creator().id(), LATER));

    assertThrows(
        AggregateNotFoundException.class, () -> workspaceRepository.update(wrongTeamWorkspace));
    Workspace unchangedWorkspace =
        workspaceRepository.findById(foundation.organizationId(), workspace.id()).orElseThrow();
    assertEquals(WorkspaceStatus.ACTIVE, unchangedWorkspace.status());
    assertEquals(0, unchangedWorkspace.version());

    AgentProfile profile = foundation.initialization().ownerPersonalAgent().agentProfile();
    AgentProfile wrongWorkspaceProfile =
        AgentProfile.reconstitute(
            profile.id(),
            profile.scope(),
            new WorkspaceId(UUID.randomUUID()),
            profile.agentPrincipalId(),
            profile.ownerMemberId(),
            profile.type(),
            profile.defaultProfile(),
            AgentProfileStatus.DISABLED,
            profile.version() + 1,
            profile.audit().modifiedBy(foundation.creator().id(), LATER));

    assertThrows(
        AggregateNotFoundException.class,
        () -> agentProfileRepository.update(wrongWorkspaceProfile));
    AgentProfile unchangedProfile =
        agentProfileRepository.findById(foundation.organizationId(), profile.id()).orElseThrow();
    assertEquals(AgentProfileStatus.ACTIVE, unchangedProfile.status());
    assertEquals(0, unchangedProfile.version());
  }

  @Test
  void rejectsDefaultPersonalAgentInitializationForAStaleActiveMemberSnapshot() {
    Foundation foundation = createFoundation("stale-personal-agent-member");
    Principal memberUser = createUser(foundation.organizationId(), "Former Member");
    TeamMember activeSnapshot =
        teamMemberRepository.create(
            foundation
                .initialization()
                .team()
                .joinMember(TeamMemberId.generate(), memberUser, TeamJoinMethod.OIDC, NOW));
    PersonalAgentInitialization candidate =
        PersonalAgentInitialization.createDefault(
            activeSnapshot, foundation.initialization().defaultWorkspace(), memberUser, LATER);
    jdbcTemplate.update(
        """
        UPDATE crewscope.team_member
        SET status = 'SUSPENDED', version = version + 1, updated_at = ?
        WHERE organization_id = ? AND team_id = ? AND id = ?
        """,
        Timestamp.from(LATER.value()),
        foundation.organizationId().value(),
        activeSnapshot.scope().teamId().value(),
        activeSnapshot.id().value());

    assertThrows(
        DomainValidationException.class,
        () -> defaultPersonalAgentRepository.initializeIfAbsent(candidate));
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crewscope.principal WHERE id = ?",
            Integer.class,
            candidate.agentPrincipal().id().value()));
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crewscope.agent_profile WHERE id = ?",
            Integer.class,
            candidate.agentProfile().id().value()));
  }

  @Test
  void reportsAnExplicitInitializationRequirementForMigratedLegacyTeams() {
    Foundation foundation = createFoundation("legacy-team");
    UUID legacyTeamId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO crewscope.team (id, organization_id, name, status)
        VALUES (?, ?, 'Legacy Team', 'ACTIVE')
        """,
        legacyTeamId,
        foundation.organizationId().value());

    TeamInitializationRequiredException failure =
        assertThrows(
            TeamInitializationRequiredException.class,
            () -> teamRepository.findById(foundation.organizationId(), new TeamId(legacyTeamId)));

    assertEquals(legacyTeamId, failure.teamId());
    assertTrue(failure.getMessage().contains("Owner and default Workspace"));
    assertEquals(
        legacyTeamId,
        teamRepository
            .findUninitializedById(foundation.organizationId(), new TeamId(legacyTeamId))
            .orElseThrow()
            .id()
            .value());
    assertEquals(
        legacyTeamId,
        teamRepository
            .lockUninitializedById(foundation.organizationId(), new TeamId(legacyTeamId))
            .orElseThrow()
            .id()
            .value());
  }

  @Test
  void serializesConcurrentDefaultPersonalAgentInitializationByTeamMember() throws Exception {
    Foundation foundation = createFoundation("personal-agent-concurrency");
    Principal memberUser = createUser(foundation.organizationId(), "Concurrent Member");
    TeamMember member =
        teamMemberRepository.create(
            foundation
                .initialization()
                .team()
                .joinMember(TeamMemberId.generate(), memberUser, TeamJoinMethod.OIDC, NOW));
    DefaultPersonalAgentService service =
        new DefaultPersonalAgentService(
            defaultPersonalAgentRepository, transactionExecutor, () -> LATER);
    CountDownLatch start = new CountDownLatch(1);

    var executor = Executors.newFixedThreadPool(2);
    try {
      var first =
          executor.submit(
              () -> {
                start.await();
                return service.ensureDefault(
                    member, foundation.initialization().defaultWorkspace(), memberUser);
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                return service.ensureDefault(
                    member, foundation.initialization().defaultWorkspace(), memberUser);
              });
      start.countDown();

      assertEquals(first.get().agentPrincipal().id(), second.get().agentPrincipal().id());
      assertEquals(
          1,
          jdbcTemplate.queryForObject(
              "SELECT COUNT(*) FROM crewscope.agent_profile WHERE owner_member_id =" + " ?",
              Integer.class,
              member.id().value()));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void roundTripsAllWorkFieldsAndImmutableCollaborationFactsWithOptimisticUpdates() {
    Foundation foundation = createFoundation("work-graph");
    WorkProject project = createProject(foundation);
    WorkItem created =
        workItemRepository.create(
            WorkItem.createExternalProjection(
                WorkItemId.generate(),
                project,
                new WorkItemKey("CRW-1"),
                WorkItemType.FEATURE,
                "Persistent planning",
                Optional.of("Full M1 description"),
                WorkItemPriority.HIGH,
                Set.of(new WorkItemLabel("Backend"), new WorkItemLabel("Collaboration")),
                Optional.of(LATER),
                WorkItemSource.JIRA,
                "JIRA-101",
                foundation.creator(),
                NOW));

    WorkItem loaded =
        workItemRepository.findById(foundation.organizationId(), created.id()).orElseThrow();
    assertEquals(WorkItemType.FEATURE, loaded.type());
    assertEquals(Optional.of("Full M1 description"), loaded.description());
    assertEquals(
        Set.of(new WorkItemLabel("backend"), new WorkItemLabel("collaboration")), loaded.labels());
    assertEquals(Optional.of(LATER), loaded.dueAt());
    assertEquals(WorkItemSource.JIRA, loaded.source());
    assertEquals(Optional.of("JIRA-101"), loaded.sourceReference());

    WorkItem revised =
        created.revise(
            WorkItemType.BUG,
            "Revised title",
            Optional.empty(),
            WorkItemPriority.URGENT,
            Set.of(new WorkItemLabel("urgent")),
            Optional.empty(),
            foundation.creator(),
            LATER);
    assertEquals(WorkItemType.BUG, workItemRepository.update(revised).type());
    assertThrows(OptimisticLockConflictException.class, () -> workItemRepository.update(revised));

    WorkItemComment comment =
        commentRepository.create(
            WorkItemComment.addNative(
                WorkItemCommentId.generate(),
                revised,
                foundation.creator(),
                "Decision recorded",
                LATER));
    WorkItemResourceLink link =
        resourceLinkRepository.create(
            WorkItemResourceLink.link(
                WorkItemResourceLinkId.generate(),
                revised,
                WorkItemResourceType.REPOSITORY,
                "github:crewscope/crewscope-java",
                Optional.of("Repository"),
                foundation.creator(),
                LATER));
    assertEquals(
        comment.id(),
        commentRepository.findByWorkItem(foundation.organizationId(), revised.id()).get(0).id());
    assertEquals(
        link.resourceReference(),
        resourceLinkRepository
            .findByWorkItem(foundation.organizationId(), revised.id())
            .get(0)
            .resourceReference());

    WorkProject renamed = project.rename("Renamed Project", foundation.creator(), LATER);
    assertEquals("Renamed Project", projectRepository.update(renamed).name());
    assertEquals(
        project.id(),
        projectRepository
            .findByKey(foundation.organizationId(), project.scope().teamId(), project.key())
            .orElseThrow()
            .id());

    WorkProject second =
        projectRepository.create(
            WorkProject.create(
                WorkProjectId.generate(),
                new WorkProjectKey("OPS"),
                "Operations",
                foundation.initialization().team(),
                foundation.initialization().defaultWorkspace(),
                foundation.creator(),
                NOW));
    WorkProjectPage firstPage =
        projectRepository.findPage(
            new WorkProjectQuery(
                foundation.organizationId(),
                project.scope().teamId(),
                Optional.empty(),
                1));
    assertEquals(1, firstPage.items().size());
    assertTrue(firstPage.nextCursor().isPresent());
    WorkProjectPage secondPage =
        projectRepository.findPage(
            new WorkProjectQuery(
                foundation.organizationId(),
                project.scope().teamId(),
                firstPage.nextCursor(),
                1));
    assertEquals(1, secondPage.items().size());
    assertFalse(secondPage.nextCursor().isPresent());
    assertEquals(
        Set.of(project.id(), second.id()),
        Set.of(firstPage.items().get(0).id(), secondPage.items().get(0).id()));
  }

  @Test
  void commitsCollaborationEventsOutboxReceiptsAndReadsOneDatabaseSnapshot() {
    Foundation foundation = createFoundation("work-item-collaboration");
    WorkProject project = createProject(foundation);
    WorkItem item =
        workItemRepository.create(
            WorkItem.createNative(
                WorkItemId.generate(),
                project,
                new WorkItemKey("CRW-42"),
                WorkItemType.TASK,
                "Persistent collaboration",
                Optional.empty(),
                WorkItemPriority.MEDIUM,
                Set.of(),
                Optional.empty(),
                foundation.creator(),
                NOW));
    WorkItemCollaborationService collaborationService = workItemCollaborationService();
    TeamCommandContext commentContext = commandContext(foundation, "work-comment-atomic-1");
    TeamCommandContext linkContext = commandContext(foundation, "work-link-atomic-1");

    var firstComment =
        collaborationService.addComment(
            commentContext,
            project.scope().teamId(),
            project.id(),
            item.id(),
            new AddWorkItemCommentCommand("Database-backed comment"));
    var replayedComment =
        collaborationService.addComment(
            commentContext,
            project.scope().teamId(),
            project.id(),
            item.id(),
            new AddWorkItemCommentCommand("Database-backed comment"));
    var firstLink =
        collaborationService.linkResource(
            linkContext,
            project.scope().teamId(),
            project.id(),
            item.id(),
            new LinkWorkItemResourceCommand(
                WorkItemResourceType.EXTERNAL_URL,
                "https://example.com/runs/42",
                Optional.of("Run 42")));
    var replayedLink =
        collaborationService.linkResource(
            linkContext,
            project.scope().teamId(),
            project.id(),
            item.id(),
            new LinkWorkItemResourceCommand(
                WorkItemResourceType.EXTERNAL_URL,
                "https://example.com/runs/42",
                Optional.of("Run 42")));
    WorkItemDetails details =
        workItemQueryService()
            .get(
                new TeamAccessContext(foundation.creator(), false),
                foundation.organizationId(),
                project.scope().teamId(),
                project.id(),
                item.id());

    assertFalse(firstComment.replayed());
    assertTrue(replayedComment.replayed());
    assertEquals(firstComment.receipt(), replayedComment.receipt());
    assertFalse(firstLink.replayed());
    assertTrue(replayedLink.replayed());
    assertEquals(firstLink.receipt(), replayedLink.receipt());
    assertEquals("Database-backed comment", details.comments().get(0).content());
    assertEquals("https://example.com/runs/42", details.resourceLinks().get(0).resourceReference());
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crewscope.work_item_comment WHERE work_item_id = ?",
            Integer.class,
            item.id().value()));
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crewscope.work_item_resource_link WHERE work_item_id = ?",
            Integer.class,
            item.id().value()));
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crewscope.domain_event WHERE event_type = 'WORK_ITEM_COMMENT_ADDED'",
            Integer.class));
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crewscope.domain_event WHERE event_type = 'WORK_ITEM_RESOURCE_LINKED'",
            Integer.class));
    assertEquals(2, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crewscope.outbox_event", Integer.class));
    assertEquals(2, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crewscope.command_receipt", Integer.class));
  }

  @Test
  void commitsAndQueriesTheResponsibilityChainWithIdempotentEventsAndRelease() {
    Foundation foundation = createFoundation("responsibility-api");
    WorkProject project = createProject(foundation);
    WorkItem item =
        workItemRepository.create(
            WorkItem.createNative(
                WorkItemId.generate(),
                project,
                new WorkItemKey("CRW-43"),
                WorkItemType.TASK,
                "Responsibility command chain",
                Optional.empty(),
                WorkItemPriority.HIGH,
                Set.of(),
                Optional.empty(),
                foundation.creator(),
                NOW));
    Principal executor = createUser(foundation.organizationId(), "Executor");
    TeamMember executorMember =
        teamMemberRepository.create(
            foundation
                .initialization()
                .team()
                .joinMember(TeamMemberId.generate(), executor, TeamJoinMethod.OIDC, NOW));
    Principal reviewer = createUser(foundation.organizationId(), "Reviewer");
    teamMemberRepository.create(
        foundation
            .initialization()
            .team()
            .joinMember(TeamMemberId.generate(), reviewer, TeamJoinMethod.OIDC, NOW));
    ResponsibilityCommandService service = responsibilityCommandService();
    TeamCommandContext ownerContext = commandContext(foundation, "responsibility-owner-1");

    var owner =
        service.replaceOwner(
            ownerContext,
            project.scope().teamId(),
            project.id(),
            item.id(),
            new ReplaceOwnerCommand(
                foundation.creator().id(), ActiveOwnerExpectation.none()));
    var ownerReplay =
        service.replaceOwner(
            ownerContext,
            project.scope().teamId(),
            project.id(),
            item.id(),
            new ReplaceOwnerCommand(
                foundation.creator().id(), ActiveOwnerExpectation.none()));
    ResponsibilityAssignment executorAssignment =
        service
            .assignExecutor(
                commandContext(foundation, "responsibility-executor-1"),
                project.scope().teamId(),
                project.id(),
                item.id(),
                new AssignResponsibilityCommand(executor.id()))
            .result()
            .orElseThrow();
    service.assignGateReviewer(
        commandContext(foundation, "responsibility-reviewer-1"),
        project.scope().teamId(),
        project.id(),
        item.id(),
        new AssignResponsibilityCommand(reviewer.id()));
    service.release(
        commandContext(foundation, "responsibility-release-1"),
        project.scope().teamId(),
        project.id(),
        item.id(),
        executorAssignment.id(),
        new ReleaseResponsibilityCommand(executorAssignment.version()));
    var active =
        responsibilityQueryService()
            .listActive(
                new TeamAccessContext(foundation.creator(), false),
                foundation.organizationId(),
                project.scope().teamId(),
                project.id(),
                item.id());

    assertTrue(ownerReplay.replayed());
    assertEquals(owner.receipt(), ownerReplay.receipt());
    assertEquals(Set.of("Owner responsibility-api", "Reviewer"), active.stream().map(value -> value.actorDisplayName()).collect(java.util.stream.Collectors.toSet()));
    assertEquals(Set.of(ResponsibilityRole.OWNER, ResponsibilityRole.REVIEWER), active.stream().map(value -> value.assignment().role()).collect(java.util.stream.Collectors.toSet()));
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crewscope.domain_event WHERE event_type = 'WORK_ITEM_OWNER_ASSIGNED'",
            Integer.class));
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crewscope.domain_event WHERE event_type = 'WORK_ITEM_EXECUTOR_ASSIGNED'",
            Integer.class));
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crewscope.domain_event WHERE event_type = 'WORK_ITEM_GATE_REVIEWER_ASSIGNED'",
            Integer.class));
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crewscope.domain_event WHERE event_type = 'WORK_ITEM_RESPONSIBILITY_RELEASED'",
            Integer.class));
    assertEquals(4, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crewscope.outbox_event", Integer.class));
    assertEquals(4, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crewscope.command_receipt", Integer.class));
    assertEquals(
        3,
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crewscope.responsibility_assignment WHERE work_item_id = ?",
            Integer.class,
            item.id().value()));
    assertEquals(executorMember.id(), executorAssignment.actorMemberId().orElseThrow());
  }

  @Test
  void readsADeduplicatedWorkItemTimelineAcrossPagesFromDomainAndAuditEvents() {
    Foundation foundation = createFoundation("work-item-timeline");
    WorkProject project = createProject(foundation);
    WorkItem item =
        workItemRepository.create(
            WorkItem.createNative(
                WorkItemId.generate(),
                project,
                new WorkItemKey("CRW-45"),
                WorkItemType.TASK,
                "Timeline query",
                Optional.empty(),
                WorkItemPriority.MEDIUM,
                Set.of(),
                Optional.empty(),
                foundation.creator(),
                NOW));
    Principal executor = createUser(foundation.organizationId(), "Timeline executor");
    teamMemberRepository.create(
        foundation
            .initialization()
            .team()
            .joinMember(TeamMemberId.generate(), executor, TeamJoinMethod.OIDC, NOW));
    var comment =
        workItemCollaborationService()
            .addComment(
                commandContext(foundation, "timeline-comment-1"),
                project.scope().teamId(),
                project.id(),
                item.id(),
                new AddWorkItemCommentCommand("Timeline comment"));
    workItemCollaborationService()
        .linkResource(
            commandContext(foundation, "timeline-link-1"),
            project.scope().teamId(),
            project.id(),
            item.id(),
            new LinkWorkItemResourceCommand(
                WorkItemResourceType.EXTERNAL_URL,
                "https://example.com/timeline",
                Optional.of("Timeline evidence")));
    ResponsibilityCommandService responsibilityService = responsibilityCommandService();
    responsibilityService.replaceOwner(
        commandContext(foundation, "timeline-owner-1"),
        project.scope().teamId(),
        project.id(),
        item.id(),
        new ReplaceOwnerCommand(foundation.creator().id(), ActiveOwnerExpectation.none()));
    responsibilityService.assignExecutor(
        commandContext(foundation, "timeline-executor-1"),
        project.scope().teamId(),
        project.id(),
        item.id(),
        new AssignResponsibilityCommand(executor.id()));

    // Simulate the normal Audit projection copy of the comment DomainEvent. The unified query must
    // still expose the underlying business fact exactly once.
    jdbcTemplate.update(
        """
        INSERT INTO crewscope.audit_event (
            event_id, organization_id, team_id, workspace_id,
            principal_id, initiator_id, actor_type, actor_id, agent_principal_id,
            credential_subject_type, credential_subject_id,
            event_type, subject_type, subject_id, outcome,
            authorization_context, domain_event_id,
            correlation_id, causation_id, trace_id,
            schema_version, occurred_at, payload
        )
        SELECT ?, organization_id, team_id, workspace_id,
               actor_id, actor_id, actor_type, actor_id, NULL,
               NULL, NULL,
               event_type, subject_type, subject_id, 'SUCCEEDED',
               CAST('{}' AS JSONB), event_id,
               correlation_id, causation_id, NULL,
               schema_version, occurred_at, payload
          FROM crewscope.domain_event
         WHERE event_id = ?
        """,
        UUID.randomUUID(),
        comment.receipt().domainEventId());

    WorkItemTimelineService timelineService = workItemTimelineService();
    WorkItemTimelinePage first =
        timelineService.list(
            new TeamAccessContext(foundation.creator(), false),
            foundation.organizationId(),
            project.scope().teamId(),
            project.id(),
            item.id(),
            Optional.empty(),
            2);
    WorkItemTimelinePage second =
        timelineService.list(
            new TeamAccessContext(foundation.creator(), false),
            foundation.organizationId(),
            project.scope().teamId(),
            project.id(),
            item.id(),
            first.nextCursor(),
            2);
    List<WorkItemTimelineEvent> events =
        java.util.stream.Stream.concat(first.items().stream(), second.items().stream()).toList();

    assertTrue(first.nextCursor().isPresent());
    assertTrue(second.nextCursor().isEmpty());
    assertEquals(4, events.size());
    assertEquals(4, events.stream().map(WorkItemTimelineEvent::canonicalEventId).distinct().count());
    assertEquals(
        Set.of(
            "WORK_ITEM_COMMENT_ADDED",
            "WORK_ITEM_RESOURCE_LINKED",
            "WORK_ITEM_OWNER_ASSIGNED",
            "WORK_ITEM_EXECUTOR_ASSIGNED"),
        events.stream().map(WorkItemTimelineEvent::eventType).collect(java.util.stream.Collectors.toSet()));
    assertEquals(
        1,
        events.stream()
            .filter(event -> event.domainEventId().equals(Optional.of(comment.receipt().domainEventId())))
            .count());
    assertTrue(
        events.stream()
            .allMatch(
                event ->
                    event.actorDisplayName().isPresent()
                        && event.source()
                            == io.crewscope.application.workitem.WorkItemTimelineSource.DOMAIN_EVENT));
    for (int index = 1; index < events.size(); index++) {
      WorkItemTimelineEvent previous = events.get(index - 1);
      WorkItemTimelineEvent current = events.get(index);
      int timeOrder = previous.occurredAt().compareTo(current.occurredAt());
      boolean eventIdOrder =
          jdbcTemplate.queryForObject(
              "SELECT CAST(? AS UUID) > CAST(? AS UUID)",
              Boolean.class,
              previous.canonicalEventId(),
              current.canonicalEventId());
      assertTrue(
          timeOrder > 0 || (timeOrder == 0 && eventIdOrder),
          "Timeline order must match PostgreSQL timestamp/UUID keyset ordering");
    }
  }

  @Test
  void serializesConcurrentOwnerReplacementsFromTheSameExpectedAssignment() throws Exception {
    Foundation foundation = createFoundation("owner-replacement-concurrency");
    WorkProject project = createProject(foundation);
    WorkItem item =
        workItemRepository.create(
            WorkItem.createNative(
                WorkItemId.generate(),
                project,
                new WorkItemKey("CRW-44"),
                WorkItemType.TASK,
                "Concurrent owner replacement",
                Optional.empty(),
                WorkItemPriority.HIGH,
                Set.of(),
                Optional.empty(),
                foundation.creator(),
                NOW));
    ResponsibilityCommandService service = responsibilityCommandService();
    ResponsibilityAssignment initial =
        service
            .replaceOwner(
                commandContext(foundation, "owner-concurrency-initial"),
                project.scope().teamId(),
                project.id(),
                item.id(),
                new ReplaceOwnerCommand(
                    foundation.creator().id(), ActiveOwnerExpectation.none()))
            .result()
            .orElseThrow()
            .active();
    Principal firstTarget = createUser(foundation.organizationId(), "First Owner Target");
    Principal secondTarget = createUser(foundation.organizationId(), "Second Owner Target");
    teamMemberRepository.create(
        foundation
            .initialization()
            .team()
            .joinMember(TeamMemberId.generate(), firstTarget, TeamJoinMethod.OIDC, NOW));
    teamMemberRepository.create(
        foundation
            .initialization()
            .team()
            .joinMember(TeamMemberId.generate(), secondTarget, TeamJoinMethod.OIDC, NOW));
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger completed = new AtomicInteger();
    AtomicInteger rejected = new AtomicInteger();
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first =
          executor.submit(
              () ->
                  replaceOwner(
                      service,
                      foundation,
                      project,
                      item,
                      initial,
                      firstTarget,
                      "owner-concurrency-first",
                      start,
                      completed,
                      rejected));
      var second =
          executor.submit(
              () ->
                  replaceOwner(
                      service,
                      foundation,
                      project,
                      item,
                      initial,
                      secondTarget,
                      "owner-concurrency-second",
                      start,
                      completed,
                      rejected));
      start.countDown();
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }

    assertEquals(1, completed.get());
    assertEquals(1, rejected.get());
    assertEquals(
        1,
        assignmentRepository
            .findActiveByWorkItem(foundation.organizationId(), item.id())
            .stream()
            .filter(value -> value.role() == ResponsibilityRole.OWNER)
            .count());
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crewscope.domain_event WHERE event_type = 'WORK_ITEM_OWNER_REPLACED'",
            Integer.class));
    assertEquals(2, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crewscope.command_receipt", Integer.class));
  }

  @Test
  void serializesConcurrentWorkProjectKeysToOneCommittedProjectAndEvent() throws Exception {
    Foundation foundation = createFoundation("project-key-concurrency");
    WorkProjectApplicationService service = workProjectApplicationService();
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger completed = new AtomicInteger();
    AtomicInteger rejected = new AtomicInteger();
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first =
          executor.submit(
              () ->
                  createWorkProject(
                      service,
                      foundation,
                      "project-key-concurrent-1",
                      start,
                      completed,
                      rejected));
      var second =
          executor.submit(
              () ->
                  createWorkProject(
                      service,
                      foundation,
                      "project-key-concurrent-2",
                      start,
                      completed,
                      rejected));
      start.countDown();
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }

    assertEquals(1, completed.get());
    assertEquals(1, rejected.get());
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crewscope.work_project WHERE team_id = ? AND project_key = 'OPS'",
            Integer.class,
            foundation.initialization().team().id().value()));
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crewscope.domain_event WHERE event_type = 'WORK_PROJECT_CREATED'",
            Integer.class));
    assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crewscope.outbox_event", Integer.class));
  }

  @Test
  void serializesConcurrentWorkItemKeysToOneCommittedItemEventAndReceipt() throws Exception {
    Foundation foundation = createFoundation("work-item-key-concurrency");
    WorkProject project = createProject(foundation);
    WorkItemCommandService service = workItemCommandService();
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger completed = new AtomicInteger();
    AtomicInteger rejected = new AtomicInteger();
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first =
          executor.submit(
              () ->
                  createWorkItem(
                      service,
                      foundation,
                      project,
                      "work-item-key-concurrent-1",
                      start,
                      completed,
                      rejected));
      var second =
          executor.submit(
              () ->
                  createWorkItem(
                      service,
                      foundation,
                      project,
                      "work-item-key-concurrent-2",
                      start,
                      completed,
                      rejected));
      start.countDown();
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }

    assertEquals(1, completed.get());
    assertEquals(1, rejected.get());
    WorkItem committed =
        workItemRepository
            .findByKey(
                foundation.organizationId(), project.id(), new WorkItemKey("CRW-101"))
            .orElseThrow();
    assertEquals(project.id(), committed.scope().projectId());
    assertEquals(
        foundation.creator().id(),
        assignmentRepository
            .findActiveOwner(foundation.organizationId(), committed.id())
            .orElseThrow()
            .actorPrincipalId());
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crewscope.work_item WHERE project_id = ? AND item_key = 'CRW-101'",
            Integer.class,
            project.id().value()));
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crewscope.responsibility_assignment WHERE work_item_id = ? AND role = 'OWNER' AND status = 'ACTIVE'",
            Integer.class,
            committed.id().value()));
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crewscope.domain_event WHERE event_type = 'WORK_ITEM_CREATED'",
            Integer.class));
    assertEquals(
        1,
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crewscope.outbox_event", Integer.class));
    assertEquals(
        1,
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crewscope.command_receipt", Integer.class));
  }

  @Test
  void acceptsOneOfTwoConcurrentTransitionsFromTheSameCommittedVersion() throws Exception {
    Foundation foundation = createFoundation("work-item-transition-concurrency");
    WorkProject project = createProject(foundation);
    WorkItem item =
        workItemRepository.create(
            WorkItem.createNative(
                WorkItemId.generate(),
                project,
                new WorkItemKey("CRW-102"),
                WorkItemType.TASK,
                "Concurrent transition",
                Optional.empty(),
                WorkItemPriority.MEDIUM,
                Set.of(),
                Optional.empty(),
                foundation.creator(),
                NOW));
    WorkItemCommandService service = workItemCommandService();
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger completed = new AtomicInteger();
    AtomicInteger rejected = new AtomicInteger();
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first =
          executor.submit(
              () ->
                  transitionWorkItem(
                      service,
                      foundation,
                      project,
                      item,
                      "work-item-transition-concurrent-1",
                      start,
                      completed,
                      rejected));
      var second =
          executor.submit(
              () ->
                  transitionWorkItem(
                      service,
                      foundation,
                      project,
                      item,
                      "work-item-transition-concurrent-2",
                      start,
                      completed,
                      rejected));
      start.countDown();
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }

    WorkItem committed =
        workItemRepository.findById(foundation.organizationId(), item.id()).orElseThrow();
    assertEquals(1, completed.get());
    assertEquals(1, rejected.get());
    assertEquals(WorkItemStatus.READY, committed.status());
    assertEquals(1, committed.version());
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crewscope.domain_event WHERE event_type = 'WORK_ITEM_STATUS_CHANGED'",
            Integer.class));
    assertEquals(
        1,
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crewscope.outbox_event", Integer.class));
    assertEquals(
        1,
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crewscope.command_receipt", Integer.class));
  }

  @Test
  void locksAndPersistsResponsibilityHistoryAndMapsActiveSlotConflicts() {
    Foundation foundation = createFoundation("responsibility");
    WorkProject project = createProject(foundation);
    WorkItem workItem =
        workItemRepository.create(
            WorkItem.createNative(
                WorkItemId.generate(),
                project,
                new WorkItemKey("CRW-2"),
                WorkItemType.TASK,
                "Responsibility",
                Optional.empty(),
                WorkItemPriority.MEDIUM,
                Set.of(),
                Optional.empty(),
                foundation.creator(),
                NOW));
    assignmentRepository.lockResponsibilityChain(foundation.organizationId(), workItem.id());

    ResponsibilityAssignment owner =
        ResponsibilityAssignment.assign(
            ResponsibilityAssignmentId.generate(),
            workItem,
            ResponsibilityRole.OWNER,
            foundation.creator(),
            Optional.of(foundation.initialization().ownerMember()),
            foundation.creator(),
            NOW);
    assignmentRepository.create(owner);
    ResponsibilityAssignment duplicate =
        ResponsibilityAssignment.assign(
            ResponsibilityAssignmentId.generate(),
            workItem,
            ResponsibilityRole.OWNER,
            foundation.creator(),
            Optional.of(foundation.initialization().ownerMember()),
            foundation.creator(),
            LATER);

    assertThrows(
        ResponsibilityConflictException.class, () -> assignmentRepository.create(duplicate));
    assertEquals(
        owner.id(),
        assignmentRepository
            .findActiveOwner(foundation.organizationId(), workItem.id())
            .orElseThrow()
            .id());

    ResponsibilityAssignment released =
        assignmentRepository.update(owner.release(foundation.creator(), LATER));
    assertEquals(1, released.version());
    assertThrows(
        OptimisticLockConflictException.class,
        () -> assignmentRepository.update(owner.release(foundation.creator(), LATER)));
    assertTrue(
        assignmentRepository.findActiveOwner(foundation.organizationId(), workItem.id()).isEmpty());
    assertThrows(
        AggregateNotFoundException.class,
        () ->
            assignmentRepository.lockResponsibilityChain(
                new OrganizationId(UUID.randomUUID()), workItem.id()));
  }

  @Test
  void holdsTheResponsibilityChainLockUntilTheOuterTransactionCompletes() throws Exception {
    Foundation foundation = createFoundation("responsibility-lock");
    WorkProject project = createProject(foundation);
    WorkItem workItem =
        workItemRepository.create(
            WorkItem.createNative(
                WorkItemId.generate(),
                project,
                new WorkItemKey("CRW-3"),
                WorkItemType.TASK,
                "Lock boundary",
                Optional.empty(),
                WorkItemPriority.MEDIUM,
                Set.of(),
                Optional.empty(),
                foundation.creator(),
                NOW));
    CountDownLatch firstLocked = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch secondAttempted = new CountDownLatch(1);
    CountDownLatch secondAcquired = new CountDownLatch(1);

    var executor = Executors.newFixedThreadPool(2);
    try {
      var first =
          executor.submit(
              () ->
                  transactionExecutor.required(
                      () -> {
                        assignmentRepository.lockResponsibilityChain(
                            foundation.organizationId(), workItem.id());
                        firstLocked.countDown();
                        await(releaseFirst);
                        return null;
                      }));
      assertTrue(firstLocked.await(5, TimeUnit.SECONDS));
      var second =
          executor.submit(
              () ->
                  transactionExecutor.required(
                      () -> {
                        secondAttempted.countDown();
                        assignmentRepository.lockResponsibilityChain(
                            foundation.organizationId(), workItem.id());
                        secondAcquired.countDown();
                        return null;
                      }));
      assertTrue(secondAttempted.await(5, TimeUnit.SECONDS));
      assertFalse(secondAcquired.await(250, TimeUnit.MILLISECONDS));

      releaseFirst.countDown();
      first.get();
      second.get();
      assertEquals(0, secondAcquired.getCount());
    } finally {
      releaseFirst.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void hidesEveryM1LookupBehindTheExplicitOrganizationBoundary() {
    Foundation foundation = createFoundation("tenant-isolation");
    WorkProject project = createProject(foundation);
    OrganizationId other = new OrganizationId(UUID.randomUUID());

    assertTrue(teamRepository.findById(other, foundation.initialization().team().id()).isEmpty());
    assertTrue(
        workspaceRepository
            .findById(other, foundation.initialization().defaultWorkspace().id())
            .isEmpty());
    assertTrue(
        teamMemberRepository
            .findById(other, foundation.initialization().ownerMember().id())
            .isEmpty());
    assertTrue(projectRepository.findById(other, project.id()).isEmpty());
  }

  private Foundation createFoundation(String suffix) {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    jdbcTemplate.update(
        "INSERT INTO crewscope.organization (id, name, status) VALUES (?, ?, 'ACTIVE')",
        organizationId.value(),
        "Organization " + suffix);
    Principal creator = createUser(organizationId, "Owner " + suffix);
    TeamCreationService service =
        new TeamCreationService(
            teamRepository,
            workspaceRepository,
            teamMemberRepository,
            teamRoleRepository,
            memberRoleRepository,
            defaultPersonalAgentRepository,
            transactionExecutor,
            () -> NOW);
    return new Foundation(
        organizationId, creator, service.create(creator, new CreateTeamCommand("Team " + suffix)));
  }

  private TeamApplicationService teamApplicationService() {
    TeamCreationService creationService =
        new TeamCreationService(
            teamRepository,
            workspaceRepository,
            teamMemberRepository,
            teamRoleRepository,
            memberRoleRepository,
            defaultPersonalAgentRepository,
            transactionExecutor,
            () -> NOW);
    return new TeamApplicationService(
        creationService,
        teamRepository,
        workspaceRepository,
        teamMemberRepository,
        teamMembershipQuery,
        teamRoleRepository,
        memberRoleRepository,
        principalRepository,
        defaultPersonalAgentRepository,
        domainEventStore,
        outboxRepository,
        commandReceiptStore,
        transactionExecutor,
        () -> NOW);
  }

  private WorkProjectApplicationService workProjectApplicationService() {
    return new WorkProjectApplicationService(
        projectRepository,
        teamRepository,
        workspaceRepository,
        teamMembershipQuery,
        teamRoleRepository,
        memberRoleRepository,
        domainEventStore,
        outboxRepository,
        commandReceiptStore,
        transactionExecutor,
        () -> NOW);
  }

  private WorkItemCommandService workItemCommandService() {
    return new WorkItemCommandService(
        workItemRepository,
        projectRepository,
        teamRepository,
        teamMembershipQuery,
        teamRoleRepository,
        memberRoleRepository,
        assignmentRepository,
        domainEventStore,
        outboxRepository,
        commandReceiptStore,
        transactionExecutor,
        () -> NOW);
  }

  private WorkItemAccessPolicy workItemAccessPolicy() {
    return new WorkItemAccessPolicy(
        workItemRepository,
        projectRepository,
        teamRepository,
        teamMembershipQuery,
        teamRoleRepository,
        memberRoleRepository);
  }

  private WorkItemCollaborationService workItemCollaborationService() {
    return new WorkItemCollaborationService(
        commentRepository,
        resourceLinkRepository,
        workItemAccessPolicy(),
        domainEventStore,
        outboxRepository,
        commandReceiptStore,
        transactionExecutor,
        () -> NOW);
  }

  private WorkItemQueryService workItemQueryService() {
    return new WorkItemQueryService(
        workItemRepository,
        commentRepository,
        resourceLinkRepository,
        workItemAccessPolicy(),
        transactionExecutor);
  }

  private WorkItemTimelineService workItemTimelineService() {
    return new WorkItemTimelineService(
        workItemTimelineRepository, workItemAccessPolicy(), transactionExecutor);
  }

  private ResponsibilityCommandService responsibilityCommandService() {
    ResponsibilityAssignmentService assignmentService =
        new ResponsibilityAssignmentService(
            assignmentRepository, transactionExecutor, () -> NOW);
    GateReviewerAssignmentService reviewerService =
        new GateReviewerAssignmentService(
            assignmentRepository, teamMembershipQuery, transactionExecutor, () -> NOW);
    return new ResponsibilityCommandService(
        assignmentRepository,
        assignmentService,
        reviewerService,
        item -> ReviewerEligibilityPolicy.strict(),
        workItemAccessPolicy(),
        principalRepository,
        teamMembershipQuery,
        domainEventStore,
        outboxRepository,
        commandReceiptStore,
        transactionExecutor,
        () -> NOW);
  }

  private ResponsibilityQueryService responsibilityQueryService() {
    return new ResponsibilityQueryService(
        assignmentRepository,
        principalRepository,
        workItemAccessPolicy(),
        transactionExecutor);
  }

  private static void completeLegacy(
      TeamApplicationService service,
      Principal administrator,
      Principal selectedOwner,
      TeamId teamId,
      String idempotencyKey,
      CountDownLatch start,
      AtomicInteger completed,
      AtomicInteger rejected) {
    await(start);
    try {
      service.completeInitialization(
          new TeamCommandContext(
              new TeamAccessContext(administrator, true),
              IdempotencyKey.from(idempotencyKey),
              UUID.randomUUID(),
              Optional.empty()),
          teamId,
          new io.crewscope.application.team.CompleteTeamInitializationCommand(selectedOwner.id()));
      completed.incrementAndGet();
    } catch (DomainValidationException expected) {
      rejected.incrementAndGet();
    }
  }

  private static void addMember(
      TeamApplicationService service,
      Foundation foundation,
      Principal target,
      String idempotencyKey,
      CountDownLatch start,
      AtomicInteger completed,
      AtomicInteger rejected) {
    await(start);
    try {
      service.addMember(
          new TeamCommandContext(
              new TeamAccessContext(foundation.creator(), false),
              IdempotencyKey.from(idempotencyKey),
              UUID.randomUUID(),
              Optional.empty()),
          foundation.initialization().team().id(),
          new AddTeamMemberCommand(target.id()));
      completed.incrementAndGet();
    } catch (DomainValidationException expected) {
      rejected.incrementAndGet();
    }
  }

  private static void createWorkProject(
      WorkProjectApplicationService service,
      Foundation foundation,
      String idempotencyKey,
      CountDownLatch start,
      AtomicInteger completed,
      AtomicInteger rejected) {
    await(start);
    try {
      service.create(
          new TeamCommandContext(
              new TeamAccessContext(foundation.creator(), false),
              IdempotencyKey.from(idempotencyKey),
              UUID.randomUUID(),
              Optional.empty()),
          foundation.initialization().team().id(),
          new CreateWorkProjectCommand("OPS", "Operations"));
      completed.incrementAndGet();
    } catch (WorkProjectKeyConflictException expected) {
      rejected.incrementAndGet();
    }
  }

  private static void createWorkItem(
      WorkItemCommandService service,
      Foundation foundation,
      WorkProject project,
      String idempotencyKey,
      CountDownLatch start,
      AtomicInteger completed,
      AtomicInteger rejected) {
    await(start);
    try {
      service.create(
          commandContext(foundation, idempotencyKey),
          project.scope().teamId(),
          project.id(),
          new CreateNativeWorkItemCommand(
              "CRW-101",
              WorkItemType.TASK,
              "Concurrent creation",
              Optional.empty(),
              WorkItemPriority.MEDIUM,
              Set.of(),
              Optional.empty()));
      completed.incrementAndGet();
    } catch (WorkItemKeyConflictException expected) {
      rejected.incrementAndGet();
    }
  }

  private static void transitionWorkItem(
      WorkItemCommandService service,
      Foundation foundation,
      WorkProject project,
      WorkItem item,
      String idempotencyKey,
      CountDownLatch start,
      AtomicInteger completed,
      AtomicInteger rejected) {
    await(start);
    try {
      service.transition(
          commandContext(foundation, idempotencyKey),
          project.scope().teamId(),
          project.id(),
          item.id(),
          new TransitionWorkItemCommand(WorkItemStatus.READY, 0));
      completed.incrementAndGet();
    } catch (OptimisticLockConflictException expected) {
      rejected.incrementAndGet();
    }
  }

  private static void replaceOwner(
      ResponsibilityCommandService service,
      Foundation foundation,
      WorkProject project,
      WorkItem item,
      ResponsibilityAssignment expectedOwner,
      Principal target,
      String idempotencyKey,
      CountDownLatch start,
      AtomicInteger completed,
      AtomicInteger rejected) {
    await(start);
    try {
      service.replaceOwner(
          commandContext(foundation, idempotencyKey),
          project.scope().teamId(),
          project.id(),
          item.id(),
          new ReplaceOwnerCommand(
              target.id(), ActiveOwnerExpectation.current(expectedOwner)));
      completed.incrementAndGet();
    } catch (io.crewscope.domain.responsibility.ResponsibilityVersionConflictException expected) {
      rejected.incrementAndGet();
    }
  }

  private static TeamCommandContext commandContext(
      Foundation foundation, String idempotencyKey) {
    return new TeamCommandContext(
        new TeamAccessContext(foundation.creator(), false),
        IdempotencyKey.from(idempotencyKey),
        UUID.randomUUID(),
        Optional.empty());
  }

  private static IdentityMappingResult mapIdentity(
      IdentityMappingService service, OrganizationId organizationId, CountDownLatch start) {
    await(start);
    return service.map(
        new IdentityMappingRequest(
            organizationId,
            new ExternalIdentity("oidc/company", "private-concurrent-subject"),
            "Concurrent User",
            UUID.randomUUID()));
  }

  private Principal createUser(OrganizationId organizationId, String displayName) {
    Principal user =
        Principal.create(
            new PrincipalId(UUID.randomUUID()),
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            displayName,
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);
    jdbcTemplate.update(
        """
        INSERT INTO crewscope.principal (
            id, organization_id, principal_type, display_name, visibility, status,
            version, created_at, updated_at
        ) VALUES (?, ?, 'USER', ?, 'ORGANIZATION', 'ACTIVE', 0, ?, ?)
        """,
        user.id().value(),
        organizationId.value(),
        user.displayName(),
        Timestamp.from(NOW.value()),
        Timestamp.from(NOW.value()));
    return user;
  }

  private WorkProject createProject(Foundation foundation) {
    return projectRepository.create(
        WorkProject.create(
            WorkProjectId.generate(),
            new WorkProjectKey("CRW"),
            "CrewScope",
            foundation.initialization().team(),
            foundation.initialization().defaultWorkspace(),
            foundation.creator(),
            NOW));
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for test coordination");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted during test coordination", interrupted);
    }
  }

  private record Foundation(
      OrganizationId organizationId, Principal creator, TeamInitialization initialization) {}

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EntityScan(basePackageClasses = JpaPersistenceConfiguration.class)
  @Import({
    TeamPersistenceMapper.class,
    JpaTeamRepositoryAdapter.class,
    JpaWorkspaceRepositoryAdapter.class,
    JpaTeamMemberRepositoryAdapter.class,
    JpaTeamRoleRepositoryAdapter.class,
    JpaMemberRoleRepositoryAdapter.class,
    JpaPrincipalRepositoryAdapter.class,
    JpaAgentProfileRepositoryAdapter.class,
    JdbcDomainEventStore.class,
    JdbcOutboxRepository.class,
    JdbcCommandReceiptStore.class,
    WorkPersistenceMapper.class,
    WorkItemEntityMapper.class,
    JpaWorkProjectRepositoryAdapter.class,
    JpaWorkItemRepositoryAdapter.class,
    JpaWorkItemCommentRepositoryAdapter.class,
    JpaWorkItemResourceLinkRepositoryAdapter.class,
    JdbcWorkItemTimelineRepository.class,
    ResponsibilityPersistenceMapper.class,
    JpaResponsibilityAssignmentRepositoryAdapter.class,
    SpringTransactionExecutor.class
  })
  static class TestApplication {}
}
