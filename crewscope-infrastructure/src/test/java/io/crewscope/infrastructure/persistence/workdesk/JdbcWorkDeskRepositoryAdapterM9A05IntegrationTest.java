package io.crewscope.infrastructure.persistence.workdesk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.workdesk.WorkDeskItem;
import io.crewscope.application.workdesk.WorkDeskQuery;
import io.crewscope.application.workdesk.WorkDeskSection;
import io.crewscope.application.workdesk.WorkDeskSummary;
import io.crewscope.application.workitem.WorkItemTransitionSubject;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real-PostgreSQL evidence that the WorkDesk read hands over the facts a transition projection needs.
 *
 * <p>The adapter may not decide availability — that belongs to the application layer — but it does
 * decide which facts reach it, and a column that does not exist is a decision the compiler cannot
 * catch. This is the only test that runs the WorkDesk SELECTs against the migrated schema, so it is
 * the only place where the row shape and the projection's expectations meet.
 */
@SpringBootTest(
        classes = JdbcWorkDeskRepositoryAdapterM9A05IntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true"
        })
class JdbcWorkDeskRepositoryAdapterM9A05IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-13T09:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    private OrganizationId organizationId;
    private TeamId teamId;
    private UUID workspaceId;
    private UUID projectId;
    private UUID principalId;
    private UUID memberId;
    private JdbcWorkDeskRepositoryAdapter adapter;

    @BeforeEach
    void seedScope() {
        jdbc.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        organizationId = OrganizationId.generate();
        teamId = TeamId.generate();
        workspaceId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        principalId = UUID.randomUUID();
        memberId = UUID.randomUUID();
        adapter = new JdbcWorkDeskRepositoryAdapter(jdbc);

        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, ?, 'ACTIVE')",
                organizationId.value(), "M9 A05 Organization");
        jdbc.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, visibility, status
                ) VALUES (?, ?, 'USER', 'M9 A05 Member', 'ORGANIZATION', 'ACTIVE')
                """,
                principalId, organizationId.value());
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status) "
                        + "VALUES (?, ?, ?, 'ACTIVE')",
                teamId.value(), organizationId.value(), "M9 A05 Team");
        jdbc.update(
                """
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES (?, ?, ?, 'TEAM', 'M9 A05 Workspace', 'ACTIVE')
                """,
                workspaceId, organizationId.value(), teamId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.team_member (
                    id, organization_id, team_id, user_principal_id,
                    status, join_method, joined_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'ACTIVE', 'BOOTSTRAP', ?, ?, ?)
                """,
                memberId, organizationId.value(), teamId.value(), principalId, NOW, NOW, NOW);
        jdbc.update(
                """
                INSERT INTO crewscope.work_project (
                    id, organization_id, team_id, workspace_id, project_key, name,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, 'A05', 'M9 A05 Project', ?, ?)
                """,
                projectId, organizationId.value(), teamId.value(), workspaceId, principalId,
                principalId);
    }

    @Test
    void reportsTheFactsAProjectionNeedsForWorkItemRowsAndNoneForOtherRows() {
        UUID nativeItem = seedWorkItem("A05-1", "IN_PROGRESS", "CREWSCOPE", null, "OWNER");
        UUID externalItem = seedWorkItem("A05-2", "IN_PROGRESS", "JIRA", "JIRA-7", "OWNER");
        UUID blockedItem = seedWorkItem("A05-3", "BLOCKED", "CREWSCOPE", null, "REVIEWER");

        WorkDeskSummary summary = adapter.summarize(query());

        List<WorkDeskItem> workItems = section(summary, "WORK_ITEM").items();
        assertEquals(3, workItems.size());
        assertSubject(row(workItems, nativeItem), WorkItemStatus.IN_PROGRESS, true);
        // The source is the one fact the WorkDesk cannot derive: an externally owned item has the same
        // status and the same project as a native one, and only the provider column tells them apart.
        assertSubject(row(workItems, externalItem), WorkItemStatus.IN_PROGRESS, false);
        assertSubject(row(workItems, blockedItem), WorkItemStatus.BLOCKED, true);

        // The blocked read is a second SELECT with its own column list, so it needs its own evidence.
        List<WorkDeskItem> blocked = section(summary, "BLOCKED").items();
        assertSubject(row(blocked, blockedItem), WorkItemStatus.BLOCKED, true);

        // Sections the fixture does not populate contribute nothing, so no row anywhere may claim a
        // WorkItem subject it does not have — and no row anywhere may carry a decided action, because
        // availability is not this layer's to decide.
        for (WorkDeskSection section : summary.sections()) {
            if (section.key().equals("WORK_ITEM") || section.key().equals("BLOCKED")) {
                continue;
            }
            assertTrue(
                    section.items().isEmpty(),
                    section.key() + " was not seeded and must stay empty");
        }
        for (WorkDeskSection section : summary.sections()) {
            for (WorkDeskItem item : section.items()) {
                assertTrue(
                        item.availableActions().isEmpty(),
                        "the adapter must never decide availability: " + item.objectId());
            }
        }
    }

    private WorkDeskQuery query() {
        return new WorkDeskQuery(
                organizationId,
                teamId,
                TeamMemberId.from(memberId.toString()),
                PrincipalId.from(principalId.toString()),
                Optional.empty(),
                Optional.empty(),
                false);
    }

    private static WorkDeskSection section(WorkDeskSummary summary, String key) {
        return summary.sections().stream()
                .filter(section -> section.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + key + " section"));
    }

    private static WorkDeskItem row(List<WorkDeskItem> rows, UUID id) {
        return rows.stream()
                .filter(row -> row.objectId().equals(id.toString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + id));
    }

    private void assertSubject(
            WorkDeskItem row, WorkItemStatus status, boolean nativeSource) {
        WorkItemTransitionSubject subject = row.transitionSubject().orElseThrow(
                () -> new AssertionError("a WorkItem row must carry its transition facts"));
        assertEquals(WorkProjectId.from(projectId.toString()), subject.projectId());
        assertEquals(status, subject.status());
        assertEquals(nativeSource, subject.nativeSource());
    }

    /**
     * Seeds one WorkItem with the acting member's assignment in {@code role}.
     *
     * @param sourceRef the external reference, which the schema requires exactly when the source is
     *     not CrewScope
     */
    private UUID seedWorkItem(
            String itemKey, String status, String sourceProvider, String sourceRef, String role) {
        UUID workItemId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO crewscope.work_item (
                    id, organization_id, team_id, workspace_id, project_id,
                    item_key, item_type, title, status, priority, source_provider, source_ref,
                    created_at, updated_at, created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, 'TASK', ?, ?, 'HIGH', ?, ?, ?, ?, ?, ?)
                """,
                workItemId, organizationId.value(), teamId.value(), workspaceId, projectId,
                itemKey, "M9 A05 " + itemKey, status, sourceProvider, sourceRef,
                NOW, NOW, principalId, principalId);
        jdbc.update(
                """
                INSERT INTO crewscope.responsibility_assignment (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    role, actor_principal_id, actor_type, actor_member_id, status,
                    assigned_by_principal_id, assigned_at, accepted_at,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'USER', ?, 'ACTIVE',
                          ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), organizationId.value(), teamId.value(), workspaceId,
                projectId, workItemId, role, principalId, memberId, principalId,
                NOW, NOW, NOW, principalId, NOW, principalId);
        return workItemId;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {}
}
