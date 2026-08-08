package io.crewscope.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.CreateTeamCommand;
import io.crewscope.application.team.DefaultPersonalAgentRepository;
import io.crewscope.application.team.DefaultPersonalAgentService;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamCreationService;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemCommentRepository;
import io.crewscope.application.workitem.WorkItemRepository;
import io.crewscope.application.workitem.WorkItemResourceLinkRepository;
import io.crewscope.application.workitem.WorkProjectRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityConflictException;
import io.crewscope.domain.responsibility.ResponsibilityRole;
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
import io.crewscope.domain.workitem.WorkItemLabel;
import io.crewscope.domain.workitem.WorkItemPriority;
import io.crewscope.domain.workitem.WorkItemResourceLink;
import io.crewscope.domain.workitem.WorkItemResourceLinkId;
import io.crewscope.domain.workitem.WorkItemResourceType;
import io.crewscope.domain.workitem.WorkItemSource;
import io.crewscope.domain.workitem.WorkItemType;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.WorkProjectKey;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileStatus;
import io.crewscope.domain.workspace.PersonalAgentInitialization;
import io.crewscope.domain.workspace.Workspace;
import io.crewscope.domain.workspace.WorkspaceScope;
import io.crewscope.domain.workspace.WorkspaceStatus;
import io.crewscope.infrastructure.persistence.responsibility.JpaResponsibilityAssignmentRepositoryAdapter;
import io.crewscope.infrastructure.persistence.responsibility.ResponsibilityPersistenceMapper;
import io.crewscope.infrastructure.persistence.team.JpaAgentProfileRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.JpaMemberRoleRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.JpaTeamMemberRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.JpaTeamRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.JpaTeamRoleRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.JpaWorkspaceRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.TeamInitializationRequiredException;
import io.crewscope.infrastructure.persistence.team.TeamPersistenceMapper;
import io.crewscope.infrastructure.persistence.workitem.JpaWorkItemCommentRepositoryAdapter;
import io.crewscope.infrastructure.persistence.workitem.JpaWorkItemRepositoryAdapter;
import io.crewscope.infrastructure.persistence.workitem.JpaWorkItemResourceLinkRepositoryAdapter;
import io.crewscope.infrastructure.persistence.workitem.JpaWorkProjectRepositoryAdapter;
import io.crewscope.infrastructure.persistence.workitem.WorkItemEntityMapper;
import io.crewscope.infrastructure.persistence.workitem.WorkPersistenceMapper;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import io.crewscope.infrastructure.transaction.SpringTransactionExecutor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    @Autowired private TeamRoleRepository teamRoleRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private DefaultPersonalAgentRepository defaultPersonalAgentRepository;
    @Autowired private AgentProfileRepository agentProfileRepository;
    @Autowired private WorkProjectRepository projectRepository;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private WorkItemCommentRepository commentRepository;
    @Autowired private WorkItemResourceLinkRepository resourceLinkRepository;
    @Autowired private ResponsibilityAssignmentRepository assignmentRepository;
    @Autowired private TransactionExecutor transactionExecutor;
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
                teamRepository
                        .findById(foundation.organizationId(), value.team().id())
                        .orElseThrow()
                        .id());
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
                5,
                teamRoleRepository
                        .findByTeam(foundation.organizationId(), value.team().id())
                        .size());
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

        assertEquals(
                value.ownerPersonalAgent().agentPrincipal().id(), retried.agentPrincipal().id());
        assertEquals(value.ownerPersonalAgent().agentProfile().id(), retried.agentProfile().id());
        assertTrue(
                agentProfileRepository
                        .findActiveDefaultPersonal(
                                foundation.organizationId(), value.ownerMember().id())
                        .isPresent());
        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.agent_profile", Integer.class));

        assertEquals(
                1,
                teamRepository
                        .update(value.team().archive(foundation.creator().id(), LATER))
                        .version());
        assertEquals(
                1,
                workspaceRepository
                        .update(value.defaultWorkspace().archive(foundation.creator().id(), LATER))
                        .version());
        assertEquals(
                1,
                teamMemberRepository.update(value.ownerMember().recordActivity(LATER)).version());
        assertEquals(
                TeamRoleStatus.DISABLED,
                teamRoleRepository
                        .update(
                                value.builtInRoles()
                                        .get(0)
                                        .transitionTo(TeamRoleStatus.DISABLED, LATER))
                        .status());
        assertEquals(1, memberRoleRepository.update(value.ownerRole().revoke(LATER)).version());
        assertEquals(
                1,
                agentProfileRepository
                        .update(
                                value.ownerPersonalAgent()
                                        .agentProfile()
                                        .disable(foundation.creator().id(), LATER))
                        .version());
    }

    @Test
    void rejectsWorkspaceAndAgentProfileUpdatesFromMismatchedImmutableScopes() {
        Foundation foundation = createFoundation("update-scope");
        Workspace workspace = foundation.initialization().defaultWorkspace();
        Workspace wrongTeamWorkspace =
                Workspace.reconstitute(
                        workspace.id(),
                        WorkspaceScope.team(
                                foundation.organizationId(), new TeamId(UUID.randomUUID())),
                        workspace.type(),
                        workspace.ownerPrincipalId(),
                        workspace.name(),
                        WorkspaceStatus.ARCHIVED,
                        workspace.version() + 1,
                        workspace.audit().modifiedBy(foundation.creator().id(), LATER));

        assertThrows(
                AggregateNotFoundException.class,
                () -> workspaceRepository.update(wrongTeamWorkspace));
        Workspace unchangedWorkspace =
                workspaceRepository
                        .findById(foundation.organizationId(), workspace.id())
                        .orElseThrow();
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
                agentProfileRepository
                        .findById(foundation.organizationId(), profile.id())
                        .orElseThrow();
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
                                .joinMember(
                                        TeamMemberId.generate(),
                                        memberUser,
                                        TeamJoinMethod.OIDC,
                                        NOW));
        PersonalAgentInitialization candidate =
                PersonalAgentInitialization.createDefault(
                        activeSnapshot,
                        foundation.initialization().defaultWorkspace(),
                        memberUser,
                        LATER);
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
                        () ->
                                teamRepository.findById(
                                        foundation.organizationId(), new TeamId(legacyTeamId)));

        assertEquals(legacyTeamId, failure.teamId());
        assertTrue(failure.getMessage().contains("Owner and default Workspace"));
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
                                .joinMember(
                                        TeamMemberId.generate(),
                                        memberUser,
                                        TeamJoinMethod.OIDC,
                                        NOW));
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
                                        member,
                                        foundation.initialization().defaultWorkspace(),
                                        memberUser);
                            });
            var second =
                    executor.submit(
                            () -> {
                                start.await();
                                return service.ensureDefault(
                                        member,
                                        foundation.initialization().defaultWorkspace(),
                                        memberUser);
                            });
            start.countDown();

            assertEquals(first.get().agentPrincipal().id(), second.get().agentPrincipal().id());
            assertEquals(
                    1,
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM crewscope.agent_profile WHERE owner_member_id ="
                                    + " ?",
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
                                Set.of(
                                        new WorkItemLabel("Backend"),
                                        new WorkItemLabel("Collaboration")),
                                Optional.of(LATER),
                                WorkItemSource.JIRA,
                                "JIRA-101",
                                foundation.creator(),
                                NOW));

        WorkItem loaded =
                workItemRepository
                        .findById(foundation.organizationId(), created.id())
                        .orElseThrow();
        assertEquals(WorkItemType.FEATURE, loaded.type());
        assertEquals(Optional.of("Full M1 description"), loaded.description());
        assertEquals(
                Set.of(new WorkItemLabel("backend"), new WorkItemLabel("collaboration")),
                loaded.labels());
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
        assertThrows(
                OptimisticLockConflictException.class, () -> workItemRepository.update(revised));

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
                commentRepository
                        .findByWorkItem(foundation.organizationId(), revised.id())
                        .get(0)
                        .id());
        assertEquals(
                link.resourceReference(),
                resourceLinkRepository
                        .findByWorkItem(foundation.organizationId(), revised.id())
                        .get(0)
                        .resourceReference());

        WorkProject renamed = project.rename("Renamed Project", foundation.creator(), LATER);
        assertEquals("Renamed Project", projectRepository.update(renamed).name());
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
                ResponsibilityConflictException.class,
                () -> assignmentRepository.create(duplicate));
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
                assignmentRepository
                        .findActiveOwner(foundation.organizationId(), workItem.id())
                        .isEmpty());
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

        assertTrue(
                teamRepository.findById(other, foundation.initialization().team().id()).isEmpty());
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
                organizationId,
                creator,
                service.create(creator, new CreateTeamCommand("Team " + suffix)));
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
        JpaAgentProfileRepositoryAdapter.class,
        WorkPersistenceMapper.class,
        WorkItemEntityMapper.class,
        JpaWorkProjectRepositoryAdapter.class,
        JpaWorkItemRepositoryAdapter.class,
        JpaWorkItemCommentRepositoryAdapter.class,
        JpaWorkItemResourceLinkRepositoryAdapter.class,
        ResponsibilityPersistenceMapper.class,
        JpaResponsibilityAssignmentRepositoryAdapter.class,
        SpringTransactionExecutor.class
    })
    static class TestApplication {}
}
