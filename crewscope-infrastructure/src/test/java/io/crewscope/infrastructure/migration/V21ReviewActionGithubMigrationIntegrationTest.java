package io.crewscope.infrastructure.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.nio.charset.StandardCharsets;
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

/** Verifies V21 Review, approved Action and GitHub delivery persistence boundaries. */
class V21ReviewActionGithubMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final MigrationVersion VERSION_20 = MigrationVersion.fromVersion("20");
    private static final MigrationVersion VERSION_21 = MigrationVersion.fromVersion("21");
    private static final String ALTERNATE_SCHEMA = "v21_probe";
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final String HASH_C = "c".repeat(64);
    private static final String HASH_D = "d".repeat(64);
    private static final String HASH_E = "e".repeat(64);
    private static final String COMMIT_A = "a".repeat(40);
    private static final String COMMIT_B = "b".repeat(40);
    private static final Set<String> V21_TABLES = Set.of(
            "github_connection_profile",
            "github_repository_catalog_entry",
            "github_rate_limit_snapshot",
            "review_subject",
            "review_context_package",
            "review_context_hunk",
            "review_context_command_evidence",
            "review_context_acceptance_result",
            "review_request",
            "review_request_state",
            "review_finding",
            "review_finding_evidence",
            "review_finding_observation",
            "review_decision",
            "review_modification_round",
            "action_bundle",
            "planned_action",
            "planned_action_dependency",
            "action_confirmation",
            "confirmation_action",
            "action_dispatch",
            "action_dispatch_dependency",
            "action_receipt",
            "external_observation",
            "external_result");

    @BeforeEach
    void resetDatabase() throws SQLException {
        resetSchemas();
    }

    @Test
    void createsCompleteV21SchemaWithFactAndMutableRootAuditShapes() throws SQLException {
        Flyway flyway = flyway(POSTGRES.getJdbcUrl(), VERSION_21);
        flyway.migrate();

        assertEquals("21", flyway.info().current().getVersion().getVersion());
        assertEquals(V21_TABLES, existingTables("crewscope", V21_TABLES));

        for (String table : Set.of(
                "review_subject", "review_context_package", "review_finding",
                "review_decision", "review_modification_round", "action_bundle",
                "action_receipt", "external_observation", "github_rate_limit_snapshot")) {
            Set<String> names = columns(table);
            assertTrue(names.containsAll(Set.of("created_at", "created_by_principal_id")), table);
            assertFalse(names.contains("updated_at"), table);
            assertFalse(names.contains("deleted"), table);
            assertFalse(names.contains("deleted_at"), table);
        }
        for (String table : Set.of(
                "review_request", "action_confirmation", "action_dispatch", "external_result",
                "github_connection_profile", "github_repository_catalog_entry")) {
            assertTrue(columns(table).containsAll(Set.of(
                    "version", "created_at", "created_by_principal_id",
                    "updated_at", "updated_by_principal_id")), table);
        }
        assertFalse(columns("action_receipt").contains("provider_payload"));
        assertFalse(columns("github_connection_profile").contains("token"));
        assertFalse(columns("github_connection_profile").contains("secret"));
    }

    @Test
    void upgradesV20ToV21AndValidatesFlywayHistory() throws SQLException {
        Flyway source = flyway(POSTGRES.getJdbcUrl(), VERSION_20);
        source.migrate();
        assertEquals("20", source.info().current().getVersion().getVersion());

        Flyway target = flyway(POSTGRES.getJdbcUrl(), VERSION_21);
        assertEquals(1, target.migrate().migrationsExecuted);
        assertEquals("21", target.info().current().getVersion().getVersion());
        target.validate();
        assertEquals(1, queryInt(
                "SELECT COUNT(*) FROM crewscope.flyway_schema_history "
                        + "WHERE version = '21' AND success"));
    }

    @Test
    void migratesIntoCrewscopeWithNonDefaultSearchPath() throws SQLException {
        String jdbcUrl = jdbcUrlWithCurrentSchema(ALTERNATE_SCHEMA);
        assertEquals(ALTERNATE_SCHEMA, currentSchema(jdbcUrl));

        flyway(jdbcUrl, VERSION_21).migrate();

        assertEquals(V21_TABLES, existingTables("crewscope", V21_TABLES));
        assertTrue(existingTables(ALTERNATE_SCHEMA, V21_TABLES).isEmpty());
        assertEquals(0, tableCount(ALTERNATE_SCHEMA, "flyway_schema_history"));
    }

    @Test
    void createsCompositeScopeConflictExternalAndQueueIndexes() throws SQLException {
        flyway(POSTGRES.getJdbcUrl(), VERSION_21).migrate();

        Set<String> constraints = queryStrings(
                "SELECT conname FROM pg_constraint "
                        + "WHERE connamespace = 'crewscope'::regnamespace");
        assertTrue(constraints.containsAll(Set.of(
                "fk_review_subject_diff",
                "fk_review_context_test",
                "fk_review_request_context",
                "uk_review_finding_fingerprint",
                "uk_review_decision_revision",
                "fk_action_bundle_provider_binding",
                "fk_action_bundle_subject",
                "fk_action_bundle_context",
                "uk_action_receipt_action",
                "ux_action_receipt_external_id",
                "uk_external_observation_connection_key",
                "ux_external_result_business_key",
                "uk_github_repository_external_id")));

        Set<String> indexes = queryStrings(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'crewscope'");
        assertTrue(indexes.containsAll(Set.of(
                "ix_review_subject_execution",
                "ix_review_context_execution_version",
                "ix_review_request_queue",
                "ix_review_finding_request_severity",
                "ix_review_decision_task_history",
                "ux_review_decision_terminal_gate",
                "ix_action_bundle_task_created",
                "ix_action_dispatch_claimable",
                "ix_action_dispatch_manual_queue",
                "ix_external_observation_history",
                "ix_external_result_reconcile",
                "ix_github_repository_catalog_delivery",
                "ix_github_rate_limit_current")));
    }

    @Test
    void persistsRepresentativeGraphAndPreservesPartialSuccess() throws SQLException {
        flyway(POSTGRES.getJdbcUrl(), VERSION_21).migrate();
        GraphFixture fixture = seedRepresentativeGraph();

        assertEquals(1, queryInt(
                "SELECT COUNT(*) FROM crewscope.review_context_package "
                        + "WHERE id = '" + fixture.contextId() + "' AND context_hash = '" + HASH_C + "'"));
        assertEquals(2, queryInt(
                "SELECT COUNT(*) FROM crewscope.planned_action "
                        + "WHERE action_bundle_id = '" + fixture.bundleId() + "'"));
        assertEquals(1, queryInt(
                "SELECT COUNT(*) FROM crewscope.action_dispatch "
                        + "WHERE action_bundle_id = '" + fixture.bundleId() + "' "
                        + "AND status = 'SUCCEEDED' AND receipt_id IS NOT NULL"));
        assertEquals(1, queryInt(
                "SELECT COUNT(*) FROM crewscope.action_dispatch "
                        + "WHERE action_bundle_id = '" + fixture.bundleId() + "' "
                        + "AND status = 'READY' AND receipt_id IS NULL"));
        assertEquals(1, queryInt(
                "SELECT COUNT(*) FROM crewscope.action_dispatch_dependency dependency "
                        + "JOIN crewscope.action_dispatch successor "
                        + "ON successor.id = dependency.action_dispatch_id "
                        + "WHERE successor.action_id = '" + fixture.pullRequestActionId() + "' "
                        + "AND dependency.predecessor_action_id = '" + fixture.pushActionId() + "'"));

        execute(
                """
                UPDATE crewscope.review_request
                SET status = 'INVALIDATED', invalidation_reason = 'CONTEXT_CHANGED',
                    version = 3, updated_at = TIMESTAMPTZ '2026-08-23 10:00:12+00',
                    updated_by_principal_id = ?
                WHERE id = ?
                """,
                fixture.actorId(), fixture.requestId());
        assertEquals(2, queryInt(
                "SELECT COUNT(*) FROM crewscope.review_request_state "
                        + "WHERE review_request_id = '" + fixture.requestId() + "'"));
    }

    @Test
    void rejectsAppendOnlyRewritesDuplicateKeysCrossScopeAndOldFencing() throws SQLException {
        flyway(POSTGRES.getJdbcUrl(), VERSION_21).migrate();
        GraphFixture fixture = seedRepresentativeGraph();

        assertSqlState("23514", () -> execute(
                "UPDATE crewscope.review_decision SET rationale = 'rewritten' WHERE id = ?",
                fixture.decisionId()));
        assertSqlState("23514", () -> execute(
                "UPDATE crewscope.action_receipt SET target_version = 'rewritten' WHERE id = ?",
                fixture.receiptId()));
        assertSqlState("23514", () -> execute(
                "UPDATE crewscope.external_observation SET external_status = 'MISSING' WHERE id = ?",
                fixture.observationId()));

        assertSqlState("23505", () -> execute(statementSql(
                """
                INSERT INTO crewscope.review_decision (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    task_id, task_execution_id, attempt, review_request_id,
                    review_request_revision, review_request_version, review_request_hash,
                    revision, reviewer_mode, reviewer_principal_id, reviewer_member_id,
                    eligibility_mode, eligibility_reason, decision_type, rationale,
                    decision_hash, created_at, created_by_principal_id
                ) SELECT ?, organization_id, team_id, workspace_id, project_id, work_item_id,
                    task_id, task_execution_id, attempt, review_request_id,
                    review_request_revision, review_request_version, review_request_hash,
                    revision, reviewer_mode, reviewer_principal_id, reviewer_member_id,
                    eligibility_mode, eligibility_reason, 'COMMENTED', 'duplicate revision',
                    ?, created_at + INTERVAL '1 second', created_by_principal_id
                FROM crewscope.review_decision WHERE id = ?
                """), UUID.randomUUID(), HASH_D, fixture.decisionId()));

        assertSqlState("23505", () -> execute(statementSql(
                """
                INSERT INTO crewscope.external_observation (
                    id, organization_id, team_id, workspace_id, project_id,
                    action_bundle_id, action_id, action_digest, observation_key,
                    connection_id, external_object_type, external_id,
                    external_business_key, external_status, provider_version,
                    source, evidence_code, evidence_hash, observed_at,
                    created_at, created_by_principal_id
                ) SELECT ?, organization_id, team_id, workspace_id, project_id,
                    action_bundle_id, action_id, action_digest, observation_key,
                    connection_id, external_object_type, 'other-id',
                    'other-key', external_status, provider_version,
                    source, evidence_code, evidence_hash, observed_at,
                    created_at, created_by_principal_id
                FROM crewscope.external_observation WHERE id = ?
                """), UUID.randomUUID(), fixture.observationId()));

        assertSqlState("23503", () -> execute(
                """
                INSERT INTO crewscope.review_finding (
                    id, organization_id, team_id, workspace_id, project_id,
                    task_id, task_execution_id, attempt, review_request_id,
                    review_request_revision, review_request_version, review_request_hash,
                    reviewer_mode, reviewer_relationship, reviewer_principal_id,
                    severity, category, title, claim, suggested_fix,
                    fingerprint, candidate_hash, created_at, created_by_principal_id
                ) SELECT ?, ?, team_id, workspace_id, project_id,
                    task_id, task_execution_id, attempt, review_request_id,
                    review_request_revision, review_request_version, review_request_hash,
                    reviewer_mode, reviewer_relationship, reviewer_principal_id,
                    severity, category, 'cross scope', claim, suggested_fix,
                    ?, ?, created_at, created_by_principal_id
                FROM crewscope.review_finding WHERE id = ?
                """,
                UUID.randomUUID(), UUID.randomUUID(), HASH_B, HASH_D, fixture.findingId()));

        seedStaleFencingDispatch(fixture);
        assertSqlState("23503", () -> execute(
                """
                INSERT INTO crewscope.action_receipt (
                    id, organization_id, team_id, workspace_id, project_id,
                    action_bundle_id, bundle_digest, action_dispatch_id, action_id,
                    action_digest, idempotency_key, result, source,
                    claim_worker_id, claim_fencing_token,
                    evidence_code, evidence_hash, received_at, created_at,
                    created_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'FAILED', 'WRITE_RESPONSE',
                          'old-worker', 1, 'REMOTE_HEAD_CONFLICT', ?,
                          TIMESTAMPTZ '2026-08-23 10:10:00+00',
                          TIMESTAMPTZ '2026-08-23 10:10:00+00', ?)
                """,
                UUID.randomUUID(), fixture.organizationId(), fixture.teamId(),
                fixture.workspaceId(), fixture.projectId(), fixture.bundleId(), HASH_A,
                fixture.staleDispatchId(), fixture.staleActionId(), HASH_D, HASH_E,
                HASH_B, fixture.actorId()));
    }

    @Test
    void enforcesConnectionScopedIdentityAndMonotonicExternalResult() throws SQLException {
        flyway(POSTGRES.getJdbcUrl(), VERSION_21).migrate();
        GraphFixture fixture = seedRepresentativeGraph();
        seedGithubConnectionRows(fixture);

        insertGithubProfile(
                id("github-team-profile"), fixture, fixture.connectionId(),
                "TEAM", fixture.teamId(), "TEAM_SERVICE_ACCOUNT", "APP_INSTALLATION", "9001");
        insertGithubProfile(
                id("github-user-profile"), fixture, id("other-connection"),
                "USER", fixture.actorId(), "DELEGATED_USER", "OAUTH_USER", "octocat");
        assertSqlState("23514", () -> insertGithubProfile(
                id("github-invalid-profile"), fixture, id("invalid-github-connection"),
                "TEAM", fixture.teamId(), "DELEGATED_USER", "OAUTH_USER", "invalid"));

        insertGithubRepository(
                id("github-team-repository"), fixture, fixture.connectionId(),
                "TEAM_SERVICE_ACCOUNT", "10001", "crewscope", "crewscope-java");
        assertSqlState("23505", () -> insertGithubRepository(
                id("github-team-repository-duplicate"), fixture, fixture.connectionId(),
                "TEAM_SERVICE_ACCOUNT", "10001", "crewscope", "other-name"));
        // Identical Provider repository IDs remain valid under another Connection.
        insertGithubRepository(
                id("github-user-repository"), fixture, id("other-connection"),
                "DELEGATED_USER", "10001", "zhangkaixuan", "crewscope-java");

        assertSqlState("23505", () -> execute(statementSql(
                """
                INSERT INTO crewscope.action_receipt (
                    id, organization_id, team_id, workspace_id, project_id,
                    action_bundle_id, bundle_digest, action_dispatch_id, action_id,
                    action_digest, idempotency_key, result, source,
                    connection_id, external_object_type, external_id,
                    external_business_key, target_version, evidence_code,
                    evidence_hash, resolved_by_principal_id, received_at,
                    created_at, created_by_principal_id
                ) SELECT ?, organization_id, team_id, workspace_id, project_id,
                    action_bundle_id, bundle_digest, ?, ?, ?, ?, result, source,
                    connection_id, external_object_type, external_id,
                    'different-business-key', target_version, evidence_code,
                    evidence_hash, resolved_by_principal_id, received_at,
                    created_at, created_by_principal_id
                FROM crewscope.action_receipt WHERE id = ?
                """), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), HASH_D, HASH_C,
                fixture.receiptId()));

        // The same Provider object ID is valid for another Connection; uniqueness is scoped.
        executeReplica(statementSql(
                """
                INSERT INTO crewscope.action_receipt (
                    id, organization_id, team_id, workspace_id, project_id,
                    action_bundle_id, bundle_digest, action_dispatch_id, action_id,
                    action_digest, idempotency_key, result, source,
                    connection_id, external_object_type, external_id,
                    external_business_key, target_version, evidence_code,
                    evidence_hash, resolved_by_principal_id, received_at,
                    created_at, created_by_principal_id
                ) SELECT '%s', organization_id, team_id, workspace_id, project_id,
                    action_bundle_id, bundle_digest, '%s', '%s', '%s', '%s', result, source,
                    '%s', external_object_type, external_id,
                    external_business_key, target_version, evidence_code,
                    evidence_hash, resolved_by_principal_id, received_at,
                    created_at, created_by_principal_id
                FROM crewscope.action_receipt WHERE id = '%s'
                """.formatted(
                        id("other-receipt"), id("other-dispatch"), id("other-action"),
                        HASH_D, HASH_C, id("other-connection"), fixture.receiptId())));

        assertSqlState("23514", () -> execute(
                """
                UPDATE crewscope.external_result
                SET provider_version = provider_version,
                    version = version + 1,
                    updated_at = updated_at + INTERVAL '1 second'
                WHERE id = ?
                """,
                fixture.externalResultId()));
        assertSqlState("23514", () -> execute(
                "DELETE FROM crewscope.external_result WHERE id = ?",
                fixture.externalResultId()));
    }

    private static void seedGithubConnectionRows(GraphFixture f) throws SQLException {
        executeReplica(
                githubConnectionSql(
                        f.connectionId(), f, "TEAM", f.teamId(), f.teamId(), null),
                githubConnectionSql(
                        id("other-connection"), f, "USER", f.actorId(), null, f.actorId()),
                githubConnectionSql(
                        id("invalid-github-connection"), f,
                        "TEAM", f.teamId(), f.teamId(), null));
    }

    private static String githubConnectionSql(
            UUID connectionId,
            GraphFixture f,
            String ownerType,
            UUID ownerId,
            UUID ownerTeamId,
            UUID ownerPrincipalId) {
        String team = ownerTeamId == null ? "NULL" : "'" + ownerTeamId + "'";
        String principal = ownerPrincipalId == null ? "NULL" : "'" + ownerPrincipalId + "'";
        return """
                INSERT INTO crewscope.connection (
                    id, organization_id, owner_type, owner_id,
                    owner_team_id, owner_user_principal_id, connector_key,
                    external_account_reference, credential_id, status, version,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES ('%s', '%s', '%s', '%s', %s, %s, 'github',
                    'safe-account-reference', '%s', 'ACTIVE', 0,
                    TIMESTAMPTZ '2026-08-23 09:00:00+00', '%s',
                    TIMESTAMPTZ '2026-08-23 09:00:00+00', '%s')
                """.formatted(
                connectionId, f.organizationId(), ownerType, ownerId, team, principal,
                id("github-credential-" + connectionId), f.actorId(), f.actorId());
    }

    private static void insertGithubProfile(
            UUID profileId,
            GraphFixture f,
            UUID connectionId,
            String ownerType,
            UUID ownerId,
            String externalIdentity,
            String authenticationType,
            String externalAccountId) throws SQLException {
        execute(
                """
                INSERT INTO crewscope.github_connection_profile (
                    id, organization_id, connection_id, connection_version,
                    connection_owner_type, connection_owner_id, external_identity,
                    authentication_type, external_account_id, external_account_login,
                    granted_permissions, repository_allowlist_hash, status, version,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, 0, ?, ?, ?, ?, ?, 'safe-login',
                    '{"contents":"write","metadata":"read","pull_requests":"write"}'::jsonb,
                    ?, 'ACTIVE', 0, TIMESTAMPTZ '2026-08-23 09:01:00+00', ?,
                    TIMESTAMPTZ '2026-08-23 09:01:00+00', ?)
                """,
                profileId, f.organizationId(), connectionId, ownerType, ownerId,
                externalIdentity, authenticationType, externalAccountId,
                HASH_A, f.actorId(), f.actorId());
    }

    private static void insertGithubRepository(
            UUID repositoryId,
            GraphFixture f,
            UUID connectionId,
            String externalIdentity,
            String externalRepositoryId,
            String owner,
            String name) throws SQLException {
        execute(
                """
                INSERT INTO crewscope.github_repository_catalog_entry (
                    id, organization_id, connection_id, connection_version,
                    external_identity, external_repository_id, owner_login,
                    repository_name, full_name, default_branch, visibility,
                    archived, fork, can_pull, can_push, can_create_pull_request,
                    permissions_hash, discovered_at, cache_expires_at, status, version,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, 0, ?, ?, ?, ?, ?, 'main', 'PRIVATE',
                    FALSE, FALSE, TRUE, TRUE, TRUE, ?,
                    TIMESTAMPTZ '2026-08-23 09:02:00+00',
                    TIMESTAMPTZ '2026-08-23 09:07:00+00', 'DELIVERABLE', 0,
                    TIMESTAMPTZ '2026-08-23 09:02:00+00', ?,
                    TIMESTAMPTZ '2026-08-23 09:02:00+00', ?)
                """,
                repositoryId, f.organizationId(), connectionId, externalIdentity,
                externalRepositoryId, owner, name, owner + "/" + name,
                HASH_B, f.actorId(), f.actorId());
    }

    private static GraphFixture seedRepresentativeGraph() throws SQLException {
        GraphFixture fixture = GraphFixture.create();
        executeReplica(
                """
                INSERT INTO crewscope.organization (id, name, status)
                VALUES ('%s', 'V21 Fixture', 'ACTIVE')
                """.formatted(fixture.organizationId()),
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, status
                ) VALUES ('%s', '%s', 'USER', 'V21 Actor', 'ACTIVE')
                """.formatted(fixture.actorId(), fixture.organizationId()),
                reviewSubjectSql(fixture),
                reviewContextSql(fixture),
                """
                INSERT INTO crewscope.review_context_hunk (
                    context_package_id, ordinal, path, start_line, end_line,
                    patch_bytes, patch_hash
                ) VALUES ('%s', 1, 'src/Main.java', 1, 10, 128, '%s')
                """.formatted(fixture.contextId(), HASH_A),
                reviewRequestSql(fixture),
                reviewRequestStateSql(fixture),
                reviewFindingSql(fixture),
                reviewDecisionSql(fixture),
                actionBundleSql(fixture),
                plannedPushSql(fixture),
                plannedPullRequestSql(fixture),
                """
                INSERT INTO crewscope.planned_action_dependency (
                    action_bundle_id, action_id, predecessor_action_id
                ) VALUES ('%s', '%s', '%s')
                """.formatted(
                        fixture.bundleId(), fixture.pullRequestActionId(), fixture.pushActionId()),
                confirmationSql(fixture),
                confirmationActionSql(fixture, fixture.pushActionId(), 1, HASH_B),
                confirmationActionSql(fixture, fixture.pullRequestActionId(), 2, HASH_C),
                receiptSql(fixture),
                pushDispatchSql(fixture),
                pullRequestDispatchSql(fixture),
                """
                INSERT INTO crewscope.action_dispatch_dependency (
                    action_dispatch_id, predecessor_action_id
                ) VALUES ('%s', '%s')
                """.formatted(fixture.pullRequestDispatchId(), fixture.pushActionId()),
                observationSql(fixture),
                externalResultSql(fixture));
        return fixture;
    }

    private static String reviewSubjectSql(GraphFixture f) {
        return """
                INSERT INTO crewscope.review_subject (
                    id, organization_id, team_id, workspace_id, project_id,
                    task_id, task_execution_id, attempt, subject_type,
                    diff_artifact_id, diff_final_hash, coding_target_snapshot_id,
                    coding_target_revision, coding_target_hash, baseline_commit,
                    delivery_commit, diff_generation, diff_manifest_hash,
                    patch_artifact_id, patch_size_bytes, patch_sha256,
                    changed_paths, subject_hash, created_at, created_by_principal_id
                ) VALUES (
                    '%s', '%s', '%s', '%s', '%s', '%s', '%s', 1, 'CODE_CHANGE',
                    '%s', '%s', '%s', 1, '%s', '%s', '%s', 1, '%s',
                    '%s', 128, '%s', '["src/Main.java"]'::jsonb, '%s',
                    TIMESTAMPTZ '2026-08-23 10:00:00+00', '%s')
                """.formatted(
                f.subjectId(), f.organizationId(), f.teamId(), f.workspaceId(), f.projectId(),
                f.taskId(), f.taskExecutionId(), f.diffId(), HASH_A, f.codingTargetId(), HASH_A,
                COMMIT_A, COMMIT_B, HASH_B, f.patchArtifactId(), HASH_C, HASH_B, f.actorId());
    }

    private static String reviewContextSql(GraphFixture f) {
        return """
                INSERT INTO crewscope.review_context_package (
                    id, organization_id, team_id, workspace_id, project_id,
                    task_id, task_execution_id, attempt, package_version,
                    subject_id, subject_type, subject_hash, diff_artifact_id,
                    diff_final_hash, coding_target_snapshot_id, coding_target_revision,
                    coding_target_hash, diff_generation, diff_manifest_hash,
                    test_evidence_id, test_evidence_hash, reviewer_agent_profile_id,
                    reviewer_agent_profile_version, reviewer_agent_principal_id,
                    reviewer_relationship, reviewer_template_key, reviewer_template_version,
                    reviewer_template_hash, reviewer_configuration_revision,
                    reviewer_configuration_hash, policy_snapshot_id,
                    policy_snapshot_revision, policy_snapshot_hash,
                    context_hash, authority_snapshot, created_at, created_by_principal_id
                ) VALUES (
                    '%s', '%s', '%s', '%s', '%s', '%s', '%s', 1, 1,
                    '%s', 'CODE_CHANGE', '%s', '%s', '%s', '%s', 1, '%s', 1, '%s',
                    '%s', '%s', '%s', 0, '%s', 'INDEPENDENT', 'reviewer', 1,
                    '%s', 1, '%s', '%s', 1, '%s', '%s', '{}'::jsonb,
                    TIMESTAMPTZ '2026-08-23 10:00:01+00', '%s')
                """.formatted(
                f.contextId(), f.organizationId(), f.teamId(), f.workspaceId(), f.projectId(),
                f.taskId(), f.taskExecutionId(), f.subjectId(), HASH_B, f.diffId(), HASH_A,
                f.codingTargetId(), HASH_A, HASH_B, f.testEvidenceId(), HASH_A,
                f.reviewerProfileId(), f.reviewerPrincipalId(), HASH_A, HASH_B,
                f.policySnapshotId(), HASH_A, HASH_C, f.actorId());
    }

    private static String reviewRequestSql(GraphFixture f) {
        return """
                INSERT INTO crewscope.review_request (
                    id, organization_id, team_id, workspace_id, project_id,
                    task_id, task_execution_id, attempt, revision,
                    subject_id, subject_type, subject_hash, context_package_id,
                    context_package_version, context_hash, request_hash, status,
                    version, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id
                ) VALUES (
                    '%s', '%s', '%s', '%s', '%s', '%s', '%s', 1, 1,
                    '%s', 'CODE_CHANGE', '%s', '%s', 1, '%s', '%s', 'COMPLETED', 2,
                    TIMESTAMPTZ '2026-08-23 10:00:02+00', '%s',
                    TIMESTAMPTZ '2026-08-23 10:00:04+00', '%s')
                """.formatted(
                f.requestId(), f.organizationId(), f.teamId(), f.workspaceId(), f.projectId(),
                f.taskId(), f.taskExecutionId(), f.subjectId(), HASH_B, f.contextId(), HASH_C,
                HASH_D, f.actorId(), f.actorId());
    }

    private static String reviewFindingSql(GraphFixture f) {
        return """
                INSERT INTO crewscope.review_finding (
                    id, organization_id, team_id, workspace_id, project_id,
                    task_id, task_execution_id, attempt, review_request_id,
                    review_request_revision, review_request_version, review_request_hash,
                    reviewer_mode, reviewer_relationship, reviewer_principal_id,
                    severity, category, title, claim, suggested_fix,
                    fingerprint, candidate_hash, created_at, created_by_principal_id
                ) VALUES (
                    '%s', '%s', '%s', '%s', '%s', '%s', '%s', 1, '%s', 1, 2, '%s',
                    'ADVISORY', 'INDEPENDENT', '%s', 'LOW', 'MAINTAINABILITY',
                    'Extract helper', 'The method is long', 'Extract one helper method',
                    '%s', '%s', TIMESTAMPTZ '2026-08-23 10:00:05+00', '%s')
                """.formatted(
                f.findingId(), f.organizationId(), f.teamId(), f.workspaceId(), f.projectId(),
                f.taskId(), f.taskExecutionId(), f.requestId(), HASH_D,
                f.reviewerPrincipalId(), HASH_A, HASH_B, f.reviewerPrincipalId());
    }

    private static String reviewRequestStateSql(GraphFixture f) {
        return """
                INSERT INTO crewscope.review_request_state (
                    review_request_id, organization_id, team_id, workspace_id, project_id,
                    task_id, task_execution_id, attempt, revision, request_version,
                    request_hash, status, recorded_at, recorded_by_principal_id
                ) VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s', 1, 1, 2,
                    '%s', 'COMPLETED', TIMESTAMPTZ '2026-08-23 10:00:04+00', '%s')
                """.formatted(
                f.requestId(), f.organizationId(), f.teamId(), f.workspaceId(), f.projectId(),
                f.taskId(), f.taskExecutionId(), HASH_D, f.actorId());
    }

    private static String reviewDecisionSql(GraphFixture f) {
        return """
                INSERT INTO crewscope.review_decision (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    task_id, task_execution_id, attempt, review_request_id,
                    review_request_revision, review_request_version, review_request_hash,
                    revision, reviewer_mode, reviewer_principal_id, reviewer_member_id,
                    eligibility_mode, eligibility_reason, decision_type, rationale,
                    decision_hash, created_at, created_by_principal_id
                ) VALUES (
                    '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', 1, '%s', 1, 2, '%s',
                    1, 'GATE', '%s', '%s', 'INDEPENDENT_MEMBER', 'independent member',
                    'APPROVED', 'Evidence satisfies the acceptance criteria', '%s',
                    TIMESTAMPTZ '2026-08-23 10:00:06+00', '%s')
                """.formatted(
                f.decisionId(), f.organizationId(), f.teamId(), f.workspaceId(), f.projectId(),
                f.workItemId(), f.taskId(), f.taskExecutionId(), f.requestId(), HASH_D,
                f.actorId(), f.memberId(), HASH_A, f.actorId());
    }

    private static String actionBundleSql(GraphFixture f) {
        return """
                INSERT INTO crewscope.action_bundle (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    task_id, task_execution_id, attempt, review_decision_id,
                    review_decision_revision, review_decision_type, review_decision_hash,
                    review_request_id, review_request_revision, review_request_version,
                    review_request_hash, review_subject_id, review_subject_hash,
                    review_context_package_id, review_context_hash,
                    review_diff_artifact_id, review_diff_final_hash,
                    responsibility_assignment_id, responsibility_version,
                    responsibility_role, responsibility_principal_id,
                    provider_binding_id, provider_binding_version,
                    provider_definition_id, provider_definition_version,
                    provider_implementation_id, provider_implementation_version,
                    provider_type, provider_execution_identity, connection_id,
                    connection_version, connection_grant_id, connection_grant_version,
                    effective_access_hash, policy_snapshot_id, policy_snapshot_revision,
                    policy_snapshot_hash, safety_overlay_id, safety_overlay_version,
                    safety_overlay_hash, repository_binding_id, repository_binding_version,
                    repository_key, default_branch, coding_target_snapshot_id,
                    coding_target_revision, coding_target_hash, baseline_commit,
                    delivery_commit, authority_snapshot, valid_until, bundle_digest,
                    version, created_at, created_by_principal_id
                ) VALUES (
                    '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', 1,
                    '%s', 1, 'APPROVED', '%s', '%s', 1, 2, '%s', '%s', '%s',
                    '%s', '%s', '%s', '%s', '%s', 0, 'OWNER', '%s',
                    '%s', 0, '%s', 0, '%s', 0, 'SOURCE_CODE', 'TEAM_SERVICE_ACCOUNT',
                    '%s', 0, '%s', 0, '%s', '%s', 1, '%s', '%s', 1, '%s',
                    '%s', 0, 'crewscope', 'main', '%s', 1, '%s', '%s', '%s',
                    '{}'::jsonb, TIMESTAMPTZ '2026-08-23 11:00:00+00', '%s', 0,
                    TIMESTAMPTZ '2026-08-23 10:00:07+00', '%s')
                """.formatted(
                f.bundleId(), f.organizationId(), f.teamId(), f.workspaceId(), f.projectId(),
                f.workItemId(), f.taskId(), f.taskExecutionId(), f.decisionId(), HASH_A,
                f.requestId(), HASH_D, f.subjectId(), HASH_B, f.contextId(), HASH_C,
                f.diffId(), HASH_A, f.responsibilityId(), f.actorId(), f.providerBindingId(),
                f.providerDefinitionId(), f.providerImplementationId(), f.connectionId(),
                f.connectionGrantId(), HASH_A, f.policySnapshotId(), HASH_A,
                f.safetyOverlayId(), HASH_A, f.repositoryBindingId(), f.codingTargetId(),
                HASH_A, COMMIT_A, COMMIT_B, HASH_A, f.actorId());
    }

    private static String plannedPushSql(GraphFixture f) {
        return """
                INSERT INTO crewscope.planned_action (
                    id, action_bundle_id, sequence, action_kind, external_repository_id,
                    connection_id, branch_full_ref, delivery_head, expected_remote_head,
                    parameter_snapshot, risk, valid_until, action_digest
                ) VALUES ('%s', '%s', 1, 'PUSH_BRANCH', '10001', '%s',
                    'refs/heads/crewscope/delivery', '%s', '%s', '{}'::jsonb,
                    'HIGH_RISK_WRITE', TIMESTAMPTZ '2026-08-23 11:00:00+00', '%s')
                """.formatted(
                f.pushActionId(), f.bundleId(), f.connectionId(), COMMIT_B, COMMIT_A, HASH_B);
    }

    private static String plannedPullRequestSql(GraphFixture f) {
        return """
                INSERT INTO crewscope.planned_action (
                    id, action_bundle_id, sequence, action_kind, external_repository_id,
                    connection_id, pr_head, pr_base, pr_head_sha, pr_title, pr_body,
                    pr_draft, parameter_snapshot, risk, valid_until, action_digest
                ) VALUES ('%s', '%s', 2, 'CREATE_DRAFT_PR', '10001', '%s',
                    'crewscope/delivery', 'main', '%s', 'Deliver change', 'Evidence attached',
                    TRUE, '{}'::jsonb, 'LOW_RISK_WRITE',
                    TIMESTAMPTZ '2026-08-23 11:00:00+00', '%s')
                """.formatted(
                f.pullRequestActionId(), f.bundleId(), f.connectionId(), COMMIT_B, HASH_C);
    }

    private static String confirmationSql(GraphFixture f) {
        return """
                INSERT INTO crewscope.action_confirmation (
                    id, organization_id, team_id, workspace_id, project_id,
                    action_bundle_id, bundle_digest, confirmed_by_principal_id,
                    confirmed_at, valid_until, status, version,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s',
                    TIMESTAMPTZ '2026-08-23 10:00:08+00',
                    TIMESTAMPTZ '2026-08-23 11:00:00+00', 'ACTIVE', 0,
                    TIMESTAMPTZ '2026-08-23 10:00:08+00', '%s',
                    TIMESTAMPTZ '2026-08-23 10:00:08+00', '%s')
                """.formatted(
                f.confirmationId(), f.organizationId(), f.teamId(), f.workspaceId(),
                f.projectId(), f.bundleId(), HASH_A, f.actorId(), f.actorId(), f.actorId());
    }

    private static String confirmationActionSql(
            GraphFixture f, UUID actionId, int sequence, String digest) {
        return """
                INSERT INTO crewscope.confirmation_action (
                    confirmation_id, action_bundle_id, action_id, sequence, action_digest
                ) VALUES ('%s', '%s', '%s', %d, '%s')
                """.formatted(f.confirmationId(), f.bundleId(), actionId, sequence, digest);
    }

    private static String receiptSql(GraphFixture f) {
        return """
                INSERT INTO crewscope.action_receipt (
                    id, organization_id, team_id, workspace_id, project_id,
                    action_bundle_id, bundle_digest, action_dispatch_id, action_id,
                    action_digest, idempotency_key, result, source,
                    connection_id, external_object_type, external_id,
                    external_business_key, target_version, evidence_code,
                    evidence_hash, resolved_by_principal_id, received_at,
                    created_at, created_by_principal_id
                ) VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s',
                    '%s', '%s', 'SUCCEEDED', 'ACTIVE_QUERY', '%s', 'BRANCH', 'refs/heads/crewscope/delivery',
                    '10001:refs/heads/crewscope/delivery', '%s', 'REMOTE_HEAD_VERIFIED',
                    '%s', NULL, TIMESTAMPTZ '2026-08-23 10:00:10+00',
                    TIMESTAMPTZ '2026-08-23 10:00:10+00', '%s')
                """.formatted(
                f.receiptId(), f.organizationId(), f.teamId(), f.workspaceId(), f.projectId(),
                f.bundleId(), HASH_A, f.pushDispatchId(), f.pushActionId(), HASH_B, HASH_B,
                f.connectionId(), COMMIT_B, HASH_A, f.actorId());
    }

    private static String pushDispatchSql(GraphFixture f) {
        return """
                INSERT INTO crewscope.action_dispatch (
                    id, organization_id, team_id, workspace_id, project_id,
                    action_bundle_id, bundle_digest, confirmation_id, action_id,
                    action_digest, sequence, idempotency_key, valid_until, status,
                    last_fencing_token, claim_attempts, reconciliation_attempts,
                    not_before, receipt_id, compensation_disposition, version,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s',
                    '%s', 1, '%s', TIMESTAMPTZ '2026-08-23 11:00:00+00', 'SUCCEEDED',
                    1, 1, 0, TIMESTAMPTZ '2026-08-23 10:00:09+00', '%s',
                    'NOT_REQUIRED', 2, TIMESTAMPTZ '2026-08-23 10:00:09+00', '%s',
                    TIMESTAMPTZ '2026-08-23 10:00:10+00', '%s')
                """.formatted(
                f.pushDispatchId(), f.organizationId(), f.teamId(), f.workspaceId(), f.projectId(),
                f.bundleId(), HASH_A, f.confirmationId(), f.pushActionId(), HASH_B, HASH_B,
                f.receiptId(), f.actorId(), f.actorId());
    }

    private static String pullRequestDispatchSql(GraphFixture f) {
        return """
                INSERT INTO crewscope.action_dispatch (
                    id, organization_id, team_id, workspace_id, project_id,
                    action_bundle_id, bundle_digest, confirmation_id, action_id,
                    action_digest, sequence, idempotency_key, valid_until, status,
                    last_fencing_token, claim_attempts, reconciliation_attempts,
                    not_before, compensation_disposition, version,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s',
                    '%s', 2, '%s', TIMESTAMPTZ '2026-08-23 11:00:00+00', 'READY',
                    0, 0, 0, TIMESTAMPTZ '2026-08-23 10:00:09+00',
                    'NOT_REQUIRED', 0, TIMESTAMPTZ '2026-08-23 10:00:09+00', '%s',
                    TIMESTAMPTZ '2026-08-23 10:00:09+00', '%s')
                """.formatted(
                f.pullRequestDispatchId(), f.organizationId(), f.teamId(), f.workspaceId(),
                f.projectId(), f.bundleId(), HASH_A, f.confirmationId(),
                f.pullRequestActionId(), HASH_C, HASH_C, f.actorId(), f.actorId());
    }

    private static String observationSql(GraphFixture f) {
        return """
                INSERT INTO crewscope.external_observation (
                    id, organization_id, team_id, workspace_id, project_id,
                    action_bundle_id, action_id, action_digest, observation_key,
                    connection_id, external_object_type, external_id,
                    external_business_key, external_status, provider_version,
                    source, evidence_code, evidence_hash, observed_at,
                    created_at, created_by_principal_id
                ) VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s',
                    '%s', 'BRANCH', 'refs/heads/crewscope/delivery',
                    '10001:refs/heads/crewscope/delivery', 'PRESENT', 1,
                    'ACTIVE_QUERY', 'REMOTE_HEAD_VERIFIED', '%s',
                    TIMESTAMPTZ '2026-08-23 10:00:11+00',
                    TIMESTAMPTZ '2026-08-23 10:00:11+00', '%s')
                """.formatted(
                f.observationId(), f.organizationId(), f.teamId(), f.workspaceId(), f.projectId(),
                f.bundleId(), f.pushActionId(), HASH_B, HASH_A, f.connectionId(), HASH_B, f.actorId());
    }

    private static String externalResultSql(GraphFixture f) {
        return """
                INSERT INTO crewscope.external_result (
                    id, organization_id, team_id, workspace_id, project_id,
                    action_bundle_id, action_id, action_digest, connection_id,
                    external_object_type, external_id, external_business_key,
                    external_status, provider_version, last_source,
                    last_observation_key, last_evidence_code, last_evidence_hash,
                    observed_at, version, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id
                ) VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s',
                    'BRANCH', 'refs/heads/crewscope/delivery',
                    '10001:refs/heads/crewscope/delivery', 'PRESENT', 1,
                    'ACTIVE_QUERY', '%s', 'REMOTE_HEAD_VERIFIED', '%s',
                    TIMESTAMPTZ '2026-08-23 10:00:11+00', 0,
                    TIMESTAMPTZ '2026-08-23 10:00:11+00', '%s',
                    TIMESTAMPTZ '2026-08-23 10:00:11+00', '%s')
                """.formatted(
                f.externalResultId(), f.organizationId(), f.teamId(), f.workspaceId(), f.projectId(),
                f.bundleId(), f.pushActionId(), HASH_B, f.connectionId(), HASH_A, HASH_B,
                f.actorId(), f.actorId());
    }

    private static void seedStaleFencingDispatch(GraphFixture f) throws SQLException {
        executeReplica(
                """
                INSERT INTO crewscope.planned_action (
                    id, action_bundle_id, sequence, action_kind, external_repository_id,
                    connection_id, pr_head, pr_base, pr_head_sha, pr_title, pr_body,
                    pr_draft, parameter_snapshot, risk, valid_until, action_digest
                ) VALUES ('%s', '%s', 3, 'CREATE_DRAFT_PR', '10001', '%s',
                    'crewscope/other', 'main', '%s', 'Other PR', 'Other body', TRUE,
                    '{}'::jsonb, 'LOW_RISK_WRITE',
                    TIMESTAMPTZ '2026-08-23 11:00:00+00', '%s')
                """.formatted(f.staleActionId(), f.bundleId(), f.connectionId(), COMMIT_B, HASH_D),
                """
                INSERT INTO crewscope.action_dispatch (
                    id, organization_id, team_id, workspace_id, project_id,
                    action_bundle_id, bundle_digest, confirmation_id, action_id,
                    action_digest, sequence, idempotency_key, valid_until, status,
                    claim_worker_id, claim_fencing_token, claim_mode,
                    claim_acquired_at, claim_last_heartbeat_at, claim_lease_until,
                    last_fencing_token, claim_attempts, reconciliation_attempts,
                    not_before, compensation_disposition, version,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s',
                    '%s', 3, '%s', TIMESTAMPTZ '2026-08-23 11:00:00+00', 'RUNNING',
                    'new-worker', 2, 'EXECUTE',
                    TIMESTAMPTZ '2026-08-23 10:09:00+00',
                    TIMESTAMPTZ '2026-08-23 10:09:00+00',
                    TIMESTAMPTZ '2026-08-23 10:10:00+00',
                    2, 2, 0, TIMESTAMPTZ '2026-08-23 10:09:00+00',
                    'NOT_REQUIRED', 1, TIMESTAMPTZ '2026-08-23 10:09:00+00', '%s',
                    TIMESTAMPTZ '2026-08-23 10:09:00+00', '%s')
                """.formatted(
                        f.staleDispatchId(), f.organizationId(), f.teamId(), f.workspaceId(),
                        f.projectId(), f.bundleId(), HASH_A, f.confirmationId(),
                        f.staleActionId(), HASH_D, HASH_E, f.actorId(), f.actorId()));
    }

    /**
     * Seeds a closed representative V21 graph without rebuilding every V1-V20 aggregate fixture.
     * PostgreSQL still evaluates NOT NULL, CHECK and UNIQUE constraints; only FK/append triggers are
     * suspended during this trusted fixture load. Negative cases execute with normal trigger mode.
     */
    private static void executeReplica(String... statements) throws SQLException {
        try (Connection connection = openConnection(POSTGRES.getJdbcUrl());
                Statement statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = replica");
            try {
                for (String sql : statements) {
                    statement.executeUpdate(sql);
                }
            } finally {
                statement.execute("SET session_replication_role = origin");
            }
        }
    }

    private static String statementSql(String sql) {
        return sql;
    }

    private static void resetSchemas() throws SQLException {
        try (Connection connection = openConnection(POSTGRES.getJdbcUrl());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS " + ALTERNATE_SCHEMA + " CASCADE");
            statement.execute("CREATE SCHEMA " + ALTERNATE_SCHEMA);
        }
    }

    private static Flyway flyway(String jdbcUrl, MigrationVersion target) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas("crewscope")
                .defaultSchema("crewscope")
                .createSchemas(true)
                .validateMigrationNaming(true)
                .target(target);
        return configuration.load();
    }

    private static Set<String> columns(String table) throws SQLException {
        return queryStrings(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'crewscope' AND table_name = '" + table + "'");
    }

    private static Set<String> existingTables(String schema, Set<String> expected)
            throws SQLException {
        String placeholders = String.join(",", expected.stream().map(value -> "?").toList());
        Set<String> values = new HashSet<>();
        try (Connection connection = openConnection(POSTGRES.getJdbcUrl());
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT table_name FROM information_schema.tables "
                                + "WHERE table_schema = ? AND table_name IN (" + placeholders + ")")) {
            statement.setString(1, schema);
            int index = 2;
            for (String table : expected) {
                statement.setString(index++, table);
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    values.add(result.getString(1));
                }
            }
        }
        return Set.copyOf(values);
    }

    private static int tableCount(String schema, String table) throws SQLException {
        try (Connection connection = openConnection(POSTGRES.getJdbcUrl());
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM information_schema.tables "
                                + "WHERE table_schema = ? AND table_name = ?")) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private static int queryInt(String sql) throws SQLException {
        try (Connection connection = openConnection(POSTGRES.getJdbcUrl());
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static Set<String> queryStrings(String sql) throws SQLException {
        Set<String> values = new HashSet<>();
        try (Connection connection = openConnection(POSTGRES.getJdbcUrl());
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return Set.copyOf(values);
    }

    private static void execute(String sql, Object... parameters) throws SQLException {
        try (Connection connection = openConnection(POSTGRES.getJdbcUrl());
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            statement.executeUpdate();
        }
    }

    private static void assertSqlState(String expected, SqlAction action) {
        SQLException exception = assertThrows(SQLException.class, action::run);
        assertEquals(expected, exception.getSQLState(), exception.getMessage());
    }

    private static Connection openConnection(String jdbcUrl) throws SQLException {
        return DriverManager.getConnection(
                jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String jdbcUrlWithCurrentSchema(String schema) {
        String separator = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
        return POSTGRES.getJdbcUrl() + separator + "currentSchema=" + schema;
    }

    private static String currentSchema(String jdbcUrl) throws SQLException {
        try (Connection connection = openConnection(jdbcUrl);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT current_schema()")) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static UUID id(String name) {
        return UUID.nameUUIDFromBytes(("v21:" + name).getBytes(StandardCharsets.UTF_8));
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }

    private record GraphFixture(
            UUID organizationId,
            UUID teamId,
            UUID workspaceId,
            UUID projectId,
            UUID workItemId,
            UUID taskId,
            UUID taskExecutionId,
            UUID actorId,
            UUID memberId,
            UUID reviewerPrincipalId,
            UUID reviewerProfileId,
            UUID diffId,
            UUID patchArtifactId,
            UUID codingTargetId,
            UUID testEvidenceId,
            UUID policySnapshotId,
            UUID safetyOverlayId,
            UUID subjectId,
            UUID contextId,
            UUID requestId,
            UUID findingId,
            UUID decisionId,
            UUID responsibilityId,
            UUID providerBindingId,
            UUID providerDefinitionId,
            UUID providerImplementationId,
            UUID connectionId,
            UUID connectionGrantId,
            UUID repositoryBindingId,
            UUID bundleId,
            UUID pushActionId,
            UUID pullRequestActionId,
            UUID confirmationId,
            UUID pushDispatchId,
            UUID pullRequestDispatchId,
            UUID receiptId,
            UUID observationId,
            UUID externalResultId,
            UUID staleActionId,
            UUID staleDispatchId) {

        private static GraphFixture create() {
            return new GraphFixture(
                    id("organization"), id("team"), id("workspace"), id("project"),
                    id("work-item"), id("task"), id("task-execution"), id("actor"),
                    id("member"), id("reviewer-principal"), id("reviewer-profile"),
                    id("diff"), id("patch-artifact"), id("coding-target"),
                    id("test-evidence"), id("policy"), id("safety"), id("subject"),
                    id("context"), id("request"), id("finding"), id("decision"),
                    id("responsibility"), id("provider-binding"), id("provider-definition"),
                    id("provider-implementation"), id("connection"), id("connection-grant"),
                    id("repository-binding"), id("bundle"), id("push-action"),
                    id("pull-request-action"), id("confirmation"), id("push-dispatch"),
                    id("pull-request-dispatch"), id("receipt"), id("observation"),
                    id("external-result"), id("stale-action"), id("stale-dispatch"));
        }
    }
}
