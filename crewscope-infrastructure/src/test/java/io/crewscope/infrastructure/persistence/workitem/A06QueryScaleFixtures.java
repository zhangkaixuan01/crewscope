package io.crewscope.infrastructure.persistence.workitem;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Deterministic bulk fixtures for the M9b-A06 scale verification.
 *
 * <p>Rows come from direct SQL batches with a fixed value domain rather than random generators:
 * every sort key repeats at least three times in a row, thirty percent of the due dates are null,
 * and the probe row the tests hunt for sits deep in the ordering, so a paging implementation that
 * loses, duplicates or reorders rows under scale cannot pass by luck. A second organization seeds
 * the same shape as noise — authorized reads never see it.
 *
 * <p>Small covers the CI default; Full is the D05-sized tier reached with
 * {@code -Da06.scale=full}. The generator only seeds the facts the work-item read model walks
 * (items, tasks, executions, assignments); inbox and review projections keep their own
 * governance chains in their own packages' tests, which is where they are asserted.
 */
public final class A06QueryScaleFixtures {

    private static final Instant BASE = Instant.parse("2026-09-01T08:00:00Z");
    private static final String HASH = "ab".repeat(32);
    private static final String[] PRIORITIES = {"URGENT", "HIGH", "MEDIUM", "LOW"};
    private static final String[] STATUSES = {"IN_PROGRESS", "BACKLOG", "BLOCKED", "DONE"};
    private static final int BATCH = 1_000;

    /** Items per project, three tasks per item, plus the org/team/project fan-out. */
    public enum Profile {
        SMALL(200, 2, 3),
        FULL(10_000, 1, 1);

        final int itemsPerProject;
        final int teamsPerOrganization;
        final int projectsPerTeam;

        Profile(int itemsPerProject, int teamsPerOrganization, int projectsPerTeam) {
            this.itemsPerProject = itemsPerProject;
            this.teamsPerOrganization = teamsPerOrganization;
            this.projectsPerTeam = projectsPerTeam;
        }
    }

    /** Where the assertions walk: the first project of the first team, plus its probe row. */
    public record Scope(
            UUID organizationId, UUID teamId, UUID projectId, int itemCount, String probeTitle) {}

    private final JdbcTemplate jdbc;

    public A06QueryScaleFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Two organizations, two teams each, three projects each — reentrant, it truncates first. */
    public Scope seed(Profile profile) {
        jdbc.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        Scope probe = null;
        for (int organization = 0; organization < 2; organization++) {
            UUID organizationId = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO crewscope.organization (id, name, status) VALUES (?, ?, 'ACTIVE')",
                    organizationId, "A06 Scale Organization " + organization);
            for (int team = 0; team < profile.teamsPerOrganization; team++) {
                UUID teamId = UUID.randomUUID();
                jdbc.update(
                        "INSERT INTO crewscope.team (id, organization_id, name, status) "
                                + "VALUES (?, ?, ?, 'ACTIVE')",
                        teamId, organizationId, "A06 Scale Team " + organization + "-" + team);
                UUID workspaceId = UUID.randomUUID();
                jdbc.update(
                        """
                        INSERT INTO crewscope.workspace (
                            id, organization_id, team_id, workspace_type, name, status
                        ) VALUES (?, ?, ?, 'TEAM', 'A06 Scale Workspace', 'ACTIVE')
                        """,
                        workspaceId, organizationId, teamId);
                UUID ownerId = UUID.randomUUID();
                jdbc.update(
                        """
                        INSERT INTO crewscope.principal (
                            id, organization_id, principal_type, display_name, visibility, status
                        ) VALUES (?, ?, 'USER', 'A06 Scale Owner', 'ORGANIZATION', 'ACTIVE')
                        """,
                        ownerId, organizationId);
                UUID memberId = UUID.randomUUID();
                jdbc.update(
                        "INSERT INTO crewscope.team_member "
                                + "(id, organization_id, team_id, user_principal_id, status, "
                                + "join_method, joined_at) VALUES (?, ?, ?, ?, 'ACTIVE', 'IMPORT', ?)",
                        memberId, organizationId, teamId, ownerId,
                        BASE.atOffset(ZoneOffset.UTC));
                for (int project = 0; project < profile.projectsPerTeam; project++) {
                    UUID projectId = UUID.randomUUID();
                    jdbc.update(
                            """
                            INSERT INTO crewscope.work_project (
                                id, organization_id, team_id, workspace_id, project_key, name,
                                status, created_by_principal_id, updated_by_principal_id
                            ) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                            """,
                            projectId, organizationId, teamId, workspaceId,
                            "SCALE" + organization + team + project,
                            "A06 Scale Project " + organization + "-" + team + "-" + project,
                            ownerId, ownerId);
                    seedItems(profile, organizationId, teamId, workspaceId, projectId,
                            ownerId, memberId);
                    if (organization == 0 && team == 0 && project == 0) {
                        probe = new Scope(
                                organizationId, teamId, projectId,
                                profile.itemsPerProject, "Scale item 00003");
                    }
                }
            }
        }
        return probe;
    }

    /**
     * Items whose value domain is fixed: three rows share every updated/created second, every
     * third row has no due date, priorities and statuses rotate, and one in three carries an
     * ACTIVE REVIEWER assignment while another third carries a RELEASED one.
     */
    private void seedItems(
            Profile profile, UUID organizationId, UUID teamId, UUID workspaceId,
            UUID projectId, UUID ownerId, UUID memberId) {
        Timestamp now = Timestamp.from(BASE);
        Batch items = new Batch("INSERT INTO crewscope.work_item ("
                + "id, organization_id, team_id, workspace_id, project_id, item_key, item_type, "
                + "title, status, priority, due_at, source_provider, created_at, updated_at, "
                + "created_by_principal_id, updated_by_principal_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'TASK', ?, ?, ?, ?, 'CREWSCOPE', ?, ?, ?, ?)");
        Batch snapshots = new Batch("INSERT INTO crewscope.task_responsibility_snapshot ("
                + "id, organization_id, team_id, workspace_id, project_id, work_item_id, "
                + "snapshot_hash, captured_at, created_at, created_by_principal_id, "
                + "updated_at, updated_by_principal_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
        Batch tasks = new Batch("INSERT INTO crewscope.task ("
                + "id, organization_id, team_id, workspace_id, project_id, work_item_id, "
                + "source_type, source_work_item_version, responsibility_snapshot_id, status, "
                + "objective, acceptance_criteria, current_execution_id, "
                + "created_at, created_by_principal_id, updated_at, updated_by_principal_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'WORK_ITEM', 0, ?, 'CREATED', 'A06 scale objective', "
                + "'[\"Accept\"]'::JSONB, NULL, ?, ?, ?, ?)");
        // task and task_execution reference each other, so tasks are born CREATED (the one status
        // allowed to have no current execution) and gain their pointer in a closing batch.
        Batch taskUpdates = new Batch(
                "UPDATE crewscope.task SET current_execution_id = ?, status = ? WHERE id = ?");
        Batch executions = new Batch("INSERT INTO crewscope.task_execution ("
                + "id, organization_id, team_id, workspace_id, project_id, task_id, attempt, "
                + "max_attempts, priority, not_before, status, waiting_reason, waiting_since, "
                + "created_at, created_by_principal_id, updated_at, updated_by_principal_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, 1, 1, 80, ?, ?, ?, ?, ?, ?, ?, ?)");
        Batch assignments = new Batch("INSERT INTO crewscope.responsibility_assignment ("
                + "id, organization_id, team_id, workspace_id, project_id, work_item_id, role, "
                + "actor_principal_id, actor_type, actor_member_id, status, "
                + "assigned_by_principal_id, assigned_at, accepted_at, released_by_principal_id, "
                + "released_at, created_at, created_by_principal_id, updated_at, "
                + "updated_by_principal_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'USER', ?, ?, ?, ?, ?, "
                + "?, ?, ?, ?, ?, ?)");
        for (int index = 0; index < profile.itemsPerProject; index++) {
            UUID workItemId = UUID.randomUUID();
            // Three consecutive rows share one timestamp on every time axis, duplicates included.
            Timestamp stamped = Timestamp.from(BASE.plusSeconds(index / 3 * 60L));
            Timestamp dueAt = index % 10 < 3
                    ? null : Timestamp.from(BASE.plusSeconds(index / 3 * 3600L));
            items.add(new Object[] {workItemId, organizationId, teamId, workspaceId, projectId,
                    "SCALE-" + (index + 1), "Scale item %05d".formatted(index),
                    STATUSES[index % STATUSES.length], PRIORITIES[index % PRIORITIES.length],
                    dueAt, stamped, stamped, ownerId, ownerId});
            // Task one is CREATED with no execution; two and three own one execution each.
            for (int task = 0; task < 3; task++) {
                UUID taskId = UUID.randomUUID();
                UUID snapshotId = UUID.randomUUID();
                snapshots.add(new Object[] {snapshotId, organizationId, teamId, workspaceId,
                        projectId, workItemId, HASH, now, now, ownerId, now, ownerId});
                tasks.add(new Object[] {taskId, organizationId, teamId, workspaceId, projectId,
                        workItemId, snapshotId, now, ownerId, now, ownerId});
                if (task == 0) {
                    continue;
                }
                UUID executionId = UUID.randomUUID();
                executions.add(new Object[] {executionId, organizationId, teamId, workspaceId,
                        projectId, taskId, now,
                        task == 1 ? "READY" : "WAITING",
                        task == 1 ? null : "CONFIRMATION",
                        task == 1 ? null : now,
                        now, ownerId, now, ownerId});
                taskUpdates.add(new Object[] {executionId,
                        task == 1 ? "ACTIVE" : "WAITING", taskId});
            }
            if (index % 3 == 0) {
                assignments.add(new Object[] {UUID.randomUUID(), organizationId, teamId,
                        workspaceId, projectId, workItemId, "REVIEWER", ownerId, memberId,
                        "ACTIVE", ownerId, now, now, null, null, now, ownerId, now, ownerId});
            } else if (index % 3 == 1) {
                assignments.add(new Object[] {UUID.randomUUID(), organizationId, teamId,
                        workspaceId, projectId, workItemId, "OWNER", ownerId, memberId,
                        "RELEASED", ownerId, now, now, ownerId, now, now, ownerId, now, ownerId});
            }
        }
        items.flush();
        snapshots.flush();
        tasks.flush();
        executions.flush();
        taskUpdates.flush();
        assignments.flush();
    }

    /**
     * Pure accumulation: rows never execute mid-accumulation, because the seed's tables reference
     * each other — a batch that flushed while its parent rows were still pending would break the
     * foreign keys. Flush runs in dependency order, sliced into bounded statements.
     */
    private final class Batch {
        private final String sql;
        private final List<Object[]> rows = new ArrayList<>();

        Batch(String sql) {
            this.sql = sql;
        }

        void add(Object[] row) {
            rows.add(row);
        }

        void flush() {
            for (int start = 0; start < rows.size(); start += BATCH) {
                jdbc.batchUpdate(sql, rows.subList(start, Math.min(start + BATCH, rows.size())));
            }
            rows.clear();
        }
    }
}
