package io.crewscope.infrastructure.persistence.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.inbox.InboxFilter;
import io.crewscope.application.inbox.InboxItemView;
import io.crewscope.application.inbox.InboxPage;
import io.crewscope.application.inbox.InboxQuery;
import io.crewscope.application.inbox.InboxSourceContext;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the M9b-A06 read-time Inbox source context against migrated PostgreSQL: each readable
 * source type resolves the live work facts of the object it points at, notifications carry none,
 * and a source that no longer exists renders without a context rather than failing the page.
 *
 * <p>The ACTION_* branches join the same equality shape over the action bundle tables; those carry
 * forty-plus NOT NULL columns only the event pipeline populates, so their positive path stays with
 * the projector's own integration tests while this test pins their miss path.
 */
@SpringBootTest(
        classes = InboxSourceContextIntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.default_schema=crewscope",
            "spring.jpa.open-in-view=false"
        })
class InboxSourceContextIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-09-02T08:00:00Z");
    private static final String HASH = "cd".repeat(32);
    private static final String PROJECTION_NAME = "member-inbox";

    @Autowired
    private JdbcInboxRepositoryAdapter repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private OrganizationId organizationId;
    private TeamId teamId;
    private UUID workspaceId;
    private WorkProjectId projectId;
    private UUID principalId;
    private TeamMemberId memberId;

    @BeforeEach
    void seedScope() {
        jdbc.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        organizationId = OrganizationId.generate();
        teamId = TeamId.generate();
        workspaceId = UUID.randomUUID();
        projectId = WorkProjectId.generate();
        principalId = UUID.randomUUID();

        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, ?, 'ACTIVE')",
                organizationId.value(), "A06 Inbox Context Organization");
        jdbc.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, visibility, status
                ) VALUES (?, ?, 'USER', 'A06 Inbox Member', 'ORGANIZATION', 'ACTIVE')
                """,
                principalId, organizationId.value());
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status) "
                        + "VALUES (?, ?, 'A06 Inbox Context Team', 'ACTIVE')",
                teamId.value(), organizationId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES (?, ?, ?, 'TEAM', 'A06 Inbox Context Workspace', 'ACTIVE')
                """,
                workspaceId, organizationId.value(), teamId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.work_project (
                    id, organization_id, team_id, workspace_id, project_key, name,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, 'A06', 'A06 Inbox Context Project', ?, ?)
                """,
                projectId.value(), organizationId.value(), teamId.value(), workspaceId,
                principalId, principalId);
        memberId = new TeamMemberId(seedMember(principalId));
        seedInboxProjection();
    }

    /**
     * Every readable source type resolves its live work facts: an assignment names its WorkItem, a
     * review names the work, the objective and the person it waits on, an execution names the work
     * it runs, a notification carries nothing, and an action whose bundle no longer exists renders
     * without a context instead of failing the page.
     */
    @Test
    void resolvesTheLiveWorkFactsOfEveryReadableSourceType() {
        UUID ownedWorkItem = seedWorkItem("A06-IA", "IN_PROGRESS");
        UUID assignmentSource = seedAssignment(ownedWorkItem, "OWNER", "ACTIVE", principalId);

        UUID reviewedWorkItem = seedWorkItem("A06-IR", "IN_REVIEW");
        UUID reviewedTask = seedTask(reviewedWorkItem, "评审目标甲");
        UUID reviewedExecution = executionOf(reviewedTask);
        UUID reviewSource = seedReviewRequest(reviewedTask, reviewedExecution);
        UUID reviewer = seedPrincipal("评审人乙");
        seedAssignment(reviewedWorkItem, "REVIEWER", "ACTIVE", reviewer, seedMember(reviewer));

        UUID executedWorkItem = seedWorkItem("A06-IE", "IN_PROGRESS");
        UUID executedTask = seedTask(executedWorkItem, "执行目标乙");
        UUID executionSource = executionOf(executedTask);

        UUID notificationSource = UUID.randomUUID();
        UUID vanishedActionSource = UUID.randomUUID();
        seedInboxItem("OWNERSHIP", "RESPONSIBILITY_ASSIGNMENT", assignmentSource, 5);
        seedInboxItem("REVIEW", "REVIEW_REQUEST", reviewSource, 4);
        seedInboxItem("EXCEPTION", "TASK_EXECUTION", executionSource, 3);
        seedInboxItem("EXCEPTION", "NOTIFICATION_DELIVERY", notificationSource, 2);
        seedInboxItem("CONFIRMATION", "ACTION_CONFIRMATION", vanishedActionSource, 1);

        InboxPage page = repository.findCurrentPage(query());

        var contexts = page.items().stream()
                .collect(Collectors.toMap(
                        view -> view.item().source().key().sourceType().name(),
                        Function.identity()));
        assertEquals(5, contexts.size(), "every seeded row is on the page");

        InboxItemView ownership = contexts.get("RESPONSIBILITY_ASSIGNMENT");
        InboxSourceContext ownedContext = ownership.sourceContext().orElseThrow();
        assertEquals("A06 A06-IA", ownedContext.workItemTitle().orElseThrow());
        assertEquals("WORK_ITEM", ownedContext.targetActionKind());
        assertEquals(ownedWorkItem.toString(),
                ownedContext.workItemId().orElseThrow().toString());
        assertTrue(ownedContext.taskObjective().isEmpty());
        assertTrue(ownedContext.waitingOnDisplayName().isEmpty());

        InboxItemView review = contexts.get("REVIEW_REQUEST");
        InboxSourceContext reviewContext = review.sourceContext().orElseThrow();
        assertEquals("A06 A06-IR", reviewContext.workItemTitle().orElseThrow());
        assertEquals("评审目标甲", reviewContext.taskObjective().orElseThrow());
        assertEquals("评审人乙", reviewContext.waitingOnDisplayName().orElseThrow());
        assertEquals("REVIEW", reviewContext.targetActionKind());
        assertEquals(projectId.toString(), reviewContext.projectId().orElseThrow().toString());

        InboxItemView execution = contexts.get("TASK_EXECUTION");
        InboxSourceContext executionContext = execution.sourceContext().orElseThrow();
        assertEquals("A06 A06-IE", executionContext.workItemTitle().orElseThrow());
        assertEquals("执行目标乙", executionContext.taskObjective().orElseThrow());
        assertEquals("TASK", executionContext.targetActionKind());
        assertTrue(executionContext.waitingOnDisplayName().isEmpty());

        assertTrue(contexts.get("NOTIFICATION_DELIVERY").sourceContext().isEmpty(),
                "a notification carries no work context");
        assertTrue(contexts.get("ACTION_CONFIRMATION").sourceContext().isEmpty(),
                "a row whose source object is gone renders without a context");
    }

    /**
     * The context is joined, never copied: a renamed WorkItem, a rewritten objective and a released
     * reviewer show their current facts on the very next read, with no reprojection involved.
     */
    @Test
    void reflectsSourceRenamesOnTheNextReadWithoutReprojection() {
        UUID reviewedWorkItem = seedWorkItem("A06-RR", "IN_REVIEW");
        UUID reviewedTask = seedTask(reviewedWorkItem, "旧目标");
        UUID reviewSource = seedReviewRequest(reviewedTask, executionOf(reviewedTask));
        UUID reviewer = seedPrincipal("评审人乙");
        seedAssignment(reviewedWorkItem, "REVIEWER", "ACTIVE", reviewer, seedMember(reviewer));
        UUID row = seedInboxItem("REVIEW", "REVIEW_REQUEST", reviewSource, 1);

        InboxSourceContext before = contextOf(row);
        assertEquals("A06 A06-RR", before.workItemTitle().orElseThrow());
        assertEquals("旧目标", before.taskObjective().orElseThrow());
        assertEquals("评审人乙", before.waitingOnDisplayName().orElseThrow());

        jdbc.update("UPDATE crewscope.work_item SET title = ? WHERE id = ?", "改名后的活",
                reviewedWorkItem);
        jdbc.update("UPDATE crewscope.task SET objective = ? WHERE id = ?", "新目标", reviewedTask);
        jdbc.update(
                "DELETE FROM crewscope.responsibility_assignment WHERE work_item_id = ? "
                        + "AND role = 'REVIEWER'",
                reviewedWorkItem);

        InboxSourceContext after = contextOf(row);
        assertEquals("改名后的活", after.workItemTitle().orElseThrow());
        assertEquals("新目标", after.taskObjective().orElseThrow());
        assertTrue(after.waitingOnDisplayName().isEmpty(),
                "no active reviewer means nobody is named, not a stale snapshot");
    }

    private InboxSourceContext contextOf(UUID inboxItemId) {
        return repository.findCurrentPage(query()).items().stream()
                .filter(view -> view.item().id().value().equals(inboxItemId))
                .findFirst()
                .orElseThrow()
                .sourceContext()
                .orElseThrow();
    }

    private InboxQuery query() {
        return new InboxQuery(
                organizationId, teamId, memberId, InboxFilter.OPEN, Optional.empty(), 10);
    }

    private UUID seedWorkItem(String itemKey, String status) {
        UUID workItemId = UUID.randomUUID();
        OffsetDateTime now = BASE.atOffset(ZoneOffset.UTC);
        jdbc.update(
                """
                INSERT INTO crewscope.work_item (
                    id, organization_id, team_id, workspace_id, project_id,
                    item_key, item_type, title, status, priority, due_at,
                    source_provider, created_at, updated_at,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, 'TASK', ?, ?, 'HIGH', NULL, 'CREWSCOPE', ?, ?, ?, ?)
                """,
                workItemId, organizationId.value(), teamId.value(), workspaceId, projectId.value(),
                itemKey, "A06 " + itemKey, status, now, now, principalId, principalId);
        return workItemId;
    }

    /** One task with a READY execution; the context reads the objective from the live row. */
    private UUID seedTask(UUID workItemId, String objective) {
        UUID taskId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        OffsetDateTime now = BASE.atOffset(ZoneOffset.UTC);
        jdbc.update(
                """
                INSERT INTO crewscope.task_responsibility_snapshot (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    snapshot_hash, captured_at, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                snapshotId, organizationId.value(), teamId.value(), workspaceId, projectId.value(),
                workItemId, HASH, now, now, principalId, now, principalId);
        jdbc.update(
                """
                INSERT INTO crewscope.task (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    source_type, source_work_item_version, responsibility_snapshot_id,
                    status, objective, acceptance_criteria,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, 'WORK_ITEM', 0, ?, 'CREATED', ?,
                          '["Accept"]'::JSONB, ?, ?, ?, ?)
                """,
                taskId, organizationId.value(), teamId.value(), workspaceId, projectId.value(),
                workItemId, snapshotId, objective, now, principalId, now, principalId);
        jdbc.update(
                """
                INSERT INTO crewscope.task_execution (
                    id, organization_id, team_id, workspace_id, project_id, task_id,
                    attempt, max_attempts, priority, not_before, status,
                    waiting_reason, waiting_since,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, 1, 1, 80, ?, 'READY', NULL, NULL, ?, ?, ?, ?)
                """,
                executionId, organizationId.value(), teamId.value(), workspaceId, projectId.value(),
                taskId, now, now, principalId, now, principalId);
        jdbc.update(
                "UPDATE crewscope.task SET current_execution_id = ?, status = 'ACTIVE' WHERE id = ?",
                executionId, taskId);
        return taskId;
    }

    private UUID executionOf(UUID taskId) {
        return jdbc.queryForObject(
                "SELECT current_execution_id FROM crewscope.task WHERE id = ?", UUID.class, taskId);
    }

    private UUID seedAssignment(UUID workItemId, String role, String status, UUID actorPrincipal) {
        return seedAssignment(workItemId, role, status, actorPrincipal, memberId.value());
    }

    private UUID seedAssignment(
            UUID workItemId, String role, String status, UUID actorPrincipal, UUID actorMember) {
        OffsetDateTime now = BASE.atOffset(ZoneOffset.UTC);
        UUID assignmentId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO crewscope.responsibility_assignment (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    role, actor_principal_id, actor_type, actor_member_id, status,
                    assigned_by_principal_id, assigned_at, accepted_at,
                    released_by_principal_id, released_at,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'USER', ?, ?, ?, ?, ?, NULL, NULL, ?, ?, ?, ?)
                """,
                assignmentId, organizationId.value(), teamId.value(), workspaceId,
                projectId.value(), workItemId, role, actorPrincipal, actorMember, status,
                principalId, now, now, now, principalId, now, principalId);
        return assignmentId;
    }

    private UUID seedPrincipal(String displayName) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, visibility, status
                ) VALUES (?, ?, 'USER', ?, 'ORGANIZATION', 'ACTIVE')
                """,
                id, organizationId.value(), displayName);
        return id;
    }

    private UUID seedReviewRequest(UUID taskId, UUID executionId) {
        OffsetDateTime now = BASE.atOffset(ZoneOffset.UTC);
        UUID requestId = UUID.randomUUID();
        UUID contextId = UUID.randomUUID();
        // The review tables guard a write path this seed does not reproduce; the projector's own
        // tests suspend the triggers the same way while seeding the read model's join inputs.
        inReplica(() -> {
            jdbc.update(
                    """
                    INSERT INTO crewscope.review_context_package (
                        id, organization_id, team_id, workspace_id, project_id,
                        task_id, task_execution_id, attempt, package_version,
                        subject_id, subject_type, subject_hash, diff_artifact_id,
                        diff_final_hash, coding_target_snapshot_id, coding_target_revision,
                        coding_target_hash, diff_generation, diff_manifest_hash,
                        test_evidence_id, test_evidence_hash, reviewer_agent_profile_id,
                        reviewer_agent_profile_version, reviewer_agent_principal_id,
                        reviewer_owner_member_id, subject_owner_member_id,
                        reviewer_relationship, reviewer_template_key, reviewer_template_version,
                        reviewer_template_hash, reviewer_configuration_revision,
                        reviewer_configuration_hash, policy_snapshot_id,
                        policy_snapshot_revision, policy_snapshot_hash, context_hash,
                        authority_snapshot, created_at, created_by_principal_id
                    ) VALUES (
                        ?, ?, ?, ?, ?, ?, ?, 1, 1, ?, 'CODE_CHANGE', ?, ?, ?, ?, 1,
                        ?, 1, ?, ?, ?, ?, 0, ?, ?, ?, 'SELF_REVIEW', 'reviewer', 1,
                        ?, 1, ?, ?, 1, ?, ?, '{}'::JSONB, ?, ?
                    )
                    """,
                    contextId, organizationId.value(), teamId.value(), workspaceId,
                    projectId.value(), taskId, executionId, UUID.randomUUID(), HASH,
                    UUID.randomUUID(), HASH, UUID.randomUUID(), HASH, HASH, UUID.randomUUID(),
                    HASH, UUID.randomUUID(), principalId, memberId.value(), memberId.value(),
                    HASH, HASH, UUID.randomUUID(), HASH, HASH, now, principalId);
            jdbc.update(
                    """
                    INSERT INTO crewscope.review_request (
                        id, organization_id, team_id, workspace_id, project_id,
                        task_id, task_execution_id, attempt, revision, predecessor_request_id,
                        subject_id, subject_type, subject_hash, context_package_id,
                        context_package_version, context_hash, request_hash, status,
                        invalidation_reason, version, created_at, created_by_principal_id,
                        updated_at, updated_by_principal_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1, NULL, ?, 'CODE_CHANGE', ?, ?, 1, ?, ?,
                              'OPEN', NULL, 0, ?, ?, ?, ?)
                    """,
                    requestId, organizationId.value(), teamId.value(), workspaceId,
                    projectId.value(), taskId, executionId, UUID.randomUUID(), HASH, contextId,
                    HASH, HASH, now, principalId, now, principalId);
        });
        return requestId;
    }

    private UUID seedMember(UUID principal) {
        UUID member = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO crewscope.team_member "
                        + "(id, organization_id, team_id, user_principal_id, status, join_method, joined_at) "
                        + "VALUES (?, ?, ?, ?, 'ACTIVE', 'IMPORT', ?)",
                member, organizationId.value(), teamId.value(), principal,
                BASE.atOffset(ZoneOffset.UTC));
        return member;
    }

    private void seedInboxProjection() {
        OffsetDateTime now = BASE.atOffset(ZoneOffset.UTC);
        // The definition table is global, so a previous case in the same container may hold the row.
        jdbc.update(
                """
                INSERT INTO crewscope.projection_definition (
                    projection_name, definition_version, projection_schema_version,
                    canonical_encoder, validator
                ) VALUES (?, 1, 1, 'inbox.canonical-v1', 'inbox.expected-v1')
                ON CONFLICT (projection_name, definition_version) DO NOTHING
                """,
                PROJECTION_NAME);
        inReplica(() -> {
            jdbc.update(
                    """
                    INSERT INTO crewscope.projection_generation (
                        organization_id, projection_name, generation, definition_version,
                        status, fencing_token, version, created_at, updated_at
                    ) VALUES (?, ?, 1, 1, 'ACTIVE', 1, 0, ?, ?)
                    ON CONFLICT (organization_id, projection_name, generation) DO NOTHING
                    """,
                    organizationId.value(), PROJECTION_NAME, now, now);
            jdbc.update(
                    """
                    INSERT INTO crewscope.projection_pointer (
                        organization_id, projection_name, active_generation, version, updated_at
                    ) VALUES (?, ?, 1, 0, ?)
                    ON CONFLICT (organization_id, projection_name) DO NOTHING
                    """,
                    organizationId.value(), PROJECTION_NAME, now);
        });
    }

    private UUID seedInboxItem(
            String itemType, String sourceType, UUID sourceId, int openedOffsetSeconds) {
        // The Inbox id is derived from the canonical source key, not chosen; the read model's
        // mapper revalidates that derivation on every row.
        io.crewscope.domain.inbox.InboxSourceKey key = new io.crewscope.domain.inbox.InboxSourceKey(
                organizationId, memberId,
                io.crewscope.domain.inbox.InboxItemType.valueOf(itemType),
                io.crewscope.domain.inbox.InboxSourceType.valueOf(sourceType),
                sourceId, new io.crewscope.domain.inbox.InboxSourceRevision(0));
        UUID inboxItemId = io.crewscope.domain.inbox.InboxItemId.fromSource(key).value();
        jdbc.update(
                """
                INSERT INTO crewscope.inbox_item (
                    organization_id, team_id, member_id, projection_name, generation,
                    inbox_item_id, projection_schema_version, item_type, source_type,
                    source_id, source_revision, priority, deadline, opened_at, source_status
                ) VALUES (?, ?, ?, ?, 1, ?, 1, ?, ?, ?, 0, 'NORMAL', NULL, ?, 'OPEN')
                """,
                organizationId.value(), teamId.value(), memberId.value(), PROJECTION_NAME,
                inboxItemId, itemType, sourceType, sourceId,
                BASE.plusSeconds(openedOffsetSeconds).atOffset(ZoneOffset.UTC));
        return inboxItemId;
    }

    private void inReplica(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            work.run();
        });
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(JdbcInboxRepositoryAdapter.class)
    static class TestApplication {}
}
