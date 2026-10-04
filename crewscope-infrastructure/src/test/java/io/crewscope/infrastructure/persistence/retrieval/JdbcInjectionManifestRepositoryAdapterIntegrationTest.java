package io.crewscope.infrastructure.persistence.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.crewscope.application.retrieval.InjectionManifestConflictException;
import io.crewscope.domain.retrieval.DegradationReasonCode;
import io.crewscope.domain.retrieval.InjectionManifest;
import io.crewscope.domain.retrieval.InjectionManifestId;
import io.crewscope.domain.retrieval.ManifestSourceRef;
import io.crewscope.domain.retrieval.ManifestSourceStage;
import io.crewscope.domain.retrieval.ManifestSourceType;
import io.crewscope.domain.retrieval.PromptBudgetSnapshot;
import io.crewscope.domain.retrieval.TrimRecord;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M10-I02b PostgreSQL proof behind the V59 schema: append derives the six scope columns
 * from the execution row, the four JSONB evidence columns round-trip into the frozen
 * domain records, the (execution_id, attempt) unique violation translates into the
 * conflict exception, and lookups stay empty for missing attempts, unknown executions
 * and foreign tenants.
 */
@SpringBootTest(
        classes = JdbcInjectionManifestRepositoryAdapterIntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "crewscope.outbox.enabled=false"
        })
class JdbcInjectionManifestRepositoryAdapterIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-04T09:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JdbcInjectionManifestRepositoryAdapter manifests;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(JdbcInjectionManifestRepositoryAdapter.class)
    static class TestApplication {

        @Bean
        ObjectMapper evidenceObjectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private TaskExecutionId executionId;

    @BeforeEach
    void seedExecutionChain() {
        jdbc.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID workItemId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID responsibilitySnapshotId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID execution = UUID.randomUUID();
        String responsibilityHash = "b".repeat(64);

        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Manifest Org', 'ACTIVE')",
                organizationId.value());
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status) VALUES (?, ?, 'Manifest Team', 'ACTIVE')",
                teamId.value(), organizationId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.workspace (id, organization_id, team_id, workspace_type, name, status)
                VALUES (?, ?, ?, 'TEAM', 'Manifest Workspace', 'ACTIVE')
                """,
                workspaceId, organizationId.value(), teamId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.work_project (id, organization_id, team_id, workspace_id, project_key, name)
                VALUES (?, ?, ?, ?, ?, 'Manifest Project')
                """,
                projectId, organizationId.value(), teamId.value(), workspaceId,
                "IM-" + UUID.randomUUID().toString().substring(0, 6));
        jdbc.update(
                """
                INSERT INTO crewscope.principal (id, organization_id, principal_type, display_name, status)
                VALUES (?, ?, 'USER', 'Manifest creator', 'ACTIVE')
                """,
                actorId, organizationId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.team_member (
                    id, organization_id, team_id, user_principal_id, status, join_method, joined_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', 'BOOTSTRAP', CURRENT_TIMESTAMP)
                """,
                memberId, organizationId.value(), teamId.value(), actorId);
        jdbc.update(
                """
                INSERT INTO crewscope.work_item (
                    id, organization_id, team_id, workspace_id, project_id,
                    item_key, item_type, title, status, priority)
                VALUES (?, ?, ?, ?, ?, ?, 'TASK', 'Manifest work item', 'READY', 'MEDIUM')
                """,
                workItemId, organizationId.value(), teamId.value(), workspaceId, projectId,
                "IM-" + UUID.randomUUID().toString().substring(0, 6));
        jdbc.update(
                """
                INSERT INTO crewscope.responsibility_assignment (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    role, actor_principal_id, actor_type, actor_member_id, status,
                    assigned_by_principal_id, assigned_at, accepted_at,
                    created_by_principal_id, updated_by_principal_id)
                VALUES (?, ?, ?, ?, ?, ?, 'EXECUTOR', ?, 'USER', ?, 'ACTIVE', ?,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """,
                assignmentId, organizationId.value(), teamId.value(), workspaceId, projectId,
                workItemId, actorId, memberId, actorId, actorId, actorId);
        jdbc.update(
                """
                INSERT INTO crewscope.task_responsibility_snapshot (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    snapshot_hash, captured_at, created_at, created_by_principal_id, updated_by_principal_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """,
                responsibilitySnapshotId, organizationId.value(), teamId.value(), workspaceId,
                projectId, workItemId, responsibilityHash, actorId, actorId);
        jdbc.update(
                """
                INSERT INTO crewscope.task_responsibility_snapshot_entry (
                    snapshot_id, organization_id, team_id, workspace_id, project_id,
                    work_item_id, assignment_id, assignment_version, role,
                    principal_id, principal_type, member_id, assigned_at, accepted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, 'EXECUTOR', ?, 'USER', ?,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                responsibilitySnapshotId, organizationId.value(), teamId.value(), workspaceId,
                projectId, workItemId, assignmentId, actorId, memberId);
        jdbc.update(
                """
                INSERT INTO crewscope.task (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    source_type, source_work_item_version, responsibility_snapshot_id,
                    objective, acceptance_criteria, status,
                    created_by_principal_id, updated_by_principal_id)
                VALUES (?, ?, ?, ?, ?, ?, 'WORK_ITEM', 0, ?, 'Implement the endpoint',
                        '["Tests pass"]'::jsonb, 'CREATED', ?, ?)
                """,
                taskId, organizationId.value(), teamId.value(), workspaceId, projectId,
                workItemId, responsibilitySnapshotId, actorId, actorId);
        jdbc.update(
                """
                INSERT INTO crewscope.task_execution (
                    id, organization_id, team_id, workspace_id, project_id, task_id,
                    attempt, max_attempts, priority, not_before, status,
                    created_by_principal_id, updated_by_principal_id)
                VALUES (?, ?, ?, ?, ?, ?, 1, 3, 50, CURRENT_TIMESTAMP, 'CREATED', ?, ?)
                """,
                execution, organizationId.value(), teamId.value(), workspaceId, projectId,
                taskId, actorId, actorId);
        executionId = new TaskExecutionId(execution);
    }

    @Test
    void appendDerivesTheScopeColumnsAndRoundTripsEveryEvidenceColumn() {
        InjectionManifest sealed = manifest(1);

        InjectionManifest stored = manifests.append(sealed);

        assertEquals(sealed, stored);
        assertEquals(Optional.of(sealed), manifests.findByAttempt(
                organizationId, teamId, executionId, 1));
        // The six scope columns were derived from the execution row, not supplied.
        UUID storedOrganization = jdbc.queryForObject(
                "SELECT organization_id FROM crewscope.injection_manifest WHERE id = ?",
                UUID.class, sealed.id().value());
        assertEquals(organizationId.value(), storedOrganization);
    }

    @Test
    void appendingTheSameAttemptTwiceTranslatesTheUniqueViolation() {
        manifests.append(manifest(1));

        InjectionManifestConflictException conflict = assertThrows(
                InjectionManifestConflictException.class,
                () -> manifests.append(manifest(1)));

        assertEquals(executionId, conflict.executionId());
        assertEquals(1, conflict.attempt());
        // A new attempt is a new idempotency slot and seals normally.
        manifests.append(manifest(2));
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.injection_manifest WHERE execution_id = ?",
                Long.class, executionId.value());
        assertEquals(2L, rows);
    }

    @Test
    void lookupsStayEmptyForMissingAttemptsAndForeignTenants() {
        assertEquals(Optional.empty(), manifests.findByAttempt(
                organizationId, teamId, executionId, 7));
        assertEquals(Optional.empty(), manifests.findByAttempt(
                OrganizationId.generate(), teamId, executionId, 1));
        assertEquals(Optional.empty(), manifests.findByAttempt(
                organizationId, TeamId.generate(), executionId, 1));
    }

    @Test
    void appendingForAnUnknownExecutionFailsClosed() {
        InjectionManifest orphan = manifest(1);
        InjectionManifest unknown = new InjectionManifest(
                orphan.id(), TaskExecutionId.generate(), orphan.attempt(),
                orphan.references(), orphan.trims(), orphan.budget(),
                orphan.degradations(), orphan.createdAt());

        AggregateNotFoundException missing = assertThrows(
                AggregateNotFoundException.class, () -> manifests.append(unknown));

        assertEquals("AGGREGATE_NOT_FOUND", missing.error().code().name());
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.injection_manifest", Long.class);
        assertEquals(0L, rows);
    }

    // ------------------------------------------------------------------ fixtures

    /** One manifest exercising every reference type, both stages and both trim reasons. */
    private InjectionManifest manifest(int attempt) {
        List<ManifestSourceRef> references = List.of(
                new ManifestSourceRef(ManifestSourceType.SKILL_INSTRUCTION,
                        "java-spring-v1_crewscope-java-spring-v1", 1, hex(0xA5F4),
                        ManifestSourceStage.INJECTED),
                new ManifestSourceRef(ManifestSourceType.KNOWLEDGE_ENTRY,
                        UUID.randomUUID().toString(), 3, hex(0x1001),
                        ManifestSourceStage.INJECTED),
                new ManifestSourceRef(ManifestSourceType.KNOWLEDGE_ENTRY,
                        UUID.randomUUID().toString(), 1, hex(0x1002),
                        ManifestSourceStage.CANDIDATE),
                new ManifestSourceRef(ManifestSourceType.REPOSITORY_CHUNK,
                        "src/Main.java#10-90", 7, hex(0x2001),
                        ManifestSourceStage.INJECTED),
                new ManifestSourceRef(ManifestSourceType.MEMORY_PREFERENCE,
                        "reply-language", 4, hex(0x3001),
                        ManifestSourceStage.CANDIDATE));
        List<TrimRecord> trims = List.of(
                new TrimRecord(ManifestSourceType.REPOSITORY_CHUNK, 2,
                        "layer budget exceeded"),
                new TrimRecord(ManifestSourceType.MEMORY_PREFERENCE, 1,
                        "total budget exceeded"));
        return new InjectionManifest(
                InjectionManifestId.generate(),
                executionId,
                attempt,
                references,
                trims,
                new PromptBudgetSnapshot(8192, 6, 4, 0),
                List.of(DegradationReasonCode.RETRIEVAL_DISABLED,
                        DegradationReasonCode.NO_MATCHING_GENERATION),
                NOW);
    }

    private static String hex(long seed) {
        return (Long.toHexString(seed) + "0".repeat(64)).substring(0, 64);
    }
}
