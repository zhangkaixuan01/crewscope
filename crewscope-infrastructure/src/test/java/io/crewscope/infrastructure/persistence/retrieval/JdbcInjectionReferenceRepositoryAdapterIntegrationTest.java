package io.crewscope.infrastructure.persistence.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.crewscope.application.retrieval.InjectionClaimedReferenceConflictException;
import io.crewscope.application.retrieval.InjectionClaimedReferences;
import io.crewscope.application.retrieval.InjectionFeedbackKind;
import io.crewscope.application.retrieval.InjectionReferenceFeedback;
import io.crewscope.domain.retrieval.DegradationReasonCode;
import io.crewscope.domain.retrieval.InjectionManifest;
import io.crewscope.domain.retrieval.InjectionManifestId;
import io.crewscope.domain.retrieval.ManifestSourceKey;
import io.crewscope.domain.retrieval.ManifestSourceRef;
import io.crewscope.domain.retrieval.ManifestSourceStage;
import io.crewscope.domain.retrieval.ManifestSourceType;
import io.crewscope.domain.retrieval.PromptBudgetSnapshot;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.util.List;
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
 * M10-I02c PostgreSQL proof behind the V60 schema: feedback rows derive their scope
 * columns from the execution row, replay the stored row through the ON CONFLICT
 * boundary instead of duplicating, and stay scoped to one member on read; claimed
 * receipts round-trip the canonical set order, replay an identical set, and surface
 * the stored receipt on a conflicting set; the execution-wide manifest read comes
 * back ascending by attempt and stays empty for foreign tenants.
 */
@SpringBootTest(
        classes = JdbcInjectionReferenceRepositoryAdapterIntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "crewscope.outbox.enabled=false"
        })
class JdbcInjectionReferenceRepositoryAdapterIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final UtcTimestamp FIRST_AT = UtcTimestamp.parse("2026-10-04T08:00:00Z");
    private static final UtcTimestamp SECOND_AT = UtcTimestamp.parse("2026-10-04T10:30:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JdbcInjectionReferenceRepositoryAdapter references;

    @Autowired
    private JdbcInjectionManifestRepositoryAdapter manifests;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({JdbcInjectionReferenceRepositoryAdapter.class,
            JdbcInjectionManifestRepositoryAdapter.class})
    static class TestApplication {

        @Bean
        ObjectMapper evidenceObjectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final PrincipalId member = PrincipalId.generate();
    private final PrincipalId otherMember = PrincipalId.generate();
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
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Evidence Org', 'ACTIVE')",
                organizationId.value());
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status) VALUES (?, ?, 'Evidence Team', 'ACTIVE')",
                teamId.value(), organizationId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.workspace (id, organization_id, team_id, workspace_type, name, status)
                VALUES (?, ?, ?, 'TEAM', 'Evidence Workspace', 'ACTIVE')
                """,
                workspaceId, organizationId.value(), teamId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.work_project (id, organization_id, team_id, workspace_id, project_key, name)
                VALUES (?, ?, ?, ?, ?, 'Evidence Project')
                """,
                projectId, organizationId.value(), teamId.value(), workspaceId,
                "IR-" + UUID.randomUUID().toString().substring(0, 6));
        jdbc.update(
                """
                INSERT INTO crewscope.principal (id, organization_id, principal_type, display_name, status)
                VALUES (?, ?, 'USER', 'Evidence creator', 'ACTIVE')
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
                VALUES (?, ?, ?, ?, ?, ?, 'TASK', 'Evidence work item', 'READY', 'MEDIUM')
                """,
                workItemId, organizationId.value(), teamId.value(), workspaceId, projectId,
                "IR-" + UUID.randomUUID().toString().substring(0, 6));
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

    // ------------------------------------------------------------------ feedback

    @Test
    void feedbackReplaysTheStoredRowInsteadOfDuplicatingIt() {
        ManifestSourceKey knowledge = knowledgeKey();
        references.record(feedback(knowledge, member, FIRST_AT));

        InjectionReferenceFeedback replay = references.record(
                feedback(knowledge, member, SECOND_AT));

        assertEquals(FIRST_AT, replay.createdAt(), "the replay returns the stored row");
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.injection_reference_feedback", Long.class);
        assertEquals(1L, rows);
    }

    @Test
    void feedbackReadsStayScopedToTheRequestingMember() {
        ManifestSourceKey knowledge = knowledgeKey();
        references.record(feedback(knowledge, member, FIRST_AT));
        references.record(feedback(knowledge, otherMember, FIRST_AT));
        references.record(feedback(skillKey(), member, SECOND_AT));

        List<InjectionReferenceFeedback> own = references.findFeedback(
                organizationId, teamId, executionId, member);

        assertEquals(2, own.size(), "one row per source key, never other members' rows");
        assertTrue(own.stream().allMatch(row -> row.memberPrincipalId().equals(member)));
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.injection_reference_feedback", Long.class);
        assertEquals(3L, rows);
    }

    @Test
    void feedbackLookupsStayEmptyForForeignTenantsAndUnknownExecutions() {
        assertTrue(references.findFeedback(
                organizationId, teamId, executionId, member).isEmpty());
        references.record(feedback(knowledgeKey(), member, FIRST_AT));
        assertTrue(references.findFeedback(
                OrganizationId.generate(), teamId, executionId, member).isEmpty());
        assertTrue(references.findFeedback(
                organizationId, TeamId.generate(), executionId, member).isEmpty());

        InjectionReferenceFeedback orphan = feedback(knowledgeKey(), member, FIRST_AT);
        InjectionReferenceFeedback unknown = new InjectionReferenceFeedback(
                TaskExecutionId.generate(), orphan.source(), orphan.memberPrincipalId(),
                orphan.kind(), orphan.createdAt());
        AggregateNotFoundException missing = assertThrows(
                AggregateNotFoundException.class, () -> references.record(unknown));
        assertEquals("AGGREGATE_NOT_FOUND", missing.error().code().name());
    }

    // ------------------------------------------------------------------ claimed

    @Test
    void claimedReceiptsRoundTripInCanonicalOrderAndReplayIdenticalSets() {
        ManifestSourceKey knowledge = knowledgeKey();
        // A shuffled construction input still lands as the canonical set — the
        // expectation is built through the same normalizing constructor.
        InjectionClaimedReferences filed = references.recordClaimed(
                new InjectionClaimedReferences(executionId, 1,
                        List.of(knowledge, skillKey()), FIRST_AT));

        assertEquals(new InjectionClaimedReferences(executionId, 1,
                        List.of(skillKey(), knowledge), FIRST_AT).claimed(),
                filed.claimed());
        assertEquals(List.of(filed), references.findClaimed(organizationId, teamId, executionId));

        InjectionClaimedReferences replay = references.recordClaimed(
                new InjectionClaimedReferences(executionId, 1,
                        List.of(knowledge, skillKey()), SECOND_AT));

        assertEquals(FIRST_AT, replay.createdAt(), "an identical set replays the receipt");
    }

    @Test
    void aDifferentClaimedSetForTheSameAttemptSurfacesTheStoredReceipt() {
        ManifestSourceKey knowledge = knowledgeKey();
        references.recordClaimed(new InjectionClaimedReferences(
                executionId, 2, List.of(knowledge), FIRST_AT));

        InjectionClaimedReferenceConflictException conflict = assertThrows(
                InjectionClaimedReferenceConflictException.class,
                () -> references.recordClaimed(new InjectionClaimedReferences(
                        executionId, 2, List.of(knowledge, skillKey()), SECOND_AT)));

        assertEquals(List.of(knowledge), conflict.stored().claimed());
        assertEquals(FIRST_AT, conflict.stored().createdAt());
        // A new attempt is a new receipt slot and files normally.
        references.recordClaimed(new InjectionClaimedReferences(
                executionId, 3, List.of(knowledge), SECOND_AT));
        assertEquals(2, references.findClaimed(organizationId, teamId, executionId).size());
    }

    // ------------------------------------------------------------------ manifest read side

    @Test
    void manifestsReadBackAscendingByAttemptAcrossTheExecution() {
        manifests.append(manifest(2));
        manifests.append(manifest(1));

        List<InjectionManifest> read = manifests.findByExecution(
                organizationId, teamId, executionId);

        assertEquals(List.of(1, 2), read.stream().map(InjectionManifest::attempt).toList());
        assertTrue(manifests.findByExecution(
                OrganizationId.generate(), teamId, executionId).isEmpty());
        assertTrue(manifests.findByExecution(
                organizationId, TeamId.generate(), executionId).isEmpty());
    }

    // ------------------------------------------------------------------ fixtures

    private InjectionReferenceFeedback feedback(
            ManifestSourceKey source, PrincipalId memberPrincipalId, UtcTimestamp createdAt) {
        return new InjectionReferenceFeedback(
                executionId, source, memberPrincipalId,
                InjectionFeedbackKind.NOT_APPLICABLE, createdAt);
    }

    private static ManifestSourceKey knowledgeKey() {
        return new ManifestSourceKey(
                ManifestSourceType.KNOWLEDGE_ENTRY, UUID.randomUUID().toString(), 3,
                hex(0x1001));
    }

    private static ManifestSourceKey skillKey() {
        return new ManifestSourceKey(
                ManifestSourceType.SKILL_INSTRUCTION,
                "java-spring-v1_crewscope-java-spring-v1", 1, hex(0xA5F4));
    }

    /** A minimal manifest — the read side only cares about attempt and execution. */
    private InjectionManifest manifest(int attempt) {
        return new InjectionManifest(
                InjectionManifestId.generate(),
                executionId,
                attempt,
                List.of(new ManifestSourceRef(ManifestSourceType.SKILL_INSTRUCTION,
                        "java-spring-v1_crewscope-java-spring-v1", 1, hex(0xA5F4),
                        ManifestSourceStage.INJECTED)),
                List.of(),
                new PromptBudgetSnapshot(8192, 0, 0, 0),
                List.of(DegradationReasonCode.RETRIEVAL_DISABLED),
                FIRST_AT);
    }

    private static String hex(long seed) {
        return (Long.toHexString(seed) + "0".repeat(64)).substring(0, 64);
    }
}
