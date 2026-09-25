package io.crewscope.infrastructure.persistence.workdesk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.workdesk.WorkDeskItem;
import io.crewscope.application.workdesk.WorkDeskQuery;
import io.crewscope.application.workdesk.WorkDeskRepository;
import io.crewscope.application.workdesk.WorkDeskSection;
import io.crewscope.application.workdesk.WorkDeskSectionPosition;
import io.crewscope.application.workdesk.WorkDeskSummary;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
 * Proves the M9b-A06 WorkDesk section pagination against migrated PostgreSQL: every section pages
 * on its own keyset without duplicates or gaps across equal tails, totals are the true full-set
 * counts, rows name the WorkItem they speak about, and the Inbox section serves the member's real
 * unread rows in the Inbox's own ordering.
 */
@SpringBootTest(
        classes = WorkDeskSectionPaginationIntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.default_schema=crewscope",
            "spring.jpa.open-in-view=false"
        })
class WorkDeskSectionPaginationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-09-02T08:00:00Z");
    private static final String HASH = "cd".repeat(32);
    private static final String PROJECTION_NAME = "member-inbox";

    @Autowired
    private WorkDeskRepository repository;

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
    private final Map<UUID, Integer> reviewRevisions = new HashMap<>();
    private final Map<UUID, UUID> lastReviewRequest = new HashMap<>();
    private final Map<UUID, UUID> contextPackages = new HashMap<>();

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
                organizationId.value(), "A06 Desk Organization");
        jdbc.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, visibility, status
                ) VALUES (?, ?, 'USER', 'A06 Desk Member', 'ORGANIZATION', 'ACTIVE')
                """,
                principalId, organizationId.value());
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status) "
                        + "VALUES (?, ?, 'A06 Desk Team', 'ACTIVE')",
                teamId.value(), organizationId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES (?, ?, ?, 'TEAM', 'A06 Desk Workspace', 'ACTIVE')
                """,
                workspaceId, organizationId.value(), teamId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.work_project (
                    id, organization_id, team_id, workspace_id, project_key, name,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, 'A06', 'A06 Desk Project', ?, ?)
                """,
                projectId.value(), organizationId.value(), teamId.value(), workspaceId,
                principalId, principalId);
        memberId = new TeamMemberId(seedMember(principalId));
    }

    /**
     * Six hundred and five owned rows over sixty distinct updatedAt values — ten rows share each
     * tail — five of them in another project: the desk is team-wide across projects, so paging the
     * WORK_ITEM section at one hundred rows must walk all six hundred and five exactly once,
     * newest first, without a duplicate or a gap across the equal-time boundaries, and must report
     * the true totals under both the unscoped and the scoped query.
     */
    @Test
    void pagesTheWorkItemSectionAcrossEqualTailsWithoutDuplicatesOrGaps() {
        UUID other = otherProject();
        seedOwnedWorkItems(600, projectId.value(), "DESK");
        seedOwnedWorkItems(5, other, "OTHER");

        WorkDeskSummary first = repository.summarize(query(100, Optional.empty()));
        WorkDeskSection section = section(first, "WORK_ITEM");

        assertEquals(605, section.total(), "the desk spans the member's projects in the team");
        assertTrue(section.truncated());
        assertEquals(100, section.items().size());
        assertTrue(section.nextPosition().isPresent());

        List<String> seen = new ArrayList<>(section.items().stream().map(WorkDeskItem::objectId).toList());
        Optional<WorkDeskSectionPosition> position = section.nextPosition();
        int pages = 1;
        while (position.isPresent()) {
            WorkDeskSection next = repository.summarizeSection(query(100, Optional.empty()), position.orElseThrow());
            assertEquals(605, next.total(), "every page reports the same full-set total");
            next.items().forEach(item -> seen.add(item.objectId()));
            position = next.nextPosition();
            pages++;
        }

        assertEquals(7, pages, "six hundred five rows at one hundred per page is seven pages");
        assertEquals(Set.copyOf(seen).size(), seen.size(), "no row may appear twice across pages");
        assertEquals(605, seen.size(), "the traversal returns every row exactly once");

        WorkDeskSection scoped = section(
                repository.summarize(query(100, Optional.of(other))), "WORK_ITEM");
        assertEquals(5, scoped.total(), "the scoped query counts only its project's rows");
    }

    /**
     * Executions and review requests page on their own keys and carry the WorkItem facts: three
     * executions over two pages with an equal-time tail, and one review chain whose projection rows
     * name the work item they review.
     */
    @Test
    void continuesExecutionsAndReviewsWithTheirOwnKeysAndWorkItemFacts() {
        for (int index = 0; index < 3; index++) {
            UUID workItem = seedWorkItem("A06-E" + index, "IN_PROGRESS", BASE.plusSeconds(index));
            seedTask(workItem);
        }
        UUID reviewed = seedWorkItem("A06-R0", "IN_REVIEW", BASE);
        UUID reviewedTask = seedTask(reviewed);
        seedReviewProjection(reviewedTask, "OPEN", null);
        seedReviewProjection(reviewedTask, "OPEN", null);
        seedAssignment(reviewed, "REVIEWER", "ACTIVE");

        WorkDeskSection executions = section(repository.summarize(query(2, Optional.empty())), "TASK_EXECUTION");
        // Four executions: the three IN_PROGRESS rows plus the reviewed item's own READY run.
        assertEquals(4, executions.total());
        assertTrue(executions.truncated() && executions.nextPosition().isPresent());
        List<String> executionIds = new ArrayList<>(executions.items().stream().map(WorkDeskItem::objectId).toList());
        WorkDeskSection rest = repository.summarizeSection(
                query(2, Optional.empty()), executions.nextPosition().orElseThrow());
        assertEquals(4, rest.total());
        assertFalse(rest.truncated());
        rest.items().forEach(item -> executionIds.add(item.objectId()));
        assertEquals(4, Set.copyOf(executionIds).size(), "four executions page without a duplicate");
        assertEquals(4, executionIds.size());
        assertTrue(executions.items().stream().allMatch(item -> item.workItemId().isPresent()
                        && item.workItemTitle().isPresent()),
                "every execution row names the WorkItem it runs for");

        WorkDeskSection reviews = section(repository.summarize(query(1, Optional.empty())), "REVIEW");
        assertEquals(2, reviews.total());
        assertEquals(1, reviews.items().size());
        assertTrue(reviews.truncated() && reviews.nextPosition().isPresent());
        WorkDeskSection secondReview = repository.summarizeSection(
                query(1, Optional.empty()), reviews.nextPosition().orElseThrow());
        assertEquals(2, secondReview.total());
        assertEquals(1, secondReview.items().size());
        assertFalse(secondReview.truncated());
        assertEquals(reviewed.toString(), reviews.items().get(0).workItemId().orElseThrow(),
                "the review row names the WorkItem it reviews");
        assertEquals("A06 A06-R0", reviews.items().get(0).workItemTitle().orElseThrow());
    }

    /**
     * The Inbox section serves the member's real unread rows in the Inbox's own ordering — rank
     * descending, then deadline ascending with nulls last, then opened_at and id — while a read
     * disposition and a closed source stay out of both the rows and the total.
     */
    @Test
    void servesTheInboxSectionFromRealUnreadRows() {
        seedInboxProjection();
        // Two URGENT rows with deadlines in ascending order, one URGENT and two HIGH rows without.
        UUID first = seedInboxItem("REVIEW", "URGENT", BASE.plusSeconds(600), BASE.plusSeconds(100));
        UUID second = seedInboxItem("REVIEW", "URGENT", BASE.plusSeconds(700), BASE.plusSeconds(100));
        UUID noDeadline = seedInboxItem("REVIEW", "URGENT", null, BASE.plusSeconds(200));
        UUID highOne = seedInboxItem("EXECUTION", "HIGH", null, BASE.plusSeconds(300));
        UUID highTwo = seedInboxItem("EXECUTION", "HIGH", null, BASE.plusSeconds(300));
        UUID read = seedInboxItem("REVIEW", "URGENT", null, BASE.plusSeconds(50));
        jdbc.update(
                """
                INSERT INTO crewscope.inbox_disposition (
                    organization_id, team_id, member_id, inbox_item_id, status, version,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, 'READ', 1, ?, ?, ?, ?)
                """,
                organizationId.value(), teamId.value(), memberId.value(), read,
                BASE.atOffset(ZoneOffset.UTC), principalId,
                BASE.atOffset(ZoneOffset.UTC), principalId);
        seedClosedInboxItem();

        WorkDeskSection inbox = section(repository.summarize(query(3, Optional.empty())), "INBOX");

        assertEquals(5, inbox.total(), "the read and closed rows never count");
        assertTrue(inbox.truncated() && inbox.nextPosition().isPresent());
        assertEquals(
                List.of(first, second, noDeadline),
                inbox.items().stream().map(item -> UUID.fromString(item.objectId())).toList(),
                "rank descends, deadlines ascend, and the null deadline trails the dated URGENT rows");
        WorkDeskSection rest = repository.summarizeSection(
                query(3, Optional.empty()), inbox.nextPosition().orElseThrow());
        assertEquals(5, rest.total());
        assertFalse(rest.truncated());
        // Equal rank, null deadline and opened_at: the tie-break is the database's unsigned id
        // order, so the expectation is built from the hexadecimal form (UUID.compareTo would
        // disagree on ids whose top nibble is 8..f).
        List<String> tailExpectation = java.util.stream.Stream.of(highOne, highTwo)
                .map(UUID::toString)
                .sorted(java.util.Comparator.reverseOrder())
                .toList();
        assertEquals(
                tailExpectation,
                rest.items().stream().map(WorkDeskItem::objectId).toList(),
                "equal HIGH rows break ties by id descending");
    }

    private WorkDeskQuery query(int sectionLimit, Optional<UUID> project) {
        return new WorkDeskQuery(
                organizationId,
                teamId,
                memberId,
                new PrincipalId(principalId),
                project.map(WorkProjectId::new),
                Optional.empty(),
                false,
                sectionLimit);
    }

    private static WorkDeskSection section(WorkDeskSummary summary, String key) {
        return summary.sections().stream()
                .filter(candidate -> candidate.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing section " + key));
    }

    private UUID otherProject() {
        UUID other = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO crewscope.work_project (
                    id, organization_id, team_id, workspace_id, project_key, name,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, 'OTH', 'A06 Other Project', ?, ?)
                """,
                other, organizationId.value(), teamId.value(), workspaceId, principalId, principalId);
        return other;
    }

    /** Rows that share one updatedAt per ten, all ACTIVE OWNER rows of the member. */
    private void seedOwnedWorkItems(int count, UUID project, String keyPrefix) {
        List<Object[]> items = new ArrayList<>(count);
        List<Object[]> assignments = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            UUID workItemId = UUID.randomUUID();
            OffsetDateTime updated = BASE.plusSeconds(index / 10).atOffset(ZoneOffset.UTC);
            items.add(new Object[] {
                    workItemId, organizationId.value(), teamId.value(), workspaceId, project,
                    keyPrefix + "-" + index, "A06 row " + index, updated, updated,
                    principalId, principalId});
            assignments.add(new Object[] {
                    UUID.randomUUID(), workItemId, project, principalId, memberId.value(), updated});
        }
        jdbc.batchUpdate(
                """
                INSERT INTO crewscope.work_item (
                    id, organization_id, team_id, workspace_id, project_id,
                    item_key, item_type, title, status, priority, due_at,
                    source_provider, created_at, updated_at,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, 'TASK', ?, 'BACKLOG', 'MEDIUM', NULL, 'CREWSCOPE', ?, ?, ?, ?)
                """,
                items);
        jdbc.batchUpdate(
                """
                INSERT INTO crewscope.responsibility_assignment (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    role, actor_principal_id, actor_type, actor_member_id, status,
                    assigned_by_principal_id, assigned_at, accepted_at,
                    released_by_principal_id, released_at,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, 'OWNER', ?, 'USER', ?, 'ACTIVE',
                          ?, ?, ?, NULL, NULL, ?, ?, ?, ?)
                """,
                assignments.stream()
                        .map(row -> new Object[] {
                                row[0], organizationId.value(), teamId.value(), workspaceId, row[2],
                                row[1], row[3], row[4], principalId, row[5], row[5],
                                row[5], principalId, row[5], principalId})
                        .toList());
    }

    private UUID seedWorkItem(String itemKey, String status, Instant updatedAt) {
        UUID workItemId = UUID.randomUUID();
        OffsetDateTime updated = updatedAt.atOffset(ZoneOffset.UTC);
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
                itemKey, "A06 " + itemKey, status, updated, updated, principalId, principalId);
        return workItemId;
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

    private void seedAssignment(UUID workItemId, String role, String status) {
        OffsetDateTime now = BASE.atOffset(ZoneOffset.UTC);
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
                UUID.randomUUID(), organizationId.value(), teamId.value(), workspaceId,
                projectId.value(), workItemId, role, principalId, memberId.value(), status,
                principalId, now, now, now, principalId, now, principalId);
    }

    /** One task with a READY execution; the WorkDesk's execution section reads its updated_at. */
    private UUID seedTask(UUID workItemId) {
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
                ) VALUES (?, ?, ?, ?, ?, ?, 'WORK_ITEM', 0, ?, 'CREATED', 'A06 objective',
                          '["Accept"]'::JSONB, ?, ?, ?, ?)
                """,
                taskId, organizationId.value(), teamId.value(), workspaceId, projectId.value(),
                workItemId, snapshotId, now, principalId, now, principalId);
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

    private void seedReviewProjection(UUID taskId, String requestStatus, String latestDecisionType) {
        OffsetDateTime now = BASE.atOffset(ZoneOffset.UTC);
        UUID requestId = UUID.randomUUID();
        UUID executionId =
                jdbc.queryForObject(
                        "SELECT current_execution_id FROM crewscope.task WHERE id = ?",
                        UUID.class, taskId);
        UUID contextId = ensureContextPackage(taskId, executionId, now);
        int revision = reviewRevisions.merge(executionId, 1, Integer::sum);
        UUID predecessor = lastReviewRequest.get(executionId);
        inReplica(() -> {
            jdbc.update(
                    """
                    INSERT INTO crewscope.review_request (
                        id, organization_id, team_id, workspace_id, project_id,
                        task_id, task_execution_id, attempt, revision, predecessor_request_id,
                        subject_id, subject_type, subject_hash, context_package_id,
                        context_package_version, context_hash, request_hash, status,
                        invalidation_reason, version, created_at, created_by_principal_id,
                        updated_at, updated_by_principal_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, 'CODE_CHANGE', ?, ?, 1, ?, ?, ?,
                              ?, 0, ?, ?, ?, ?)
                    """,
                    requestId, organizationId.value(), teamId.value(), workspaceId, projectId.value(),
                    taskId, executionId, revision, predecessor, UUID.randomUUID(), HASH,
                    contextId, HASH, HASH, requestStatus, null, now, principalId, now, principalId);
            jdbc.update(
                    """
                    INSERT INTO crewscope.review_request_projection (
                        review_request_id, organization_id, team_id, workspace_id, project_id,
                        task_id, task_execution_id, attempt, request_revision, request_version,
                        request_status, invalidation_reason, context_hash,
                        finding_count, duplicate_observation_count, blocker_count, high_count,
                        latest_decision_id, latest_decision_revision, latest_decision_type,
                        modification_round, projected_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, 0, ?, ?, ?, 0, 0, 0, 0, ?, ?, ?, 0, ?)
                    """,
                    requestId, organizationId.value(), teamId.value(), workspaceId, projectId.value(),
                    taskId, executionId, revision, requestStatus, null, HASH,
                    latestDecisionType == null ? null : UUID.randomUUID(),
                    latestDecisionType == null ? null : 1,
                    latestDecisionType, now);
        });
        lastReviewRequest.put(executionId, requestId);
    }

    private UUID ensureContextPackage(UUID taskId, UUID executionId, OffsetDateTime now) {
        return contextPackages.computeIfAbsent(executionId, key -> {
            UUID contextId = UUID.randomUUID();
            inReplica(() -> jdbc.update(
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
                    contextId, organizationId.value(), teamId.value(), workspaceId, projectId.value(),
                    taskId, executionId, UUID.randomUUID(), HASH, UUID.randomUUID(), HASH,
                    UUID.randomUUID(), HASH, HASH, UUID.randomUUID(), HASH, UUID.randomUUID(),
                    principalId, memberId.value(), memberId.value(), HASH, HASH,
                    UUID.randomUUID(), HASH, HASH, now, principalId));
            return contextId;
        });
    }

    private void seedInboxProjection() {
        OffsetDateTime now = BASE.atOffset(ZoneOffset.UTC);
        jdbc.update(
                """
                INSERT INTO crewscope.projection_definition (
                    projection_name, definition_version, projection_schema_version,
                    canonical_encoder, validator
                ) VALUES (?, 1, 1, 'inbox.canonical-v1', 'inbox.expected-v1')
                """,
                PROJECTION_NAME);
        // The deferred pointer invariant only holds when generation and pointer commit together;
        // the projector's own seed suspends the triggers the same way.
        inReplica(() -> {
            jdbc.update(
                    """
                    INSERT INTO crewscope.projection_generation (
                        organization_id, projection_name, generation, definition_version,
                        status, fencing_token, version, created_at, updated_at
                    ) VALUES (?, ?, 1, 1, 'ACTIVE', 1, 0, ?, ?)
                    """,
                    organizationId.value(), PROJECTION_NAME, now, now);
            jdbc.update(
                    """
                    INSERT INTO crewscope.projection_pointer (
                        organization_id, projection_name, active_generation, version, updated_at
                    ) VALUES (?, ?, 1, 0, ?)
                    """,
                    organizationId.value(), PROJECTION_NAME, now);
        });
    }

    private UUID seedInboxItem(
            String itemType, String priority, Instant deadline, Instant openedAt) {
        UUID inboxItemId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO crewscope.inbox_item (
                    organization_id, team_id, member_id, projection_name, generation,
                    inbox_item_id, projection_schema_version, item_type, source_type,
                    source_id, source_revision, priority, deadline, opened_at, source_status
                ) VALUES (?, ?, ?, ?, 1, ?, 1, ?, ?, ?, 0, ?, ?, ?, 'OPEN')
                """,
                organizationId.value(), teamId.value(), memberId.value(), PROJECTION_NAME,
                inboxItemId, itemType,
                "REVIEW".equals(itemType) ? "REVIEW_REQUEST" : "RESPONSIBILITY_ASSIGNMENT",
                UUID.randomUUID(), priority,
                deadline == null ? null : deadline.atOffset(ZoneOffset.UTC),
                openedAt.atOffset(ZoneOffset.UTC));
        return inboxItemId;
    }

    private void seedClosedInboxItem() {
        OffsetDateTime opened = BASE.plusSeconds(10).atOffset(ZoneOffset.UTC);
        jdbc.update(
                """
                INSERT INTO crewscope.inbox_item (
                    organization_id, team_id, member_id, projection_name, generation,
                    inbox_item_id, projection_schema_version, item_type, source_type,
                    source_id, source_revision, priority, deadline, opened_at,
                    source_status, close_reason, closed_at
                ) VALUES (?, ?, ?, ?, 1, ?, 1, 'REVIEW', 'REVIEW_REQUEST', ?, 0, 'URGENT',
                          NULL, ?, 'CLOSED', 'REVIEW_COMPLETED', ?)
                """,
                organizationId.value(), teamId.value(), memberId.value(), PROJECTION_NAME,
                UUID.randomUUID(), UUID.randomUUID(), opened, opened.plusSeconds(60));
    }

    /**
     * Suspends trigger and foreign-key enforcement for the review seed, the way the production
     * projector's own test does: the review tables guard a write path this seed does not reproduce.
     */
    private void inReplica(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            work.run();
        });
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(JdbcWorkDeskRepositoryAdapter.class)
    static class TestApplication {}
}
