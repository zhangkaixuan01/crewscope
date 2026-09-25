package io.crewscope.infrastructure.persistence.workitem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.workitem.WorkItemExecutionSummary;
import io.crewscope.application.workitem.WorkItemSummaryRepository;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.infrastructure.persistence.responsibility.ResponsibilityAssignmentEntity;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the M9b-A05 resultSummary production rule against migrated PostgreSQL: only a
 * single-task WorkItem whose current execution is COMPLETED delivers facts — the coding attempt
 * states its file changes and test verdict with a stable coding-attempt reference, a non-coding
 * completion carries only the runtime-artifact pointer, and everything else stays honestly empty.
 */
@SpringBootTest(
        classes = WorkItemSummaryDeliveryIntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.default_schema=crewscope",
            "spring.jpa.open-in-view=false"
        })
class WorkItemSummaryDeliveryIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-09-20T08:00:00Z");
    private static final String HASH64 = "ab".repeat(32);
    private static final String FINAL_HASH = "cd".repeat(32);
    private static final String COMMIT40 = "ef".repeat(20);

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
                organizationId.value(), "A05 Delivery Organization");
        jdbc.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, visibility, status
                ) VALUES (?, ?, 'USER', 'A05 Member', 'ORGANIZATION', 'ACTIVE')
                """,
                principalId, organizationId.value());
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status) "
                        + "VALUES (?, ?, 'A05 Delivery Team', 'ACTIVE')",
                teamId.value(), organizationId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES (?, ?, ?, 'TEAM', 'A05 Delivery Workspace', 'ACTIVE')
                """,
                workspaceId, organizationId.value(), teamId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.work_project (
                    id, organization_id, team_id, workspace_id, project_key, name,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, 'A05', 'A05 Delivery Project', ?, ?)
                """,
                projectId.value(), organizationId.value(), teamId.value(), workspaceId,
                principalId, principalId);
    }

    @Test
    void completedCodingAttemptDeliversFileFactsAndAStableReference() {
        UUID passing = seedWorkItem("A05-10");
        UUID failing = seedWorkItem("A05-11");
        UUID passingExecution = seedSingleCompletedTask(passing);
        UUID failingExecution = seedSingleCompletedTask(failing);
        seedCodingDelivery(passingExecution, 3, 10, 2, true, false);
        seedCodingDelivery(failingExecution, 5, 7, 1, false, true);

        Map<WorkItemId, WorkItemExecutionSummary> assembled = summarize(passing, failing);

        WorkItemExecutionSummary pass = assembled.get(WorkItemId.from(passing.toString()));
        assertEquals(
                Optional.of("已交付 3 个文件变更（+10/−2），测试通过"),
                pass.resultSummary());
        assertEquals(
                Optional.of("coding-attempt:" + passingExecution + "@" + FINAL_HASH),
                pass.resultSourceReference());

        // The latest evidence sequence decides: the earlier pass does not overwrite the final failure.
        WorkItemExecutionSummary fail = assembled.get(WorkItemId.from(failing.toString()));
        assertEquals(
                Optional.of("已交付 5 个文件变更（+7/−1），测试未通过"),
                fail.resultSummary());
        assertEquals(
                Optional.of("coding-attempt:" + failingExecution + "@" + FINAL_HASH),
                fail.resultSourceReference());
    }

    @Test
    void completedCodingAttemptWithoutEvidenceClaimsNoVerdict() {
        UUID item = seedWorkItem("A05-12");
        UUID execution = seedSingleCompletedTask(item);
        seedCodingDelivery(execution, 1, 4, 0, null, null);

        WorkItemExecutionSummary summary = summarize(item)
                .get(WorkItemId.from(item.toString()));

        assertEquals(Optional.of("已交付 1 个文件变更（+4/−0）"), summary.resultSummary());
        assertTrue(summary.resultSourceReference().isPresent());
    }

    @Test
    void nonCodingCompletionCarriesOnlyTheRuntimeArtifactReference() {
        UUID item = seedWorkItem("A05-13");
        UUID execution = seedSingleCompletedTask(item);
        UUID artifact = UUID.randomUUID();
        seedAgentRun(execution, 1, null);
        seedAgentRun(execution, 2, artifact);

        WorkItemExecutionSummary summary = summarize(item)
                .get(WorkItemId.from(item.toString()));

        assertTrue(summary.resultSummary().isEmpty(),
                "the model output is not parsed into a list line — F03 owns that presentation");
        assertEquals(Optional.of("runtime-artifact:" + artifact), summary.resultSourceReference());
    }

    @Test
    void waitingMultiTaskAndUndeliveredCompletionsStayEmpty() {
        UUID waiting = seedWorkItem("A05-14");
        seedSingleTask(waiting, "WAITING");
        UUID undelivered = seedWorkItem("A05-15");
        seedSingleTask(undelivered, "COMPLETED");
        UUID choosing = seedWorkItem("A05-16");
        seedSingleTask(choosing, "COMPLETED");
        seedSingleTask(choosing, "COMPLETED");
        // A delivery under a WAITING current execution must not surface either.
        UUID stale = seedWorkItem("A05-17");
        UUID staleExecution = seedSingleTask(stale, "WAITING");
        seedCodingDelivery(staleExecution, 9, 99, 9, true, null);

        Map<WorkItemId, WorkItemExecutionSummary> assembled =
                summarize(waiting, undelivered, choosing, stale);

        for (UUID item : List.of(waiting, undelivered, choosing, stale)) {
            WorkItemExecutionSummary summary = assembled.get(WorkItemId.from(item.toString()));
            assertTrue(summary.resultSummary().isEmpty(), item + " must not claim a delivery");
            assertTrue(summary.resultSourceReference().isEmpty(), item + " must not reference one");
        }
    }

    private Map<WorkItemId, WorkItemExecutionSummary> summarize(UUID... itemIds) {
        return summaries.summarize(
                organizationId,
                teamId,
                Optional.of(projectId),
                java.util.Arrays.stream(itemIds)
                        .map(value -> WorkItemId.from(value.toString()))
                        .toList(),
                UtcTimestamp.from(BASE.plusSeconds(600)));
    }

    private UUID seedWorkItem(String itemKey) {
        UUID workItemId = UUID.randomUUID();
        OffsetDateTime now = BASE.atOffset(ZoneOffset.UTC);
        jdbc.update(
                """
                INSERT INTO crewscope.work_item (
                    id, organization_id, team_id, workspace_id, project_id,
                    item_key, item_type, title, status, priority,
                    source_provider, created_at, updated_at,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, 'TASK', ?, 'IN_PROGRESS', 'HIGH',
                          'CREWSCOPE', ?, ?, ?, ?)
                """,
                workItemId, organizationId.value(), teamId.value(), workspaceId,
                projectId.value(), itemKey, "A05 " + itemKey, now, now, principalId, principalId);
        return workItemId;
    }

    /** Creates one task whose current execution is seeded in the requested terminal/waiting state. */
    private UUID seedSingleTask(UUID workItemId, String executionStatus) {
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
                snapshotId, organizationId.value(), teamId.value(), workspaceId,
                projectId.value(), workItemId, HASH64, now, now, principalId, now, principalId);
        jdbc.update(
                """
                INSERT INTO crewscope.task (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    source_type, source_work_item_version, responsibility_snapshot_id,
                    status, objective, acceptance_criteria,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, 'WORK_ITEM', 0, ?, 'CREATED', 'A05 objective',
                          '["Accept"]'::JSONB, ?, ?, ?, ?)
                """,
                taskId, organizationId.value(), teamId.value(), workspaceId,
                projectId.value(), workItemId, snapshotId, now, principalId, now, principalId);
        // The schema's shape checks bind each status to its decision facts: a terminal execution
        // names its decider, a WAITING one names its reason.
        boolean terminal = "COMPLETED".equals(executionStatus);
        jdbc.update(
                """
                INSERT INTO crewscope.task_execution (
                    id, organization_id, team_id, workspace_id, project_id, task_id,
                    attempt, max_attempts, priority, not_before, status,
                    waiting_reason, waiting_since,
                    terminal_decided_by_principal_id, terminal_decided_at,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, 1, 1, 80, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                executionId, organizationId.value(), teamId.value(), workspaceId,
                projectId.value(), taskId, now, executionStatus,
                terminal ? null : "MANUAL",
                terminal ? null : now,
                terminal ? principalId : null,
                terminal ? now : null,
                now, principalId, now, principalId);
        jdbc.update(
                "UPDATE crewscope.task SET current_execution_id = ?, status = 'ACTIVE' WHERE id = ?",
                executionId, taskId);
        return executionId;
    }

    private UUID seedSingleCompletedTask(UUID workItemId) {
        return seedSingleTask(workItemId, "COMPLETED");
    }

    /**
     * Seeds the delivery chain this summary actually reads. The workspace/diff/evidence rows guard
     * a write path only the production coding engine reproduces (lease, policy and target closure),
     * so the seed suspends trigger and foreign-key enforcement — the read joins real columns. A
     * null verdict slot means that evidence row does not exist; the schema records failure
     * classifications only, so a passing row carries none.
     */
    private void seedCodingDelivery(
            UUID executionId,
            int fileCount,
            long additions,
            long deletions,
            Boolean latestPassed,
            Boolean earlierPassed) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            UUID taskId = jdbc.queryForObject(
                    "SELECT task_id FROM crewscope.task_execution WHERE id = ?", UUID.class,
                    executionId);
            UUID workspaceId_ = UUID.randomUUID();
            // The key check binds the branch to the attempt's UUID shape and the archive ref
            // to the workspace key itself, so the three identities derive from one source.
            String workspaceKey = "ws-" + executionId + "-a1";
            jdbc.update(
                    """
                    INSERT INTO crewscope.execution_workspace (
                        id, organization_id, team_id, workspace_id, project_id,
                        task_id, task_execution_id, attempt,
                        coding_target_snapshot_id, coding_target_revision, coding_target_hash,
                        repository_binding_id, repository_binding_version, repository_key,
                        baseline_commit, workspace_key, managed_branch, archive_reference,
                        runtime_environment, runtime_id, worker_id, execution_lease_id,
                        fencing_token, status, completion_reason, retain_until,
                        workspace_fingerprint, created_at,
                        created_by_principal_id, updated_by_principal_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, 1, ?, ?, 1, 'a05-repo', ?, ?, ?,
                              ?, 'test', ?, ?, ?, 1, 'COMPLETED', 'SUCCEEDED', ?,
                              ?, ?, ?, ?)
                    """,
                    workspaceId_, organizationId.value(), teamId.value(), workspaceId,
                    projectId.value(), taskId, executionId, UUID.randomUUID(), HASH64,
                    UUID.randomUUID(), COMMIT40, workspaceKey,
                    "crewscope/tasks/" + executionId + "/attempt-1",
                    "refs/crewscope/archives/" + workspaceKey,
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    BASE.plusSeconds(3600).atOffset(ZoneOffset.UTC),
                    HASH64, BASE.atOffset(ZoneOffset.UTC), principalId, principalId);
            jdbc.update(
                    """
                    INSERT INTO crewscope.diff_artifact (
                        id, organization_id, team_id, workspace_id, project_id,
                        task_id, task_execution_id, attempt, execution_workspace_id,
                        workspace_fingerprint, coding_target_snapshot_id,
                        coding_target_revision, coding_target_hash,
                        baseline_commit, delivery_commit, diff_generation, manifest_hash,
                        file_count, additions, deletions,
                        patch_artifact_id, patch_size_bytes, patch_sha256, final_hash,
                        created_at, created_by_principal_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, 1, ?, ?, ?, 1, ?, ?, ?, ?,
                              ?, 128, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(), organizationId.value(), teamId.value(), workspaceId,
                    projectId.value(), taskId, executionId, workspaceId_, HASH64,
                    UUID.randomUUID(), HASH64, COMMIT40, COMMIT40, HASH64,
                    fileCount, additions, deletions, UUID.randomUUID(), HASH64, FINAL_HASH,
                    BASE.atOffset(ZoneOffset.UTC), principalId);
            long sequence = 1;
            if (earlierPassed != null) {
                insertTestEvidence(executionId, taskId, workspaceId_, sequence++,
                        failureClassification(earlierPassed));
            }
            if (latestPassed != null) {
                insertTestEvidence(executionId, taskId, workspaceId_, sequence,
                        failureClassification(latestPassed));
            }
        });
    }

    private static String failureClassification(Boolean passed) {
        return passed ? null : "TESTS_FAILED";
    }

    /**
     * Seeds one evidence row. The schema records failure classifications only — a null means the
     * run passed, which is exactly the passed-derivation SQL 5 reads back.
     */
    private void insertTestEvidence(
            UUID executionId, UUID taskId, UUID executionWorkspaceId, long sequence,
            String failureClassification) {
        jdbc.update(
                """
                INSERT INTO crewscope.test_evidence (
                    id, organization_id, team_id, workspace_id, project_id,
                    task_id, task_execution_id, attempt, execution_workspace_id,
                    workspace_fingerprint, coding_target_snapshot_id,
                    coding_target_revision, coding_target_hash,
                    diff_generation, diff_manifest_hash, evidence_sequence,
                    workspace_policy_id, workspace_policy_hash,
                    test_total, test_passed, test_failed, test_errors, test_skipped,
                    summary, failure_classification, evidence_hash,
                    created_at, created_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, 1, ?, 1, ?, ?, ?, ?, 4, 4, 0, 0, 0,
                          '4 tests', ?, ?, ?, ?)
                """,
                UUID.randomUUID(), organizationId.value(), teamId.value(), workspaceId,
                projectId.value(), taskId, executionId, executionWorkspaceId, HASH64,
                UUID.randomUUID(), HASH64, HASH64, sequence, UUID.randomUUID(), HASH64,
                failureClassification, HASH64, BASE.atOffset(ZoneOffset.UTC), principalId);
    }

    /** The Agent run rows keep their production owner chain un-seeded for the same reason. */
    private void seedAgentRun(UUID executionId, long runSequence, UUID terminalArtifactId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update(
                    """
                    INSERT INTO crewscope.agent_run (
                        id, organization_id, team_id, workspace_id, project_id,
                        task_id, task_execution_id, runtime_session_id,
                        agent_principal_id, agent_profile_id, agent_profile_version,
                        run_sequence, status, terminal_result_artifact_id, terminal_at,
                        created_by_principal_id, updated_by_principal_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 'COMPLETED', ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(), organizationId.value(), teamId.value(), workspaceId,
                    projectId.value(),
                    jdbc.queryForObject(
                            "SELECT task_id FROM crewscope.task_execution WHERE id = ?",
                            UUID.class, executionId),
                    executionId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    runSequence, terminalArtifactId,
                    // A COMPLETED run always names its terminal instant — only the artifact is optional.
                    BASE.plusSeconds(60).atOffset(ZoneOffset.UTC),
                    principalId, principalId);
        });
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {ResponsibilityAssignmentEntity.class})
    @Import(JdbcWorkItemSummaryRepositoryAdapter.class)
    static class TestApplication {}
}
