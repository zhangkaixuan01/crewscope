package io.crewscope.infrastructure.persistence.responsibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.responsibility.AssignResponsibilityCommand;
import io.crewscope.application.responsibility.GateReviewerAssignmentService;
import io.crewscope.application.responsibility.ReleaseResponsibilityCommand;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.responsibility.ResponsibilityAssignmentService;
import io.crewscope.application.responsibility.ResponsibilityCommandService;
import io.crewscope.application.responsibility.ResponsibilityHandoverApplicationService;
import io.crewscope.application.responsibility.ResponsibilityHandoverRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.application.workitem.WorkItemRepository;
import io.crewscope.application.workitem.WorkProjectRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.responsibility.ReviewerEligibilityPolicy;
import io.crewscope.domain.responsibility.handover.HandoverItemState;
import io.crewscope.domain.responsibility.handover.HandoverJobStatus;
import io.crewscope.domain.responsibility.handover.ResponsibilityHandoverItem;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.BuiltInTeamRole;
import io.crewscope.domain.team.MemberRole;
import io.crewscope.domain.team.MemberRoleId;
import io.crewscope.domain.team.RoleScope;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamJoinMethod;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamScope;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.infrastructure.persistence.command.JdbcCommandReceiptStore;
import io.crewscope.infrastructure.persistence.event.JdbcDomainEventStore;
import io.crewscope.infrastructure.persistence.event.JdbcOutboxRepository;
import io.crewscope.infrastructure.persistence.team.TeamPersistenceMapper;
import io.crewscope.infrastructure.persistence.team.JpaTeamRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.JpaTeamMemberRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.JpaTeamRoleRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.JpaMemberRoleRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.JpaPrincipalRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.JpaWorkspaceRepositoryAdapter;
import io.crewscope.infrastructure.persistence.workitem.JpaWorkItemRepositoryAdapter;
import io.crewscope.infrastructure.persistence.workitem.WorkItemEntityMapper;
import io.crewscope.infrastructure.persistence.workitem.WorkPersistenceMapper;
import io.crewscope.infrastructure.persistence.workitem.JpaWorkProjectRepositoryAdapter;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import io.crewscope.infrastructure.transaction.SpringTransactionExecutor;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M11-Q01 attack-set face for responsibility handover (ADR-038 §1): the target's membership
 * is revoked MID-FLIGHT — after the job committed its first item's DONE outcome — and the
 * resumed run must still converge: the queued item stops as DENIED, its paired release is
 * rolled back, the job completes with no PENDING residue, and every WorkItem keeps exactly
 * one ACTIVE executor (the settled item under the target, the stopped one under the
 * source): no orphan chain. The REMOVED-before-start shape is pinned in
 * HandoverJobRestartIntegrationTest; SUSPENDED and REMOVED both reduce to
 * {@code canParticipate() == false} in the eligibility check, so one mid-flight scenario
 * covers the branch rather than duplicating it.
 */
@SpringBootTest(
        classes = HandoverTargetRevocationM11Q01IntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.open-in-view=false"
        })
@EntityScan(basePackages = "io.crewscope.infrastructure.persistence")
class HandoverTargetRevocationM11Q01IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final UtcTimestamp NOW =
            UtcTimestamp.from(Instant.parse("2026-10-12T09:00:00Z"));

    @Autowired private TeamRepository teams;
    @Autowired private TeamMemberRepository members;
    @Autowired private TeamRoleRepository roleRepository;
    @Autowired private MemberRoleRepository grants;
    @Autowired private PrincipalRepository principals;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private WorkItemRepository workItems;
    @Autowired private WorkProjectRepository workProjects;
    @Autowired private ResponsibilityAssignmentRepository assignments;
    @Autowired private ResponsibilityHandoverRepository handovers;
    @Autowired private DomainEventStore eventStore;
    @Autowired private OutboxRepository outbox;
    @Autowired private CommandReceiptStore receipts;
    @Autowired private TransactionExecutor transactions;
    @Autowired private JdbcTemplate jdbc;

    private final TimeProvider time = () -> NOW;
    private ResponsibilityCommandService commands;
    private ResponsibilityHandoverApplicationService service;

    @BeforeEach
    void resetBusinessData() {
        jdbc.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        WorkItemAccessPolicy accessPolicy =
                new WorkItemAccessPolicy(
                        workItems, workProjects, teams, (TeamMembershipQuery) members,
                        roleRepository, grants);
        commands =
                new ResponsibilityCommandService(
                        assignments,
                        new ResponsibilityAssignmentService(assignments, transactions, time),
                        new GateReviewerAssignmentService(
                                assignments, (TeamMembershipQuery) members, transactions, time),
                        workItem -> ReviewerEligibilityPolicy.strict(),
                        accessPolicy,
                        principals,
                        (TeamMembershipQuery) members,
                        eventStore,
                        outbox,
                        receipts,
                        transactions,
                        time);
        service =
                new ResponsibilityHandoverApplicationService(
                        handovers,
                        assignments,
                        commands,
                        accessPolicy,
                        teams,
                        members,
                        (TeamMembershipQuery) members,
                        principals,
                        eventStore,
                        outbox,
                        receipts,
                        transactions,
                        time);
    }

    /**
     * A mid-flight suspension of the target stops the queued item as DENIED with the paired
     * release rolled back, settles nothing twice, and leaves every WorkItem with exactly one
     * ACTIVE executor — the settled item under the target, the stopped one back under the
     * source. No orphan chain, no PENDING residue.
     */
    @Test
    void suspendingTheTargetMidFlightStopsRemainingItemsAsDeniedAndLeavesNoOrphanChain() {
        Fixture fixture = fixture("Suspended Team");
        Principal sourceUser = addUser(fixture, "Source Member");
        TeamMember sourceMember = addMember(fixture, sourceUser);
        Principal targetUser = addUser(fixture, "Target Member");
        TeamMember targetMember = addMember(fixture, targetUser);
        ResponsibilityAssignment first =
                assignExecutor(fixture, sourceUser, addWorkItem(fixture, "SUSPEND-1"));
        ResponsibilityAssignment second =
                assignExecutor(fixture, sourceUser, addWorkItem(fixture, "SUSPEND-2"));

        ResponsibilityHandoverApplicationService.HandoverJobCreated created =
                service.createJob(
                        context(fixture.owner(), "suspend-create"),
                        fixture.team().id(),
                        sourceMember.id(),
                        targetUser.id(),
                        ResponsibilityRole.EXECUTOR);
        // The committed prefix of an interrupted run: the job is RUNNING, the first item's
        // transfer really committed under the target, and the item row records it DONE —
        // exactly the rows a restart may see. (The transfer is replayed through the real
        // command so the no-orphan-chain claim below reads committed rows, not pointers.)
        handovers.updateJob(
                handovers
                        .findJobById(fixture.organizationId(), created.job().id())
                        .orElseThrow()
                        .markRunning(fixture.owner().id(), NOW));
        ResponsibilityAssignmentId firstMoved =
                transactions.required(
                        () -> {
                            // The exact transfer sequence processItem runs: release the
                            // source slot under its committed version, then assign the
                            // target — so the no-orphan-chain assertion reads real rows.
                            commands.release(
                                    context(fixture.owner(), "suspend-first-release"),
                                    fixture.team().id(),
                                    fixture.projectId(),
                                    first.workItemId(),
                                    first.id(),
                                    new ReleaseResponsibilityCommand(first.version()));
                            return commands.assignExecutor(
                                            context(fixture.owner(), "suspend-first-assign"),
                                            fixture.team().id(),
                                            fixture.projectId(),
                                            first.workItemId(),
                                            new AssignResponsibilityCommand(targetUser.id()))
                                    .result()
                                    .orElseThrow()
                                    .id();
                        });
        handovers.updateItem(
                itemFor(created, first).markDone(fixture.owner().id(), firstMoved, NOW));

        // The attack: the target's membership is suspended while the job is in flight — the
        // exact moment a revocation lands between two item transactions.
        jdbc.update(
                "UPDATE crewscope.team_member SET status = 'SUSPENDED',"
                        + " authorization_version = authorization_version + 1 WHERE id = ?",
                targetMember.id().value());

        ResponsibilityHandoverApplicationService.HandoverJobView view =
                service.processJob(
                        context(fixture.owner(), "suspend-process"),
                        fixture.team().id(),
                        created.job().id());

        assertEquals(HandoverItemState.DONE, itemFor(view, first).state());
        ResponsibilityHandoverItem denied = itemFor(view, second);
        assertEquals(HandoverItemState.DENIED, denied.state());
        assertEquals(Optional.of("TARGET_NOT_ELIGIBLE"), denied.errorCode());
        assertEquals(HandoverJobStatus.COMPLETED, view.job().status());

        // The DENIED item's transaction rolled its paired release back: the source keeps
        // the slot, and the suspended target holds nothing new.
        assertTrue(
                assignments
                        .findById(fixture.organizationId(), second.id())
                        .orElseThrow()
                        .isActive());
        assertTrue(
                assignments
                        .findActive(
                                fixture.organizationId(),
                                second.workItemId(),
                                ResponsibilityRole.EXECUTOR,
                                targetUser.id())
                        .isEmpty());

        // No orphan chain: every WorkItem still holds exactly one ACTIVE executor — the
        // settled one under the target, the stopped one under the source.
        for (ResponsibilityAssignment source : List.of(first, second)) {
            assertEquals(
                    1L,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM crewscope.responsibility_assignment"
                                    + " WHERE work_item_id = ? AND role = 'EXECUTOR'"
                                    + " AND status = 'ACTIVE'",
                            Long.class,
                            source.workItemId().value()),
                    "work item " + source.workItemId() + " must keep exactly one executor");
        }
        assertTrue(
                assignments
                        .findActive(
                                fixture.organizationId(),
                                first.workItemId(),
                                ResponsibilityRole.EXECUTOR,
                                targetUser.id())
                        .isPresent());

        // The run converged: nothing in this job is left PENDING.
        assertEquals(
                0L,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.responsibility_handover_item"
                                + " WHERE job_id = ? AND state = 'PENDING'",
                        Long.class,
                        created.job().id().value()));
    }

    // ------------------------------------------------------------------ fixtures

    private ResponsibilityHandoverItem itemFor(
            ResponsibilityHandoverApplicationService.HandoverJobCreated created,
            ResponsibilityAssignment source) {
        return created.items().stream()
                .filter(item -> item.assignmentId().equals(source.id()))
                .findFirst()
                .orElseThrow();
    }

    private ResponsibilityHandoverItem itemFor(
            ResponsibilityHandoverApplicationService.HandoverJobView view,
            ResponsibilityAssignment source) {
        return view.items().stream()
                .filter(item -> item.assignmentId().equals(source.id()))
                .findFirst()
                .orElseThrow();
    }

    private ResponsibilityAssignment assignExecutor(
            Fixture fixture, Principal executor, WorkItem workItem) {
        CommandExecution<ResponsibilityAssignment> execution =
                commands.assignExecutor(
                        context(fixture.owner(), "seed-executor-" + workItem.id()),
                        fixture.team().id(),
                        fixture.projectId(),
                        workItem.id(),
                        new AssignResponsibilityCommand(executor.id()));
        return execution.result().orElseThrow();
    }

    private WorkItem addWorkItem(Fixture fixture, String key) {
        return workItems.create(
                WorkItem.create(
                        WorkItemId.generate(),
                        fixture.scope(),
                        new WorkItemKey(key),
                        "Work item " + key,
                        fixture.owner().id(),
                        NOW));
    }

    private TeamCommandContext context(Principal actor, String key) {
        return new TeamCommandContext(
                new TeamAccessContext(actor, false),
                IdempotencyKey.from(key),
                UUID.randomUUID(),
                Optional.empty());
    }

    private Fixture fixture(String teamName) {
        OrganizationId organizationId = OrganizationId.generate();
        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, ?, 'ACTIVE')",
                organizationId.value(),
                teamName + " Organization");
        Principal owner =
                Principal.create(
                        PrincipalId.generate(),
                        PrincipalScope.organization(organizationId),
                        PrincipalType.USER,
                        Optional.empty(),
                        teamName + " Owner",
                        Optional.empty(),
                        PrincipalVisibility.ORGANIZATION,
                        NOW);
        insertPrincipal(organizationId, owner);
        TeamInitialization initialization = TeamInitialization.create(owner, teamName, NOW);
        // One transaction mirrors TeamCreationService: the Team row's deferred owner reference
        // is only checked at commit, after the membership row exists.
        TeamMember ownerMember =
                transactions.required(
                        () -> {
                            teams.create(initialization.team());
                            workspaces.create(initialization.defaultWorkspace());
                            TeamMember created = members.create(initialization.ownerMember());
                            roleRepository.createAll(initialization.builtInRoles());
                            grants.create(initialization.ownerRole());
                            return created;
                        });
        WorkProjectId projectId = WorkProjectId.generate();
        jdbc.update(
                """
                INSERT INTO crewscope.work_project (
                    id, organization_id, team_id, workspace_id, project_key, name,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                projectId.value(),
                organizationId.value(),
                initialization.team().id().value(),
                initialization.defaultWorkspace().id().value(),
                projectKey(teamName),
                teamName + " Project",
                owner.id().value(),
                owner.id().value());
        return new Fixture(organizationId, initialization, owner, ownerMember, projectId);
    }

    private Principal addUser(Fixture fixture, String displayName) {
        Principal user =
                Principal.create(
                        PrincipalId.generate(),
                        PrincipalScope.organization(fixture.organizationId()),
                        PrincipalType.USER,
                        Optional.empty(),
                        displayName,
                        Optional.empty(),
                        PrincipalVisibility.ORGANIZATION,
                        NOW);
        insertPrincipal(fixture.organizationId(), user);
        return user;
    }

    private void insertPrincipal(OrganizationId organizationId, Principal principal) {
        jdbc.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, visibility, status
                ) VALUES (?, ?, 'USER', ?, 'ORGANIZATION', 'ACTIVE')
                """,
                principal.id().value(),
                organizationId.value(),
                principal.displayName());
    }

    private TeamMember addMember(Fixture fixture, Principal user) {
        TeamMember member =
                TeamMember.join(
                        TeamMemberId.generate(),
                        new TeamScope(fixture.organizationId(), fixture.team().id()),
                        user,
                        TeamJoinMethod.OIDC,
                        NOW);
        return transactions.required(
                () -> {
                    TeamMember created = members.create(member);
                    grants.create(
                            MemberRole.grant(
                                    MemberRoleId.generate(),
                                    created,
                                    roleByKey(fixture, BuiltInTeamRole.MEMBER),
                                    RoleScope.team(),
                                    fixture.owner().id(),
                                    NOW,
                                    NOW,
                                    Optional.empty()));
                    return created;
                });
    }

    private TeamRole roleByKey(Fixture fixture, BuiltInTeamRole builtIn) {
        return roleRepository
                .findByTeam(fixture.organizationId(), fixture.team().id())
                .stream()
                .filter(role -> role.isBuiltIn(builtIn))
                .findFirst()
                .orElseThrow();
    }

    private static String projectKey(String suffix) {
        String normalized = suffix.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        return normalized.substring(0, Math.min(10, normalized.length()));
    }

    private record Fixture(
            OrganizationId organizationId,
            TeamInitialization initialization,
            Principal owner,
            TeamMember ownerMember,
            WorkProjectId projectId) {

        private Team team() {
            return initialization.team();
        }

        private WorkItemScope scope() {
            return new WorkItemScope(
                    organizationId,
                    initialization.team().id(),
                    initialization.defaultWorkspace().id(),
                    projectId);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
        TeamPersistenceMapper.class,
        JpaTeamRepositoryAdapter.class,
        JpaTeamMemberRepositoryAdapter.class,
        JpaTeamRoleRepositoryAdapter.class,
        JpaMemberRoleRepositoryAdapter.class,
        JpaPrincipalRepositoryAdapter.class,
        JpaWorkspaceRepositoryAdapter.class,
        WorkItemEntityMapper.class,
        WorkPersistenceMapper.class,
        JpaWorkItemRepositoryAdapter.class,
        JpaWorkProjectRepositoryAdapter.class,
        ResponsibilityHandoverPersistenceMapper.class,
        ResponsibilityPersistenceMapper.class,
        JpaResponsibilityAssignmentRepositoryAdapter.class,
        JpaResponsibilityHandoverRepositoryAdapter.class,
        JdbcDomainEventStore.class,
        JdbcOutboxRepository.class,
        JdbcCommandReceiptStore.class,
        SpringTransactionExecutor.class
    })
    static class TestApplication {}
}
