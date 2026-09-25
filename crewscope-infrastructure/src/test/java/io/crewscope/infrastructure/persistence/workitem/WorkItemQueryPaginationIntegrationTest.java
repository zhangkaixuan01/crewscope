package io.crewscope.infrastructure.persistence.workitem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.workitem.WorkItemBlockedReason;
import io.crewscope.application.workitem.WorkItemCursor;
import io.crewscope.application.workitem.WorkItemExecutionSummary;
import io.crewscope.application.workitem.WorkItemFilter;
import io.crewscope.application.workitem.WorkItemPage;
import io.crewscope.application.workitem.WorkItemQuery;
import io.crewscope.application.workitem.WorkItemRepository;
import io.crewscope.application.workitem.WorkItemSort;
import io.crewscope.application.workitem.WorkItemSummaryRepository;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemPriority;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.infrastructure.persistence.responsibility.ResponsibilityAssignmentEntity;
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
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the M9b-A06 stable WorkItem read model against migrated PostgreSQL: every ordering pages
 * without duplicates or gaps across equal keys, the responsibility filter runs before pagination,
 * the covering index serves the default ordering, and one summary batch answers 0/1/N-task items.
 */
@SpringBootTest(
        classes = WorkItemQueryPaginationIntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.default_schema=crewscope",
            "spring.jpa.open-in-view=false"
        })
class WorkItemQueryPaginationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-09-01T08:00:00Z");
    private static final String HASH = "ab".repeat(32);

    @Autowired
    private WorkItemRepository repository;

    @Autowired
    private WorkItemSummaryRepository summaries;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private OrganizationId organizationId;
    private TeamId teamId;
    private UUID workspaceId;
    private WorkProjectId projectId;
    private UUID principalId;
    private final Map<UUID, UUID> memberIds = new HashMap<>();
    /** The one version-1 context package each execution owns (uk_review_context_lineage_version). */
    private final Map<UUID, UUID> contextPackages = new HashMap<>();
    /** The next review revision per execution (uk_review_request_revision makes them 1, 2, 3...). */
    private final Map<UUID, Integer> reviewRevisions = new HashMap<>();
    /** The previous request of each execution's chain; revision N points at revision N-1. */
    private final Map<UUID, UUID> lastReviewRequest = new HashMap<>();

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
                organizationId.value(), "A06 Query Organization");
        jdbc.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, visibility, status
                ) VALUES (?, ?, 'USER', 'A06 Member', 'ORGANIZATION', 'ACTIVE')
                """,
                principalId, organizationId.value());
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status) "
                        + "VALUES (?, ?, 'A06 Query Team', 'ACTIVE')",
                teamId.value(), organizationId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES (?, ?, ?, 'TEAM', 'A06 Query Workspace', 'ACTIVE')
                """,
                workspaceId, organizationId.value(), teamId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.work_project (
                    id, organization_id, team_id, workspace_id, project_key, name,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, 'A06', 'A06 Query Project', ?, ?)
                """,
                projectId.value(), organizationId.value(), teamId.value(), workspaceId,
                principalId, principalId);
    }

    /**
     * Three rows share one updatedAt, five differ, one row sits in another project and one carries a
     * filtered-out status; paging at limit 2 must return exactly the six in-scope rows, newest
     * first, with no duplicate and no gap across the equal-time boundary.
     */
    @Test
    void pagesStablyAcrossEqualUpdatedAtValuesWithoutDuplicatesOrGaps() {
        UUID probe = seedWorkItem("A06-1", "BACKLOG", "MEDIUM", null, BASE.plusSeconds(10));
        seedWorkItem("A06-2", "BACKLOG", "MEDIUM", null, BASE.plusSeconds(20));
        seedWorkItem("A06-3", "BACKLOG", "MEDIUM", null, BASE.plusSeconds(20));
        seedWorkItem("A06-4", "BACKLOG", "MEDIUM", null, BASE.plusSeconds(20));
        seedWorkItem("A06-5", "BACKLOG", "MEDIUM", null, BASE.plusSeconds(30));
        seedWorkItem("A06-6", "BACKLOG", "MEDIUM", null, BASE.plusSeconds(40));
        seedWorkItem("A06-7", "READY", "MEDIUM", null, BASE.plusSeconds(50));
        seedWorkItem("A06-8", "BACKLOG", "MEDIUM", null, BASE.plusSeconds(60), otherProject());

        List<WorkItem> rows =
                pageAll(WorkItemFilter.ofStatus(WorkItemStatus.BACKLOG), WorkItemSort.UPDATED_AT);

        assertEquals(6, rows.size(), "the READY row and the other project's row are out of scope");
        assertEquals(
                probe,
                rows.get(rows.size() - 1).id().value(),
                "the oldest row lands on the last page — nothing is skipped");
        List<Instant> times = rows.stream().map(row -> row.audit().updatedAt().value()).toList();
        for (int index = 1; index < times.size(); index++) {
            assertTrue(
                    times.get(index - 1).compareTo(times.get(index)) >= 0,
                    "updatedAt must never increase across pages");
        }
        long equalBoundary =
                rows.stream()
                        .filter(row -> row.audit().updatedAt().value().equals(BASE.plusSeconds(20)))
                        .count();
        assertEquals(3, equalBoundary, "the three equal-time rows all survive pagination");
    }

    /** Equal priorities must page by the ID tie-breaker, in the frozen rank order. */
    @Test
    void ordersAndPagesByPriorityRankWithStableTies() {
        seedWorkItem("A06-10", "BACKLOG", "LOW", null, BASE);
        seedWorkItem("A06-11", "BACKLOG", "URGENT", null, BASE);
        seedWorkItem("A06-12", "BACKLOG", "URGENT", null, BASE);
        seedWorkItem("A06-13", "BACKLOG", "URGENT", null, BASE);
        seedWorkItem("A06-14", "BACKLOG", "HIGH", null, BASE);
        seedWorkItem("A06-15", "BACKLOG", "MEDIUM", null, BASE);
        seedWorkItem("A06-16", "BACKLOG", "URGENT", null, BASE, otherProject());

        List<WorkItem> rows = pageAll(WorkItemFilter.ALL, WorkItemSort.PRIORITY);

        assertEquals(6, rows.size());
        assertEquals(
                List.of(
                        WorkItemPriority.URGENT,
                        WorkItemPriority.URGENT,
                        WorkItemPriority.URGENT,
                        WorkItemPriority.HIGH,
                        WorkItemPriority.MEDIUM,
                        WorkItemPriority.LOW),
                rows.stream().map(WorkItem::priority).toList(),
                "rank descends URGENT to LOW and the other project never leaks in");
        // PostgreSQL orders the uuid type as an unsigned 128-bit value, while UUID.compareTo compares
        // its 64-bit halves as signed longs — UUIDs whose top nibble is 8..f order differently under
        // the two. The tie-breaker contract is the database's, so the expectation is built from the
        // hexadecimal form, whose lexicographic order matches the unsigned one.
        List<String> urgentIds =
                rows.subList(0, 3).stream().map(row -> row.id().value().toString()).toList();
        assertEquals(
                urgentIds.stream().sorted(java.util.Comparator.reverseOrder()).toList(),
                urgentIds,
                "equal ranks break ties by id descending");
    }

    /** DUE_AT ascends with nulls last; the traversal crosses from dated rows into the null segment. */
    @Test
    void ordersAndPagesByDueAtWithNullsLastAcrossTheSegmentBoundary() {
        seedWorkItem("A06-20", "BACKLOG", "MEDIUM", BASE.plusSeconds(10), BASE);
        seedWorkItem("A06-21", "BACKLOG", "MEDIUM", BASE.plusSeconds(20), BASE);
        seedWorkItem("A06-22", "BACKLOG", "MEDIUM", BASE.plusSeconds(20), BASE);
        seedWorkItem("A06-23", "BACKLOG", "MEDIUM", null, BASE);
        seedWorkItem("A06-24", "BACKLOG", "MEDIUM", null, BASE);
        seedWorkItem("A06-25", "BACKLOG", "MEDIUM", null, BASE);
        seedWorkItem("A06-26", "BACKLOG", "MEDIUM", BASE.plusSeconds(30), BASE, otherProject());

        List<WorkItem> rows = pageAll(WorkItemFilter.ALL, WorkItemSort.DUE_AT);

        assertEquals(6, rows.size());
        List<Optional<UtcTimestamp>> dueTimes = rows.stream().map(WorkItem::dueAt).toList();
        for (int index = 0; index < 3; index++) {
            assertTrue(dueTimes.get(index).isPresent(), "dated rows come first");
        }
        for (int index = 3; index < dueTimes.size(); index++) {
            assertTrue(dueTimes.get(index).isEmpty(), "the null segment trails every dated row");
        }
        // Unsigned order again: compare the hexadecimal form, not UUID.compareTo (see the priority
        // case above for why the two disagree on ids whose top nibble is 8..f).
        List<String> nullIds = rows.subList(3, 6).stream().map(row -> row.id().value().toString()).toList();
        assertEquals(nullIds.stream().sorted().toList(), nullIds, "null segment ascends by id");
        UtcTimestamp tiedDueTime = UtcTimestamp.from(BASE.plusSeconds(20));
        assertEquals(tiedDueTime, rows.get(1).dueAt().orElseThrow());
        assertEquals(tiedDueTime, rows.get(2).dueAt().orElseThrow());
        assertTrue(
                rows.get(1).id().value().toString().compareTo(rows.get(2).id().value().toString()) < 0,
                "equal due times break ties by id ascending");
    }

    /**
     * The responsibility filter is a WHERE predicate, not a post-page filter: only rows with an
     * ACTIVE assignment in the requested role ever reach the keyset pagination.
     */
    @Test
    void appliesTheResponsibilityRoleFilterBeforePagination() {
        UUID reviewerOne = seedWorkItem("A06-30", "BACKLOG", "MEDIUM", null, BASE.plusSeconds(10));
        UUID reviewerTwo = seedWorkItem("A06-31", "BACKLOG", "MEDIUM", null, BASE.plusSeconds(20));
        seedAssignment(reviewerOne, "REVIEWER", "ACTIVE");
        seedAssignment(reviewerTwo, "REVIEWER", "RELEASED");
        seedWorkItem("A06-32", "BACKLOG", "MEDIUM", null, BASE.plusSeconds(30));
        UUID otherProjectRow =
                seedWorkItem("A06-33", "BACKLOG", "MEDIUM", null, BASE.plusSeconds(40), otherProject());
        seedAssignment(otherProjectRow, "REVIEWER", "ACTIVE");

        WorkItemFilter filter =
                new WorkItemFilter(
                        Set.of(), Set.of(), Set.of(), Optional.of(ResponsibilityRole.REVIEWER));
        List<WorkItem> rows = pageAll(filter, WorkItemSort.UPDATED_AT);

        assertEquals(1, rows.size(), "only the ACTIVE, same-project REVIEWER row survives");
        assertEquals(reviewerOne, rows.get(0).id().value());
    }

    /**
     * The default ordering's covering index must serve the paged read; a plan that sorts the whole
     * table would degrade as the project grows.
     */
    @Test
    void servesTheDefaultOrderingFromTheProjectUpdatedIndex() {
        for (int index = 0; index < 60; index++) {
            seedWorkItem("A06-4" + index, "BACKLOG", "MEDIUM", null, BASE.plusSeconds(index));
        }
        String plan =
                String.join(
                        "\n",
                        jdbc.queryForList(
                                """
                                EXPLAIN (COSTS FALSE)
                                SELECT id FROM crewscope.work_item
                                WHERE organization_id = ? AND team_id = ? AND project_id = ?
                                ORDER BY updated_at DESC, id DESC
                                LIMIT 2
                                """,
                                String.class,
                                organizationId.value(),
                                teamId.value(),
                                projectId.value()));

        assertTrue(
                plan.contains("ix_work_item_project_updated"),
                "the default ordering must use ix_work_item_project_updated, plan was:\n" + plan);
    }

    /**
     * One summarize batch answers the three task shapes plus the derived review wait: a task-less
     * item, a single-task item with a WAITING current execution, a two-task item that requires
     * selection, and a single-task item with open review projections.
     */
    @Test
    void assemblesSummariesForZeroOneAndManyTaskItemsInOneBatch() {
        UUID noTasks = seedWorkItem("A06-50", "BACKLOG", "MEDIUM", null, BASE);
        UUID waiting = seedWorkItem("A06-51", "IN_PROGRESS", "HIGH", null, BASE);
        UUID choosing = seedWorkItem("A06-52", "IN_PROGRESS", "HIGH", null, BASE);
        UUID inReview = seedWorkItem("A06-53", "IN_REVIEW", "HIGH", null, BASE);

        UUID reviewerPrincipal = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, visibility, status
                ) VALUES (?, ?, 'USER', 'A06 Reviewer', 'ORGANIZATION', 'ACTIVE')
                """,
                reviewerPrincipal, organizationId.value());
        seedAssignment(waiting, "REVIEWER", "ACTIVE", reviewerPrincipal);

        seedTask(waiting, "WAITING", "REVIEW");
        seedTask(choosing, "ACTIVE", null);
        seedTask(choosing, "CREATED", null);
        UUID reviewTask = seedTask(inReview, "ACTIVE", null);
        seedReviewProjection(reviewTask, "OPEN", null);
        seedReviewProjection(reviewTask, "IN_PROGRESS", "COMMENTED");
        seedReviewProjection(reviewTask, "OPEN", "APPROVED");
        seedReviewProjection(reviewTask, "INVALIDATED", null);
        // A foreign project's open review must not leak into this project's counts.
        UUID foreignItem = seedWorkItem("A06-54", "IN_REVIEW", "HIGH", null, BASE, otherProject());
        UUID foreignTask = seedTask(foreignItem, "ACTIVE", null);
        seedReviewProjection(foreignTask, "OPEN", null);

        UtcTimestamp observedAt = UtcTimestamp.from(BASE.plusSeconds(600));
        var assembled =
                summaries.summarize(
                        organizationId,
                        teamId,
                        Optional.of(projectId),
                        List.of(
                                WorkItemId.from(noTasks.toString()),
                                WorkItemId.from(waiting.toString()),
                                WorkItemId.from(choosing.toString()),
                                WorkItemId.from(inReview.toString())),
                        observedAt);

        assertEquals(4, assembled.size());
        WorkItemExecutionSummary none = assembled.get(WorkItemId.from(noTasks.toString()));
        assertEquals(0, none.taskCount());
        assertFalse(none.selectionRequired());
        assertTrue(none.blockedReasons().isEmpty());

        WorkItemExecutionSummary single = assembled.get(WorkItemId.from(waiting.toString()));
        assertEquals(1, single.taskCount());
        assertEquals(1, single.activeTaskCount());
        assertTrue(single.currentExecutionId().isPresent());
        assertEquals(Optional.of(TaskExecutionStatus.WAITING), single.executionStatus());
        assertEquals(1, single.blockedReasons().size());
        assertEquals(WorkItemBlockedReason.Code.REVIEW, single.blockedReasons().get(0).code());
        assertTrue(single.blockedReasons().get(0).waitingOnPrincipalId().isPresent());

        WorkItemExecutionSummary multi = assembled.get(WorkItemId.from(choosing.toString()));
        assertEquals(2, multi.taskCount());
        assertTrue(multi.selectionRequired());
        assertTrue(multi.currentTaskId().isEmpty(), "a multi-task item never guesses a current one");
        assertEquals(
                Set.of(WorkItemBlockedReason.Code.RUNTIME),
                multi.blockedReasons().stream()
                        .map(WorkItemBlockedReason::code)
                        .collect(java.util.stream.Collectors.toSet()));
        assertTrue(
                multi.blockedReasons().get(0).waitingOnPrincipalId().isEmpty(),
                "a machine-driven wait names no person");

        WorkItemExecutionSummary review = assembled.get(WorkItemId.from(inReview.toString()));
        assertEquals(
                2,
                review.pendingReviewCount(),
                "OPEN/IN_PROGRESS without a deciding decision count; APPROVED and INVALIDATED do not");
        assertTrue(
                review.blockedReasons().stream()
                        .anyMatch(
                                reason -> reason.code() == WorkItemBlockedReason.Code.REVIEW_PENDING));
        assertEquals(observedAt, review.observedAt());
        assertEquals(0, review.workItemVersion());
    }

    /** Pages one ordering to its end through the scope-bound keyset cursor, two rows at a time. */
    private List<WorkItem> pageAll(WorkItemFilter filter, WorkItemSort sort) {
        List<WorkItem> rows = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        Optional<WorkItemCursor> cursor = Optional.empty();
        for (int page = 0; page < 20; page++) {
            WorkItemPage current =
                    repository.findPage(
                            WorkItemQuery.create(
                                    organizationId,
                                    teamId,
                                    projectId,
                                    PrincipalId.from(principalId.toString()),
                                    filter,
                                    sort,
                                    cursor,
                                    2));
            for (WorkItem row : current.items()) {
                assertTrue(seen.add(row.id().value()), "a row appeared on two pages");
                rows.add(row);
            }
            cursor = current.nextCursor();
            if (cursor.isEmpty()) {
                return rows;
            }
        }
        throw new AssertionError("pagination did not terminate");
    }

    private UUID otherProject() {
        UUID other = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO crewscope.work_project (
                    id, organization_id, team_id, workspace_id, project_key, name,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, 'A06X', 'A06 Foreign Project', ?, ?)
                """,
                other, organizationId.value(), teamId.value(), workspaceId, principalId, principalId);
        return other;
    }

    private UUID seedWorkItem(
            String itemKey, String status, String priority, Instant dueAt, Instant updatedAt) {
        return seedWorkItem(itemKey, status, priority, dueAt, updatedAt, projectId.value());
    }

    private UUID seedWorkItem(
            String itemKey,
            String status,
            String priority,
            Instant dueAt,
            Instant updatedAt,
            UUID project) {
        UUID workItemId = UUID.randomUUID();
        OffsetDateTime updated = updatedAt.atOffset(ZoneOffset.UTC);
        jdbc.update(
                """
                INSERT INTO crewscope.work_item (
                    id, organization_id, team_id, workspace_id, project_id,
                    item_key, item_type, title, status, priority, due_at,
                    source_provider, created_at, updated_at,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, 'TASK', ?, ?, ?, ?, 'CREWSCOPE', ?, ?, ?, ?)
                """,
                workItemId,
                organizationId.value(),
                teamId.value(),
                workspaceId,
                project,
                itemKey,
                "A06 " + itemKey,
                status,
                priority,
                dueAt == null ? null : dueAt.atOffset(ZoneOffset.UTC),
                updated,
                updated,
                principalId,
                principalId);
        return workItemId;
    }

    private void seedAssignment(UUID workItemId, String role, String status) {
        seedAssignment(workItemId, role, status, principalId);
    }

    /**
     * Seeds the team_member row a USER-typed actor needs (the assignment table's actor check demands
     * one), at most once per principal: several assignments share the seeding principal.
     */
    private UUID ensureMember(UUID actor) {
        return memberIds.computeIfAbsent(
                actor,
                principal -> {
                    UUID memberId = UUID.randomUUID();
                    jdbc.update(
                            "INSERT INTO crewscope.team_member "
                                    + "(id, organization_id, team_id, user_principal_id, status, join_method, joined_at) "
                                    + "VALUES (?, ?, ?, ?, 'ACTIVE', 'IMPORT', ?)",
                            memberId,
                            organizationId.value(),
                            teamId.value(),
                            principal,
                            BASE.atOffset(ZoneOffset.UTC));
                    return memberId;
                });
    }

    private void seedAssignment(UUID workItemId, String role, String status, UUID actor) {
        OffsetDateTime now = BASE.atOffset(ZoneOffset.UTC);
        jdbc.update(
                """
                INSERT INTO crewscope.responsibility_assignment (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    role, actor_principal_id, actor_type, actor_member_id, status,
                    assigned_by_principal_id, assigned_at, accepted_at,
                    released_by_principal_id, released_at,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?,
                          (SELECT project_id FROM crewscope.work_item WHERE id = ?),
                          ?, ?, ?, 'USER', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                organizationId.value(),
                teamId.value(),
                workspaceId,
                workItemId,
                workItemId,
                role,
                actor,
                ensureMember(actor),
                status,
                principalId,
                now,
                now,
                "RELEASED".equals(status) ? principalId : null,
                "RELEASED".equals(status) ? now : null,
                now,
                principalId,
                now,
                principalId);
    }

    /**
     * Inserts one task with a current execution whose status is WAITING when a wait reason is given,
     * READY otherwise. The UUID of the current execution is meaningless to every assertion; only
     * the counts and the wait facts matter.
     */
    private UUID seedTask(UUID workItemId, String status, String waitingReason) {
        UUID taskId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        OffsetDateTime now = BASE.atOffset(ZoneOffset.UTC);
        // Every child row carries the work item's own project — the seeded rows sometimes belong to
        // the foreign project, and the composite foreign keys bind the project into each edge.
        UUID project =
                jdbc.queryForObject(
                        "SELECT project_id FROM crewscope.work_item WHERE id = ?",
                        UUID.class,
                        workItemId);
        jdbc.update(
                """
                INSERT INTO crewscope.task_responsibility_snapshot (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    snapshot_hash, captured_at, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                snapshotId,
                organizationId.value(),
                teamId.value(),
                workspaceId,
                project,
                workItemId,
                HASH,
                now,
                now,
                principalId,
                now,
                principalId);
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
                taskId,
                organizationId.value(),
                teamId.value(),
                workspaceId,
                project,
                workItemId,
                snapshotId,
                now,
                principalId,
                now,
                principalId);
        jdbc.update(
                """
                INSERT INTO crewscope.task_execution (
                    id, organization_id, team_id, workspace_id, project_id, task_id,
                    attempt, max_attempts, priority, not_before, status,
                    waiting_reason, waiting_since,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, 1, 1, 80, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                executionId,
                organizationId.value(),
                teamId.value(),
                workspaceId,
                project,
                taskId,
                now,
                waitingReason == null ? "READY" : "WAITING",
                waitingReason,
                waitingReason == null ? null : now,
                now,
                principalId,
                now,
                principalId);
        // task and task_execution reference each other, so the task is born CREATED (the one status
        // allowed to have no current execution) and only then gains its pointer and final status.
        if (!"CREATED".equals(status)) {
            jdbc.update(
                    "UPDATE crewscope.task SET current_execution_id = ?, status = ? WHERE id = ?",
                    executionId,
                    status,
                    taskId);
        }
        return taskId;
    }

    /**
     * Seeds one review request projection row through the owning tables, so the pending-review count
     * exercises the same chain production writes.
     */
    private void seedReviewProjection(UUID taskId, String requestStatus, String latestDecisionType) {
        OffsetDateTime now = BASE.atOffset(ZoneOffset.UTC);
        UUID requestId = UUID.randomUUID();
        UUID executionId =
                jdbc.queryForObject(
                        "SELECT current_execution_id FROM crewscope.task WHERE id = ?",
                        UUID.class,
                        taskId);
        UUID project =
                jdbc.queryForObject(
                        "SELECT wi.project_id FROM crewscope.work_item wi "
                                + "JOIN crewscope.task t ON t.work_item_id = wi.id WHERE t.id = ?",
                        UUID.class,
                        taskId);
        UUID contextId = ensureContextPackage(taskId, executionId, project, now);
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
                    requestId,
                    organizationId.value(),
                    teamId.value(),
                    workspaceId,
                    project,
                    taskId,
                    executionId,
                    revision,
                    predecessor,
                    UUID.randomUUID(),
                    HASH,
                    contextId,
                    HASH,
                    HASH,
                    requestStatus,
                    "INVALIDATED".equals(requestStatus) ? "SUBJECT_CHANGED" : null,
                    now,
                    principalId,
                    now,
                    principalId);
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
                    requestId,
                    organizationId.value(),
                    teamId.value(),
                    workspaceId,
                    project,
                    taskId,
                    executionId,
                    revision,
                    requestStatus,
                    "INVALIDATED".equals(requestStatus) ? "SUBJECT_CHANGED" : null,
                    HASH,
                    latestDecisionType == null ? null : UUID.randomUUID(),
                    latestDecisionType == null ? null : 1,
                    latestDecisionType,
                    now);
        });
        lastReviewRequest.put(executionId, requestId);
    }

    /** Every review of one execution reuses its single version-1 package, as production writes it. */
    private UUID ensureContextPackage(UUID taskId, UUID executionId, UUID project, OffsetDateTime now) {
        return contextPackages.computeIfAbsent(
                executionId,
                key -> {
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
                            contextId,
                            organizationId.value(),
                            teamId.value(),
                            workspaceId,
                            project,
                            taskId,
                            executionId,
                            UUID.randomUUID(),
                            HASH,
                            UUID.randomUUID(),
                            HASH,
                            UUID.randomUUID(),
                            HASH,
                            HASH,
                            UUID.randomUUID(),
                            HASH,
                            UUID.randomUUID(),
                            principalId,
                            ensureMember(principalId),
                            ensureMember(principalId),
                            HASH,
                            HASH,
                            UUID.randomUUID(),
                            HASH,
                            HASH,
                            now,
                            principalId));
                    return contextId;
                });
    }

    /**
     * Runs projection writes with trigger and foreign-key enforcement suspended, the way the
     * production projector's own test does: the review tables guard a write path this seed does not
     * reproduce, and the summary reads only the projection rows, never their owners.
     */
    private void inReplica(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            work.run();
        });
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {WorkItemEntity.class, ResponsibilityAssignmentEntity.class})
    @Import({
        JpaWorkItemRepositoryAdapter.class,
        WorkItemEntityMapper.class,
        JdbcWorkItemSummaryRepositoryAdapter.class
    })
    static class TestApplication {}
}
