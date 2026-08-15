package io.crewscope.infrastructure.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Verifies the V10 durable Task Runtime schema and its database-level safety verdicts. */
class V10DurableTaskRuntimeMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final MigrationVersion VERSION_9 = MigrationVersion.fromVersion("9");
    private static final MigrationVersion VERSION_10 = MigrationVersion.fromVersion("10");
    private static final MigrationVersion VERSION_11 = MigrationVersion.fromVersion("11");
    private static final MigrationVersion VERSION_12 = MigrationVersion.fromVersion("12");
    private static final MigrationVersion VERSION_13 = MigrationVersion.fromVersion("13");
    private static final String ALTERNATE_SCHEMA = "v10_probe";
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final String HASH_C = "c".repeat(64);
    private static final String HASH_D = "d".repeat(64);

    @BeforeEach
    void resetSchemas() throws SQLException {
        try (Connection connection = openConnection(POSTGRES.getJdbcUrl());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS v10_probe CASCADE");
            statement.execute("CREATE SCHEMA v10_probe");
        }
    }

    @Test
    void createsDurableRuntimeTablesAuditColumnsConstraintsAndIndexes() throws SQLException {
        Flyway flyway = flyway(POSTGRES.getJdbcUrl(), VERSION_10);
        flyway.migrate();

        assertEquals("10", flyway.info().current().getVersion().getVersion());
        Set<String> tables = queryStrings(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'crewscope'");
        assertTrue(tables.containsAll(Set.of(
                "task_responsibility_snapshot",
                "task_responsibility_snapshot_entry",
                "task",
                "conversation_task_link",
                "task_execution",
                "policy_snapshot",
                "safety_enforcement_overlay",
                "plan_version",
                "plan_step",
                "plan_todo_summary",
                "step_execution",
                "execution_runtime",
                "runtime_worker",
                "task_credential_grant",
                "agent_run",
                "agent_run_segment",
                "agent_interrupt",
                "runtime_artifact",
                "agent_state_snapshot")));
        assertFalse(tables.contains("step_execution_lease"));

        for (String table : Set.of(
                "task_responsibility_snapshot",
                "task",
                "conversation_task_link",
                "task_execution",
                "policy_snapshot",
                "safety_enforcement_overlay",
                "plan_version",
                "step_execution",
                "execution_runtime",
                "runtime_worker",
                "task_credential_grant",
                "agent_run",
                "agent_interrupt",
                "runtime_artifact",
                "agent_state_snapshot")) {
            assertTrue(columns(table).containsAll(Set.of(
                    "version",
                    "created_at",
                    "created_by_principal_id",
                    "updated_at",
                    "updated_by_principal_id")), table);
        }
        assertTrue(columns("execution_lease").containsAll(Set.of(
                "lease_version", "status", "phase", "runtime_id", "worker_id",
                "claim_token_hash", "fencing_token", "acquired_at",
                "last_heartbeat_at", "expires_at")));
        assertTrue(columns("agent_runtime_session").containsAll(Set.of(
                "session_purpose",
                "project_id",
                "task_id",
                "task_execution_id",
                "step_execution_id",
                "agent_principal_id",
                "agent_principal_type",
                "agent_profile_type")));
        assertFalse(columns("execution_lease").contains("claim_token"));
        assertFalse(columns("task_credential_grant").contains("jti"));

        Set<String> indexes = queryStrings(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'crewscope'");
        assertTrue(indexes.containsAll(Set.of(
                "ix_task_execution_ready_queue",
                "ux_task_execution_active_task",
                "ix_execution_lease_expiry",
                "ux_execution_lease_active",
                "ux_task_credential_grant_active_execution",
                "ux_agent_runtime_session_active_task_binding",
                "ux_agent_run_active_session",
                "ux_agent_interrupt_pending_run",
                "ux_agent_state_snapshot_current_session")));
    }

    @Test
    void upgradesPopulatedV9AndPreservesPersonalSessionFacts() throws SQLException {
        Flyway versionNine = flyway(POSTGRES.getJdbcUrl(), VERSION_9);
        versionNine.migrate();
        Fixture fixture = seedFixture("UPG", true);

        Flyway versionTen = flyway(POSTGRES.getJdbcUrl(), VERSION_10);
        assertEquals(1, versionTen.migrate().migrationsExecuted);

        assertEquals("10", versionTen.info().current().getVersion().getVersion());
        assertEquals(1, queryInt(
                "SELECT COUNT(*) FROM crewscope.work_item WHERE id = ?", fixture.workItemId()));
        assertEquals(1, queryInt(
                """
                SELECT COUNT(*)
                FROM crewscope.agent_runtime_session
                WHERE id = ?
                  AND session_purpose = 'PERSONAL'
                  AND agent_principal_id = personal_agent_principal_id
                  AND agent_principal_type = 'PERSONAL_AGENT'
                  AND agent_profile_type = 'PERSONAL'
                  AND task_id IS NULL
                """,
                fixture.personalSessionId()));
    }

    @Test
    void upgradesPopulatedV10AndBackfillsImmutableTaskBriefs() throws SQLException {
        flyway(POSTGRES.getJdbcUrl(), VERSION_10).migrate();
        Fixture fixture = seedFixture("BRF", false);
        execute(
                "UPDATE crewscope.work_item SET title = ?, description = ? WHERE id = ?",
                "Pinned objective",
                "First criterion",
                fixture.workItemId());
        TaskFacts facts = seedTaskFacts(fixture);

        Flyway versionEleven = flyway(POSTGRES.getJdbcUrl(), VERSION_11);
        assertEquals(1, versionEleven.migrate().migrationsExecuted);

        assertEquals("11", versionEleven.info().current().getVersion().getVersion());
        assertTrue(columns("task").containsAll(Set.of("objective", "acceptance_criteria")));
        assertEquals(1, queryInt(
                """
                SELECT COUNT(*)
                FROM crewscope.task
                WHERE id = ?
                  AND objective = 'Pinned objective'
                  AND acceptance_criteria = '["First criterion"]'::jsonb
                """,
                facts.taskId()));
        assertSqlState(
                "23502",
                "UPDATE crewscope.task SET objective = NULL WHERE id = ?",
                facts.taskId());
    }

    @Test
    void upgradesV11WithTheCompleteBoundedTaskQueryIndexSet() throws SQLException {
        flyway(POSTGRES.getJdbcUrl(), VERSION_11).migrate();

        Flyway versionTwelve = flyway(POSTGRES.getJdbcUrl(), VERSION_12);
        assertEquals(1, versionTwelve.migrate().migrationsExecuted);

        assertEquals("12", versionTwelve.info().current().getVersion().getVersion());
        Set<String> indexes = queryStrings(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'crewscope'");
        assertTrue(indexes.containsAll(Set.of(
                "ix_task_team_updated",
                "ix_task_team_project_updated",
                "ix_task_team_status_updated",
                "ix_task_team_project_status_updated",
                "ix_agent_interrupt_execution_created",
                "ix_agent_state_snapshot_execution_sequence",
                "ix_execution_lease_execution_acquired")));
    }

    @Test
    void upgradesV12WithTaskEventStreamAndBackfillsOnlyKnownTaskFacts() throws SQLException {
        flyway(POSTGRES.getJdbcUrl(), VERSION_10).migrate();
        Fixture fixture = seedFixture("EVT", false);
        TaskFacts facts = seedTaskFacts(fixture);
        flyway(POSTGRES.getJdbcUrl(), VERSION_12).migrate();

        UUID taskEventId = UUID.randomUUID();
        insertDomainEvent(
                fixture,
                taskEventId,
                "WORKER_TASK_PROGRESS_ACCEPTED",
                "TASK_EXECUTION",
                facts.executionId(),
                3,
                """
                {"taskExecutionId":"%s","attempt":1,"executionLeaseId":"%s",
                 "operation":"PROGRESS","taskExecutionVersion":3,"leaseVersion":null,
                 "safeSummary":"working","progressPercent":40,
                 "failureClass":null,"failureCode":null}
                """.formatted(facts.executionId(), UUID.randomUUID()));
        insertDomainEvent(
                fixture,
                UUID.randomUUID(),
                "WORKER_TASK_FUTURE_ACCEPTED",
                "TASK",
                facts.taskId(),
                0,
                "{\"taskId\":\"" + facts.taskId() + "\"}");

        Flyway versionThirteen = flyway(POSTGRES.getJdbcUrl(), VERSION_13);
        assertEquals(1, versionThirteen.migrate().migrationsExecuted);

        assertEquals("13", versionThirteen.info().current().getVersion().getVersion());
        assertEquals(1, queryInt(
                "SELECT COUNT(*) FROM crewscope.task_event WHERE task_id = ?",
                facts.taskId()));
        assertEquals(1, queryInt(
                """
                SELECT COUNT(*) FROM crewscope.task_event
                WHERE task_id = ? AND task_execution_id = ? AND domain_event_id = ?
                  AND event_id = md5('CREWSCOPE:REALTIME:TASK:' || ?::TEXT)::UUID
                """,
                facts.taskId(), facts.executionId(), taskEventId, taskEventId));
        assertTrue(queryStrings(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'crewscope'")
                .contains("ix_task_event_stream_position"));
    }

    @Test
    void migratesV10IntoCrewscopeWithNonDefaultSearchPath() throws SQLException {
        String jdbcUrl = POSTGRES.getJdbcUrl()
                + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=" + ALTERNATE_SCHEMA;

        flyway(jdbcUrl, VERSION_10).migrate();

        assertEquals(1, queryInt(
                """
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'crewscope' AND table_name = 'task_execution'
                """));
        assertEquals(0, queryInt(
                """
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'v10_probe' AND table_name = 'task_execution'
                """));
    }

    @Test
    void enforcesTaskScopeAttemptStateAndPlanPolicyParentLineage() throws SQLException {
        flyway(POSTGRES.getJdbcUrl(), VERSION_10).migrate();
        Fixture fixture = seedFixture("ONE", false);
        TaskFacts facts = seedTaskFacts(fixture);
        Fixture foreign = seedFixture("TWO", false);

        assertSqlState(
                "23503",
                taskInsertSql(),
                UUID.randomUUID(),
                foreign.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                fixture.projectId(),
                fixture.workItemId(),
                facts.responsibilitySnapshotId(),
                fixture.userPrincipalId(),
                fixture.userPrincipalId());

        assertSqlState(
                "23505",
                taskExecutionInsertSql(),
                UUID.randomUUID(),
                fixture.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                fixture.projectId(),
                facts.taskId(),
                1,
                null,
                fixture.userPrincipalId(),
                fixture.userPrincipalId());

        assertSqlState(
                "23514",
                "UPDATE crewscope.task_execution SET status = 'UNKNOWN' WHERE id = ?",
                facts.executionId());
        assertSqlState(
                "23514",
                "UPDATE crewscope.task_execution SET status = 'CLAIMED' WHERE id = ?",
                facts.executionId());

        TaskFacts foreignFacts = seedTaskFacts(fixture);
        UUID secondExecutionId = foreignFacts.executionId();
        UUID foreignPolicyId = insertPolicy(
                fixture, foreignFacts.taskId(), secondExecutionId, 2,
                foreignFacts.policyId(), "MANUAL_REAUTHORIZATION");
        assertSqlState("23503", () -> execute(
                policyInsertSql(), UUID.randomUUID(), fixture, facts.taskId(),
                facts.executionId(), 2L, foreignPolicyId, "MANUAL_REAUTHORIZATION"));

        UUID foreignPlanId = foreignFacts.planId();
        assertSqlState("23503", () -> execute(
                planInsertSql(), UUID.randomUUID(), fixture, facts.taskId(),
                facts.executionId(), facts.policyId(), facts.overlayId(),
                2L, foreignPlanId, "MANUAL_REVISION"));
    }

    @Test
    void enforcesRuntimeLeaseCredentialAndSessionVerdicts() throws SQLException {
        flyway(POSTGRES.getJdbcUrl(), VERSION_10).migrate();
        Fixture fixture = seedFixture("RUN", false);
        TaskFacts facts = seedTaskFacts(fixture);
        RuntimeFacts runtime = seedRuntimeFacts(fixture, facts);

        assertSqlState(
                "23514",
                runtimeWorkerInsertSql(),
                UUID.randomUUID(),
                fixture.organizationId(),
                runtime.runtimeId(),
                "invalid-capacity",
                1,
                2,
                fixture.userPrincipalId(),
                fixture.userPrincipalId());

        assertSqlState("23505", () -> execute(
                leaseInsertSql(), UUID.randomUUID(), fixture, facts, runtime, HASH_B, 2L));

        UUID firstGrant = UUID.randomUUID();
        execute(credentialGrantInsertSql(), firstGrant, fixture, facts, runtime, HASH_C);
        assertSqlState("23505", () -> execute(
                credentialGrantInsertSql(), UUID.randomUUID(), fixture, facts, runtime, HASH_D));

        assertSqlState("23514", () -> execute(
                taskSessionInsertSql(), UUID.randomUUID(), fixture, facts, "STEP", null));

        UUID taskSession = insertTaskSession(fixture, facts, "TASK", null);
        assertEquals(1, queryInt(
                "SELECT COUNT(*) FROM crewscope.agent_runtime_session WHERE id = ? AND session_purpose = 'TASK'",
                taskSession));
    }

    @Test
    void enforcesRunSegmentInterruptAndSnapshotPartialUniqueness() throws SQLException {
        flyway(POSTGRES.getJdbcUrl(), VERSION_10).migrate();
        Fixture fixture = seedFixture("AGT", false);
        TaskFacts facts = seedTaskFacts(fixture);
        UUID sessionId = insertTaskSession(fixture, facts, "TASK", null);
        UUID runId = insertAgentRun(fixture, facts, sessionId, 1);
        insertSegment(runId, 1);

        assertSqlState("23505", () -> execute(
                agentRunInsertSql(), UUID.randomUUID(), fixture, facts, sessionId, 2L));
        assertSqlState(
                "23505",
                """
                INSERT INTO crewscope.agent_run_segment (
                    agent_run_id, sequence, kind, status, started_at
                ) VALUES (?, 1, 'INVOKE', 'ACTIVE', CURRENT_TIMESTAMP)
                """,
                runId);

        insertInterrupt(fixture, facts, runId, HASH_A);
        assertSqlState("23505", () -> execute(
                interruptInsertSql(), UUID.randomUUID(), fixture, facts, runId, HASH_B));

        UUID artifactOne = insertSnapshotArtifact(fixture, facts, runId, HASH_C);
        insertSnapshot(fixture, facts, runId, sessionId, artifactOne, HASH_C, 1);
        UUID artifactTwo = insertSnapshotArtifact(fixture, facts, runId, HASH_D);
        assertSqlState("23505", () -> execute(
                snapshotInsertSql(), UUID.randomUUID(), fixture, facts, runId,
                sessionId, artifactTwo, HASH_D, 2L));
    }

    private static Fixture seedFixture(String projectKey, boolean personalSession)
            throws SQLException {
        Fixture fixture = new Fixture(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                personalSession ? UUID.randomUUID() : null);
        execute(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Org', 'ACTIVE')",
                fixture.organizationId());
        execute(
                "INSERT INTO crewscope.team (id, organization_id, name, status) VALUES (?, ?, ?, 'ACTIVE')",
                fixture.teamId(), fixture.organizationId(), "Team " + projectKey);
        execute(
                """
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES (?, ?, ?, 'TEAM', ?, 'ACTIVE')
                """,
                fixture.workspaceId(), fixture.organizationId(), fixture.teamId(),
                "Workspace " + projectKey);
        execute(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, status
                ) VALUES (?, ?, 'USER', 'Owner', 'ACTIVE')
                """,
                fixture.userPrincipalId(), fixture.organizationId());
        execute(
                """
                INSERT INTO crewscope.team_member (
                    id, organization_id, team_id, user_principal_id,
                    status, join_method, joined_at
                ) VALUES (?, ?, ?, ?, 'ACTIVE', 'BOOTSTRAP', CURRENT_TIMESTAMP)
                """,
                fixture.memberId(), fixture.organizationId(), fixture.teamId(),
                fixture.userPrincipalId());
        insertAgentPrincipal(
                fixture.personalAgentPrincipalId(), fixture, "PERSONAL_AGENT", "Personal Agent");
        insertAgentPrincipal(
                fixture.teamAgentPrincipalId(), fixture, "TEAM_AGENT", "Team Agent");
        insertProfile(
                fixture.personalAgentProfileId(), fixture, fixture.personalAgentPrincipalId(),
                fixture.memberId(), "PERSONAL", true);
        insertProfile(
                fixture.teamAgentProfileId(), fixture, fixture.teamAgentPrincipalId(),
                null, "TEAM", false);
        execute(
                """
                INSERT INTO crewscope.work_project (
                    id, organization_id, team_id, workspace_id,
                    project_key, name, created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, 'Project', ?, ?)
                """,
                fixture.projectId(), fixture.organizationId(), fixture.teamId(),
                fixture.workspaceId(), projectKey, fixture.userPrincipalId(),
                fixture.userPrincipalId());
        execute(
                """
                INSERT INTO crewscope.work_item (
                    id, organization_id, team_id, workspace_id, project_id,
                    item_key, item_type, title, status, priority,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, 'TASK', 'Item', 'BACKLOG', 'MEDIUM', ?, ?)
                """,
                fixture.workItemId(), fixture.organizationId(), fixture.teamId(),
                fixture.workspaceId(), fixture.projectId(), projectKey + "-1",
                fixture.userPrincipalId(), fixture.userPrincipalId());
        insertResponsibility(
                fixture.ownerAssignmentId(), fixture, "OWNER", fixture.userPrincipalId(),
                "USER", fixture.memberId());
        insertResponsibility(
                fixture.executorAssignmentId(), fixture, "EXECUTOR",
                fixture.teamAgentPrincipalId(), "TEAM_AGENT", null);
        if (personalSession) {
            insertPersonalSession(fixture);
        }
        return fixture;
    }

    private static void insertAgentPrincipal(
            UUID id, Fixture fixture, String type, String name) throws SQLException {
        execute(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, team_id, principal_type,
                    owner_principal_id, display_name, visibility, status
                ) VALUES (?, ?, ?, ?, ?, ?, 'TEAM', 'ACTIVE')
                """,
                id, fixture.organizationId(), fixture.teamId(), type,
                fixture.userPrincipalId(), name);
    }

    private static void insertProfile(
            UUID id,
            Fixture fixture,
            UUID agentId,
            UUID ownerMemberId,
            String type,
            boolean defaultProfile)
            throws SQLException {
        execute(
                """
                INSERT INTO crewscope.agent_profile (
                    id, organization_id, team_id, workspace_id,
                    agent_principal_id, owner_member_id,
                    profile_type, default_profile, status,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """,
                id, fixture.organizationId(), fixture.teamId(), fixture.workspaceId(),
                agentId, ownerMemberId, type, defaultProfile,
                fixture.userPrincipalId(), fixture.userPrincipalId());
    }

    private static void insertResponsibility(
            UUID id,
            Fixture fixture,
            String role,
            UUID principalId,
            String principalType,
            UUID memberId)
            throws SQLException {
        execute(
                """
                INSERT INTO crewscope.responsibility_assignment (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    role, actor_principal_id, actor_type, actor_member_id, status,
                    assigned_by_principal_id, assigned_at, accepted_at,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """,
                id, fixture.organizationId(), fixture.teamId(), fixture.workspaceId(),
                fixture.projectId(), fixture.workItemId(), role, principalId, principalType,
                memberId, fixture.userPrincipalId(), fixture.userPrincipalId(),
                fixture.userPrincipalId());
    }

    private static void insertDomainEvent(
            Fixture fixture,
            UUID eventId,
            String eventType,
            String aggregateType,
            UUID aggregateId,
            long aggregateVersion,
            String payload) throws SQLException {
        execute(
                """
                INSERT INTO crewscope.domain_event (
                    event_id, event_type, schema_version,
                    organization_id, team_id, workspace_id,
                    subject_type, subject_id, aggregate_version,
                    actor_type, actor_id, correlation_id, occurred_at, payload
                ) VALUES (?, ?, '1', ?, ?, ?, ?, ?, ?, 'USER', ?, ?,
                          CURRENT_TIMESTAMP, CAST(? AS jsonb))
                """,
                eventId,
                eventType,
                fixture.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                aggregateType,
                aggregateId,
                aggregateVersion,
                fixture.userPrincipalId(),
                UUID.randomUUID(),
                payload);
    }

    private static void insertPersonalSession(Fixture fixture) throws SQLException {
        UUID conversationId = fixture.conversationId();
        execute(
                """
                INSERT INTO crewscope.conversation (
                    id, organization_id, team_id, workspace_id,
                    owner_member_id, owner_principal_id, personal_agent_principal_id,
                    title, visibility, status,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'Conversation', 'PRIVATE', 'ACTIVE', ?, ?)
                """,
                conversationId, fixture.organizationId(), fixture.teamId(), fixture.workspaceId(),
                fixture.memberId(), fixture.userPrincipalId(), fixture.personalAgentPrincipalId(),
                fixture.userPrincipalId(), fixture.userPrincipalId());
        execute(
                """
                INSERT INTO crewscope.agent_runtime_session (
                    id, organization_id, team_id, workspace_id, conversation_id,
                    owner_member_id, owner_principal_id, personal_agent_principal_id,
                    agent_profile_id, agent_profile_version,
                    agent_scope_user_id, agent_scope_session_id, state_reference,
                    status, created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, 'ACTIVE', ?, ?)
                """,
                fixture.personalSessionId(), fixture.organizationId(), fixture.teamId(),
                fixture.workspaceId(), conversationId, fixture.memberId(),
                fixture.userPrincipalId(), fixture.personalAgentPrincipalId(),
                fixture.personalAgentProfileId(),
                "crewscope:v1:user:" + fixture.memberId(),
                "crewscope:v1:session:" + fixture.personalSessionId(),
                "crewscope:agent-state:v1:" + fixture.personalSessionId(),
                fixture.userPrincipalId(), fixture.userPrincipalId());
    }

    private static TaskFacts seedTaskFacts(Fixture fixture) throws SQLException {
        UUID snapshotId = UUID.randomUUID();
        execute(
                """
                INSERT INTO crewscope.task_responsibility_snapshot (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    snapshot_hash, captured_at, created_at,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP,
                          CURRENT_TIMESTAMP, ?, ?)
                """,
                snapshotId, fixture.organizationId(), fixture.teamId(), fixture.workspaceId(),
                fixture.projectId(), fixture.workItemId(), HASH_A,
                fixture.userPrincipalId(), fixture.userPrincipalId());
        insertSnapshotEntry(snapshotId, fixture, fixture.ownerAssignmentId(),
                "OWNER", fixture.userPrincipalId(), "USER", fixture.memberId());
        insertSnapshotEntry(snapshotId, fixture, fixture.executorAssignmentId(),
                "EXECUTOR", fixture.teamAgentPrincipalId(), "TEAM_AGENT", null);

        UUID taskId = UUID.randomUUID();
        execute(taskInsertSql(), taskId, fixture.organizationId(), fixture.teamId(),
                fixture.workspaceId(), fixture.projectId(), fixture.workItemId(), snapshotId,
                fixture.userPrincipalId(), fixture.userPrincipalId());
        UUID executionId = UUID.randomUUID();
        execute(taskExecutionInsertSql(), executionId, fixture.organizationId(), fixture.teamId(),
                fixture.workspaceId(), fixture.projectId(), taskId, 1, null,
                fixture.userPrincipalId(), fixture.userPrincipalId());
        UUID policyId = insertPolicy(fixture, taskId, executionId, 1, null, "TASK_CREATED");
        UUID overlayId = insertOverlay(fixture, taskId, executionId);
        UUID planId = insertPlan(
                fixture, taskId, executionId, policyId, overlayId,
                1, null, "INITIAL_PLAN");
        UUID stepId = UUID.randomUUID();
        insertStep(fixture, taskId, executionId, policyId, overlayId, planId, stepId);
        execute(
                """
                UPDATE crewscope.task_execution
                SET status = 'READY',
                    execution_principal_id = ?,
                    execution_assignment_id = ?,
                    execution_assignment_version = 0,
                    responsibility_snapshot_hash = ?,
                    current_policy_snapshot_id = ?,
                    current_policy_snapshot_hash = ?,
                    current_safety_overlay_id = ?,
                    current_safety_overlay_version = 1,
                    current_safety_overlay_hash = ?,
                    current_plan_version_id = ?,
                    current_plan_version_hash = ?,
                    updated_by_principal_id = ?
                WHERE id = ?
                """,
                fixture.teamAgentPrincipalId(), fixture.executorAssignmentId(), HASH_A,
                policyId, HASH_B, overlayId, HASH_C, planId, HASH_D,
                fixture.userPrincipalId(), executionId);
        execute(
                "UPDATE crewscope.task SET status = 'ACTIVE', current_execution_id = ?, updated_by_principal_id = ? WHERE id = ?",
                executionId, fixture.userPrincipalId(), taskId);
        return new TaskFacts(snapshotId, taskId, executionId, policyId, overlayId, planId, stepId);
    }

    private static void insertSnapshotEntry(
            UUID snapshotId,
            Fixture fixture,
            UUID assignmentId,
            String role,
            UUID principalId,
            String principalType,
            UUID memberId)
            throws SQLException {
        execute(
                """
                INSERT INTO crewscope.task_responsibility_snapshot_entry (
                    snapshot_id, organization_id, team_id, workspace_id, project_id,
                    work_item_id, assignment_id, assignment_version, role,
                    principal_id, principal_type, member_id, assigned_at, accepted_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                snapshotId, fixture.organizationId(), fixture.teamId(), fixture.workspaceId(),
                fixture.projectId(), fixture.workItemId(), assignmentId, role,
                principalId, principalType, memberId);
    }

    private static String taskInsertSql() {
        return """
                INSERT INTO crewscope.task (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    source_type, source_work_item_version, responsibility_snapshot_id,
                    status, created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, 'WORK_ITEM', 0, ?, 'CREATED', ?, ?)
                """;
    }

    private static String taskExecutionInsertSql() {
        return """
                INSERT INTO crewscope.task_execution (
                    id, organization_id, team_id, workspace_id, project_id, task_id,
                    attempt, max_attempts, parent_execution_id, priority, not_before, status,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 3, ?, 100, CURRENT_TIMESTAMP, 'CREATED', ?, ?)
                """;
    }

    private static UUID insertPolicy(
            Fixture fixture,
            UUID taskId,
            UUID executionId,
            long revision,
            UUID parentId,
            String reason)
            throws SQLException {
        UUID id = UUID.randomUUID();
        execute(policyInsertSql(), id, fixture, taskId, executionId, revision, parentId, reason);
        return id;
    }

    private static String policyInsertSql() {
        return """
                INSERT INTO crewscope.policy_snapshot (
                    id, organization_id, team_id, workspace_id, project_id,
                    task_id, task_execution_id, revision, parent_snapshot_id, change_reason,
                    execution_principal_id, execution_assignment_id,
                    execution_assignment_version, responsibility_snapshot_hash,
                    policy_pack_id, policy_pack_version, agent_profile_id, agent_profile_version,
                    capabilities, allowed_tools, provider_binding_ids,
                    max_tokens, max_model_calls, max_tool_calls, max_duration_seconds,
                    snapshot_hash, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?,
                          ?, 0, ?, 0, '[]'::JSONB, '[]'::JSONB, '[]'::JSONB,
                          1000, 10, 10, 600, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?)
                """;
    }

    private static int execute(
            String sql,
            UUID id,
            Fixture fixture,
            UUID taskId,
            UUID executionId,
            long revision,
            UUID parentId,
            String reason)
            throws SQLException {
        return execute(sql, id, fixture.organizationId(), fixture.teamId(), fixture.workspaceId(),
                fixture.projectId(), taskId, executionId, revision, parentId, reason,
                fixture.teamAgentPrincipalId(), fixture.executorAssignmentId(), HASH_A,
                UUID.randomUUID(), fixture.teamAgentProfileId(), HASH_B,
                fixture.userPrincipalId(), fixture.userPrincipalId());
    }

    private static UUID insertOverlay(Fixture fixture, UUID taskId, UUID executionId)
            throws SQLException {
        UUID id = UUID.randomUUID();
        execute(
                """
                INSERT INTO crewscope.safety_enforcement_overlay (
                    id, organization_id, team_id, workspace_id, project_id,
                    task_id, task_execution_id, overlay_version,
                    restrictions, disabled_capabilities, disabled_tools, overlay_hash,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, '[]'::JSONB, '[]'::JSONB, '[]'::JSONB,
                          ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?)
                """,
                id, fixture.organizationId(), fixture.teamId(), fixture.workspaceId(),
                fixture.projectId(), taskId, executionId, HASH_C,
                fixture.userPrincipalId(), fixture.userPrincipalId());
        return id;
    }

    private static UUID insertPlan(
            Fixture fixture,
            UUID taskId,
            UUID executionId,
            UUID policyId,
            UUID overlayId,
            long revision,
            UUID parentId,
            String reason)
            throws SQLException {
        UUID id = UUID.randomUUID();
        execute(planInsertSql(), id, fixture, taskId, executionId, policyId, overlayId,
                revision, parentId, reason);
        execute(
                """
                INSERT INTO crewscope.plan_step (
                    plan_version_id, task_execution_id, step_key, sequence, title, step_type,
                    dependency_keys, required_capabilities, required_tools, critical
                ) VALUES (?, ?, 'execute', 1, 'Execute', 'IMPLEMENTATION',
                          '[]'::JSONB, '[]'::JSONB, '[]'::JSONB, TRUE)
                """,
                id, executionId);
        return id;
    }

    private static String planInsertSql() {
        return """
                INSERT INTO crewscope.plan_version (
                    id, organization_id, team_id, workspace_id, project_id,
                    task_id, task_execution_id, policy_snapshot_id, policy_snapshot_hash,
                    safety_overlay_id, safety_overlay_version, safety_overlay_hash,
                    execution_principal_id, execution_assignment_id,
                    execution_assignment_version, responsibility_snapshot_hash,
                    revision, parent_version_id, change_reason, markdown,
                    content_hash, version_hash, published_by_principal_id, published_at,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, 0, ?,
                          ?, ?, ?, '# Plan', ?, ?, ?, CURRENT_TIMESTAMP,
                          CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?)
                """;
    }

    private static int execute(
            String sql,
            UUID id,
            Fixture fixture,
            UUID taskId,
            UUID executionId,
            UUID policyId,
            UUID overlayId,
            long revision,
            UUID parentId,
            String reason)
            throws SQLException {
        return execute(sql, id, fixture.organizationId(), fixture.teamId(), fixture.workspaceId(),
                fixture.projectId(), taskId, executionId, policyId, HASH_B, overlayId, HASH_C,
                fixture.teamAgentPrincipalId(), fixture.executorAssignmentId(), HASH_A,
                revision, parentId, reason, HASH_A, HASH_D,
                fixture.userPrincipalId(), fixture.userPrincipalId(), fixture.userPrincipalId());
    }

    private static void insertStep(
            Fixture fixture,
            UUID taskId,
            UUID executionId,
            UUID policyId,
            UUID overlayId,
            UUID planId,
            UUID stepId)
            throws SQLException {
        execute(
                """
                INSERT INTO crewscope.step_execution (
                    id, organization_id, team_id, workspace_id, project_id,
                    task_id, task_execution_id, plan_version_id, plan_version_hash,
                    plan_step_key, sequence, critical,
                    execution_principal_id, execution_assignment_id,
                    execution_assignment_version, responsibility_snapshot_hash,
                    policy_snapshot_id, policy_snapshot_hash,
                    safety_overlay_id, safety_overlay_version, safety_overlay_hash,
                    run_attempt, max_run_attempts, status,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'execute', 1, TRUE, ?, ?, 0, ?,
                          ?, ?, ?, 1, ?, 1, 3, 'READY', ?, ?)
                """,
                stepId, fixture.organizationId(), fixture.teamId(), fixture.workspaceId(),
                fixture.projectId(), taskId, executionId, planId, HASH_D,
                fixture.teamAgentPrincipalId(), fixture.executorAssignmentId(), HASH_A,
                policyId, HASH_B, overlayId, HASH_C,
                fixture.userPrincipalId(), fixture.userPrincipalId());
    }

    private static RuntimeFacts seedRuntimeFacts(Fixture fixture, TaskFacts facts)
            throws SQLException {
        UUID runtimeId = UUID.randomUUID();
        execute(
                """
                INSERT INTO crewscope.execution_runtime (
                    id, organization_id, runtime_environment, runtime_key,
                    display_name, implementation_version,
                    capabilities, languages, build_systems, status,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, 'dev', 'agentscope', 'AgentScope', '2.0.0',
                          '[]'::JSONB, '[]'::JSONB, '[]'::JSONB, 'ACTIVE', ?, ?)
                """,
                runtimeId, fixture.organizationId(), fixture.userPrincipalId(),
                fixture.userPrincipalId());
        UUID workerId = UUID.randomUUID();
        execute(runtimeWorkerInsertSql(), workerId, fixture.organizationId(), runtimeId,
                "worker-one", 2, 0, fixture.userPrincipalId(), fixture.userPrincipalId());
        execute(
                """
                UPDATE crewscope.task_execution
                SET status = 'CLAIMED', last_fencing_token = 1,
                    updated_by_principal_id = ?
                WHERE id = ?
                """,
                fixture.userPrincipalId(), facts.executionId());
        UUID leaseId = UUID.randomUUID();
        RuntimeFacts runtime = new RuntimeFacts(runtimeId, workerId, leaseId);
        execute(leaseInsertSql(), leaseId, fixture, facts, runtime, HASH_A, 1L);
        return runtime;
    }

    private static String runtimeWorkerInsertSql() {
        return """
                INSERT INTO crewscope.runtime_worker (
                    id, organization_id, runtime_environment, runtime_id, stable_key,
                    runtime_profile, capabilities, languages, build_systems,
                    max_concurrent_executions, active_executions, status,
                    last_heartbeat_at, heartbeat_sequence,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, 'dev', ?, ?, 'WORKER', '[]'::JSONB, '[]'::JSONB,
                          '[]'::JSONB, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, 1, ?, ?)
                """;
    }

    private static String leaseInsertSql() {
        return """
                INSERT INTO crewscope.execution_lease (
                    id, organization_id, team_id, workspace_id, project_id,
                    task_id, task_execution_id, attempt, runtime_environment,
                    runtime_id, worker_id, claim_token_hash, fencing_token,
                    phase, status, acquired_at, last_heartbeat_at, expires_at,
                    lease_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, 'dev', ?, ?, ?, ?,
                          'PREPARE', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                          CURRENT_TIMESTAMP + INTERVAL '1 minute', 0)
                """;
    }

    private static int execute(
            String sql,
            UUID id,
            Fixture fixture,
            TaskFacts facts,
            RuntimeFacts runtime,
            String hash,
            long fencing)
            throws SQLException {
        return execute(sql, id, fixture.organizationId(), fixture.teamId(), fixture.workspaceId(),
                fixture.projectId(), facts.taskId(), facts.executionId(), runtime.runtimeId(),
                runtime.workerId(), hash, fencing);
    }

    private static String credentialGrantInsertSql() {
        return """
                INSERT INTO crewscope.task_credential_grant (
                    id, organization_id, team_id, workspace_id, project_id,
                    task_id, task_execution_id, attempt, execution_lease_id,
                    runtime_environment, runtime_id, worker_id, claim_token_hash, fencing_token,
                    execution_principal_id, execution_assignment_id,
                    execution_assignment_version, responsibility_snapshot_hash,
                    policy_snapshot_id, policy_snapshot_hash,
                    safety_overlay_id, safety_overlay_version, safety_overlay_hash,
                    jti_hash, issued_at, expires_at, status,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, 'dev', ?, ?, ?, 1, ?, ?, 0, ?,
                          ?, ?, ?, 1, ?, ?, CURRENT_TIMESTAMP,
                          CURRENT_TIMESTAMP + INTERVAL '30 seconds', 'ACTIVE', ?, ?)
                """;
    }

    private static int execute(
            String sql,
            UUID id,
            Fixture fixture,
            TaskFacts facts,
            RuntimeFacts runtime,
            String jtiHash)
            throws SQLException {
        return execute(sql, id, fixture.organizationId(), fixture.teamId(), fixture.workspaceId(),
                fixture.projectId(), facts.taskId(), facts.executionId(), runtime.leaseId(),
                runtime.runtimeId(), runtime.workerId(), HASH_A,
                fixture.teamAgentPrincipalId(), fixture.executorAssignmentId(), HASH_A,
                facts.policyId(), HASH_B, facts.overlayId(), HASH_C, jtiHash,
                fixture.userPrincipalId(), fixture.userPrincipalId());
    }

    private static UUID insertTaskSession(
            Fixture fixture, TaskFacts facts, String purpose, UUID stepId) throws SQLException {
        UUID id = UUID.randomUUID();
        execute(taskSessionInsertSql(), id, fixture, facts, purpose, stepId);
        return id;
    }

    private static String taskSessionInsertSql() {
        return """
                INSERT INTO crewscope.agent_runtime_session (
                    id, organization_id, team_id, workspace_id,
                    session_purpose, project_id, task_id, task_execution_id, step_execution_id,
                    agent_principal_id, agent_principal_type,
                    agent_profile_id, agent_profile_type, agent_profile_version,
                    agent_scope_user_id, agent_scope_session_id, state_reference,
                    status, created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'TEAM_AGENT', ?, 'TEAM', 0,
                          ?, ?, ?, 'ACTIVE', ?, ?)
                """;
    }

    private static int execute(
            String sql,
            UUID id,
            Fixture fixture,
            TaskFacts facts,
            String purpose,
            UUID stepId)
            throws SQLException {
        return execute(sql, id, fixture.organizationId(), fixture.teamId(), fixture.workspaceId(),
                purpose, fixture.projectId(), facts.taskId(), facts.executionId(), stepId,
                fixture.teamAgentPrincipalId(), fixture.teamAgentProfileId(),
                "crewscope:v1:user:" + facts.taskId(),
                "crewscope:v1:session:" + id,
                "crewscope:agent-state:v1:" + id,
                fixture.userPrincipalId(), fixture.userPrincipalId());
    }

    private static UUID insertAgentRun(
            Fixture fixture, TaskFacts facts, UUID sessionId, long sequence) throws SQLException {
        UUID id = UUID.randomUUID();
        execute(agentRunInsertSql(), id, fixture, facts, sessionId, sequence);
        return id;
    }

    private static String agentRunInsertSql() {
        return """
                INSERT INTO crewscope.agent_run (
                    id, organization_id, team_id, workspace_id, project_id,
                    task_id, task_execution_id, runtime_session_id,
                    agent_principal_id, agent_profile_id, agent_profile_version,
                    run_sequence, status, created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 'RUNNING', ?, ?)
                """;
    }

    private static int execute(
            String sql,
            UUID id,
            Fixture fixture,
            TaskFacts facts,
            UUID sessionId,
            long sequence)
            throws SQLException {
        return execute(sql, id, fixture.organizationId(), fixture.teamId(), fixture.workspaceId(),
                fixture.projectId(), facts.taskId(), facts.executionId(), sessionId,
                fixture.teamAgentPrincipalId(), fixture.teamAgentProfileId(), sequence,
                fixture.teamAgentPrincipalId(), fixture.teamAgentPrincipalId());
    }

    private static void insertSegment(UUID runId, long sequence) throws SQLException {
        execute(
                """
                INSERT INTO crewscope.agent_run_segment (
                    agent_run_id, sequence, kind, status, started_at
                ) VALUES (?, ?, 'INVOKE', 'ACTIVE', CURRENT_TIMESTAMP)
                """,
                runId, sequence);
    }

    private static void insertInterrupt(
            Fixture fixture, TaskFacts facts, UUID runId, String hash) throws SQLException {
        execute(interruptInsertSql(), UUID.randomUUID(), fixture, facts, runId, hash);
    }

    private static String interruptInsertSql() {
        return """
                INSERT INTO crewscope.agent_interrupt (
                    id, organization_id, team_id, workspace_id, project_id,
                    task_id, task_execution_id, agent_run_id, segment_sequence,
                    kind, interrupt_token_hash, status,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, 'CLARIFICATION', ?, 'PENDING', ?, ?)
                """;
    }

    private static int execute(
            String sql,
            UUID id,
            Fixture fixture,
            TaskFacts facts,
            UUID runId,
            String hash)
            throws SQLException {
        return execute(sql, id, fixture.organizationId(), fixture.teamId(), fixture.workspaceId(),
                fixture.projectId(), facts.taskId(), facts.executionId(), runId, hash,
                fixture.teamAgentPrincipalId(), fixture.teamAgentPrincipalId());
    }

    private static UUID insertSnapshotArtifact(
            Fixture fixture, TaskFacts facts, UUID runId, String hash) throws SQLException {
        UUID id = UUID.randomUUID();
        execute(
                """
                INSERT INTO crewscope.runtime_artifact (
                    id, artifact_id, organization_id, team_id, workspace_id, project_id,
                    task_id, task_execution_id, agent_run_id, kind, content_type,
                    size_bytes, content_hash, created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'AGENT_STATE_SNAPSHOT',
                          'application/vnd.crewscope.agent-state-snapshot+json',
                          128, ?, ?, ?)
                """,
                id, UUID.randomUUID(), fixture.organizationId(), fixture.teamId(),
                fixture.workspaceId(), fixture.projectId(), facts.taskId(), facts.executionId(),
                runId, hash, fixture.teamAgentPrincipalId(), fixture.teamAgentPrincipalId());
        return id;
    }

    private static void insertSnapshot(
            Fixture fixture,
            TaskFacts facts,
            UUID runId,
            UUID sessionId,
            UUID artifactId,
            String hash,
            long sequence)
            throws SQLException {
        execute(snapshotInsertSql(), UUID.randomUUID(), fixture, facts, runId, sessionId,
                artifactId, hash, sequence);
    }

    private static String snapshotInsertSql() {
        return """
                INSERT INTO crewscope.agent_state_snapshot (
                    id, organization_id, team_id, workspace_id, project_id,
                    task_id, task_execution_id, agent_run_id, runtime_session_id,
                    agent_profile_id, agent_profile_version, agent_principal_id, agent_name,
                    agent_scope_user_id, agent_scope_session_id,
                    snapshot_sequence, checkpoint_sequence,
                    runtime_artifact_id, content_hash, size_bytes, status,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 'TaskAgent', ?, ?,
                          ?, ?, ?, ?, 128, 'CURRENT', ?, ?)
                """;
    }

    private static int execute(
            String sql,
            UUID id,
            Fixture fixture,
            TaskFacts facts,
            UUID runId,
            UUID sessionId,
            UUID artifactId,
            String hash,
            long sequence)
            throws SQLException {
        return execute(sql, id, fixture.organizationId(), fixture.teamId(), fixture.workspaceId(),
                fixture.projectId(), facts.taskId(), facts.executionId(), runId, sessionId,
                fixture.teamAgentProfileId(), fixture.teamAgentPrincipalId(),
                "crewscope:v1:user:" + facts.taskId(), "crewscope:v1:session:" + sessionId,
                sequence, sequence, artifactId, hash,
                fixture.teamAgentPrincipalId(), fixture.teamAgentPrincipalId());
    }

    private static Flyway flyway(String jdbcUrl, MigrationVersion target) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas("crewscope")
                .defaultSchema("crewscope")
                .createSchemas(true)
                .validateMigrationNaming(true);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static Connection openConnection(String jdbcUrl) throws SQLException {
        return DriverManager.getConnection(
                jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static int execute(String sql, Object... values) throws SQLException {
        try (Connection connection = openConnection(POSTGRES.getJdbcUrl());
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            return statement.executeUpdate();
        }
    }

    private static void assertSqlState(String state, String sql, Object... values) {
        SQLException exception = assertThrows(SQLException.class, () -> execute(sql, values));
        assertEquals(state, exception.getSQLState());
    }

    private static void assertSqlState(String state, SqlAction action) {
        SQLException exception = assertThrows(SQLException.class, action::execute);
        assertEquals(state, exception.getSQLState());
    }

    private static int queryInt(String sql, Object... values) throws SQLException {
        try (Connection connection = openConnection(POSTGRES.getJdbcUrl());
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getInt(1);
            }
        }
    }

    private static Set<String> queryStrings(String sql) throws SQLException {
        Set<String> result = new HashSet<>();
        try (Connection connection = openConnection(POSTGRES.getJdbcUrl());
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                result.add(resultSet.getString(1));
            }
        }
        return result;
    }

    private static Set<String> columns(String table) throws SQLException {
        Set<String> result = new HashSet<>();
        try (Connection connection = openConnection(POSTGRES.getJdbcUrl());
                PreparedStatement statement = connection.prepareStatement(
                        """
                        SELECT column_name FROM information_schema.columns
                        WHERE table_schema = 'crewscope' AND table_name = ?
                        """)) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.add(resultSet.getString(1));
                }
            }
        }
        return result;
    }

    private record Fixture(
            UUID organizationId,
            UUID teamId,
            UUID workspaceId,
            UUID userPrincipalId,
            UUID memberId,
            UUID personalAgentPrincipalId,
            UUID personalAgentProfileId,
            UUID teamAgentPrincipalId,
            UUID teamAgentProfileId,
            UUID projectId,
            UUID workItemId,
            UUID ownerAssignmentId,
            UUID executorAssignmentId,
            UUID conversationId,
            UUID personalSessionId) {}

    private record TaskFacts(
            UUID responsibilitySnapshotId,
            UUID taskId,
            UUID executionId,
            UUID policyId,
            UUID overlayId,
            UUID planId,
            UUID stepId) {}

    private record RuntimeFacts(UUID runtimeId, UUID workerId, UUID leaseId) {}

    @FunctionalInterface
    private interface SqlAction {
        void execute() throws SQLException;
    }
}
