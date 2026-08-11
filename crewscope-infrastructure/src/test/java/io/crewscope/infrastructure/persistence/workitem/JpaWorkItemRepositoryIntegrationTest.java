package io.crewscope.infrastructure.persistence.workitem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.workitem.WorkItemPage;
import io.crewscope.application.workitem.WorkItemQuery;
import io.crewscope.application.workitem.WorkItemRepository;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectKey;
import io.crewscope.domain.workitem.WorkProjectScope;
import io.crewscope.domain.workitem.WorkProjectStatus;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Exercises the WorkItem adapter against migrated PostgreSQL instead of an in-memory database. */
@SpringBootTest(
        classes = JpaWorkItemRepositoryIntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.default_schema=crewscope",
            "spring.jpa.open-in-view=false"
        })
class JpaWorkItemRepositoryIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final UtcTimestamp BASE_TIME = UtcTimestamp.parse("2026-08-06T12:00:00Z");

    @Autowired
    private WorkItemRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetBusinessData() {
        jdbcTemplate.execute("TRUNCATE TABLE crewscope.organization CASCADE");
    }

    @Test
    void createsAndReconstitutesAWorkItemWithScopeDefaultsAndAudit() {
        Fixture fixture = seedFixture("one");
        WorkItem original = newWorkItem(fixture, "CRW-1", BASE_TIME);

        WorkItem committed = repository.create(original);
        WorkItem loaded = repository
                .findById(fixture.organizationId(), original.id())
                .orElseThrow();
        Map<String, Object> row = jdbcTemplate.queryForMap(
                """
                SELECT item_type, priority, source_provider,
                       created_by_principal_id, updated_by_principal_id,
                       created_at, updated_at, version
                FROM crewscope.work_item
                WHERE id = ?
                """,
                original.id().value());

        assertEquals(original.id(), committed.id());
        assertEquals(original.scope(), loaded.scope());
        assertEquals(original.key(), loaded.key());
        assertEquals(WorkItemStatus.BACKLOG, loaded.status());
        assertEquals(0, loaded.version());
        assertEquals(fixture.actorId(), loaded.audit().createdBy().orElseThrow());
        assertEquals(fixture.actorId(), loaded.audit().updatedBy().orElseThrow());
        assertEquals("TASK", row.get("item_type"));
        assertEquals("MEDIUM", row.get("priority"));
        assertEquals("CREWSCOPE", row.get("source_provider"));
        assertEquals(fixture.actorId().value(), row.get("created_by_principal_id"));
        assertEquals(fixture.actorId().value(), row.get("updated_by_principal_id"));
        assertEquals(0L, ((Number) row.get("version")).longValue());
        assertNotNull(row.get("created_at"));
        assertNotNull(row.get("updated_at"));
    }

    @Test
    void commitsAStateTransitionVersionAndLastModifierAtomically() {
        Fixture fixture = seedFixture("transition");
        PrincipalId reviewer = seedPrincipal(fixture.organizationId(), "Reviewer");
        WorkItem created = repository.create(newWorkItem(fixture, "CRW-2", BASE_TIME));
        UtcTimestamp changedAt = UtcTimestamp.parse("2026-08-06T12:05:00.123456789Z");

        WorkItem committed = repository.update(
                created.transitionTo(WorkItemStatus.READY, reviewer, changedAt));

        assertEquals(WorkItemStatus.READY, committed.status());
        assertEquals(1, committed.version());
        assertEquals(fixture.actorId(), committed.audit().createdBy().orElseThrow());
        assertEquals(reviewer, committed.audit().updatedBy().orElseThrow());
        assertEquals(
                UtcTimestamp.parse("2026-08-06T12:05:00.123456Z"),
                committed.audit().updatedAt());
    }

    @Test
    void reportsTheCommittedVersionWhenTwoWritersUseTheSameSnapshot() {
        Fixture fixture = seedFixture("locking");
        WorkItem created = repository.create(newWorkItem(fixture, "CRW-3", BASE_TIME));
        WorkItem firstWriter = created.transitionTo(
                WorkItemStatus.READY,
                fixture.actorId(),
                UtcTimestamp.parse("2026-08-06T12:01:00Z"));
        WorkItem staleWriter = created.transitionTo(
                WorkItemStatus.CANCELLED,
                fixture.actorId(),
                UtcTimestamp.parse("2026-08-06T12:02:00Z"));

        repository.update(firstWriter);
        OptimisticLockConflictException failure =
                assertThrows(OptimisticLockConflictException.class, () -> repository.update(staleWriter));

        assertEquals("0", failure.error().details().get("expectedVersion"));
        assertEquals("1", failure.error().details().get("actualVersion"));
        assertEquals(
                WorkItemStatus.READY,
                repository
                        .findById(fixture.organizationId(), created.id())
                        .orElseThrow()
                        .status());
    }

    @Test
    void treatsSameOrganizationScopeMismatchAsAnInvisibleAggregate() {
        Fixture fixture = seedFixture("scope-original");
        Fixture otherProject = seedProject(fixture, "SCP", "Other Scope");
        WorkItem created = repository.create(newWorkItem(fixture, "CRW-30", BASE_TIME));
        WorkItem wrongScope = WorkItem.reconstitute(
                        created.id(),
                        otherProject.scope(),
                        created.key(),
                        created.title(),
                        created.status(),
                        created.version(),
                        created.audit())
                .transitionTo(
                        WorkItemStatus.READY,
                        fixture.actorId(),
                        UtcTimestamp.parse("2026-08-06T12:01:00Z"));

        assertThrows(AggregateNotFoundException.class, () -> repository.update(wrongScope));
        assertEquals(
                WorkItemStatus.BACKLOG,
                repository
                        .findById(fixture.organizationId(), created.id())
                        .orElseThrow()
                        .status());
    }

    @Test
    void isolatesFindUpdateAndCreateAcrossOrganizations() {
        Fixture first = seedFixture("tenant-one");
        Fixture second = seedFixture("tenant-two");
        WorkItem created = repository.create(newWorkItem(first, "CRW-4", BASE_TIME));

        assertTrue(repository.findById(first.organizationId(), created.id()).isPresent());
        assertTrue(repository.findById(second.organizationId(), created.id()).isEmpty());

        WorkItem wrongScope = WorkItem.reconstitute(
                        created.id(),
                        second.scope(),
                        created.key(),
                        created.title(),
                        created.status(),
                        created.version(),
                        created.audit())
                .transitionTo(
                        WorkItemStatus.READY,
                        second.actorId(),
                        UtcTimestamp.parse("2026-08-06T12:01:00Z"));
        assertThrows(AggregateNotFoundException.class, () -> repository.update(wrongScope));

        WorkItem crossTenantActor = WorkItem.create(
                WorkItemId.generate(),
                first.scope(),
                new WorkItemKey("CRW-5"),
                "Cross tenant actor",
                second.actorId(),
                BASE_TIME);
        assertThrows(DataIntegrityViolationException.class, () -> repository.create(crossTenantActor));

        // The actor belongs to the declared Organization, so only the composite Scope foreign keys
        // can reject this Organization/Team/Workspace/Project mixture.
        WorkItem crossTenantScope = WorkItem.create(
                WorkItemId.generate(),
                new WorkItemScope(
                        first.organizationId(),
                        second.teamId(),
                        second.workspaceId(),
                        second.projectId()),
                new WorkItemKey("CRW-6"),
                "Cross tenant scope",
                first.actorId(),
                BASE_TIME);
        assertThrows(
                DataIntegrityViolationException.class,
                () -> repository.create(crossTenantScope));
    }

    @Test
    void pagesDeterministicallyByTeamProjectAndStatusWithoutDuplicates() {
        Fixture fixture = seedFixture("paging");
        Fixture otherProject = seedProject(fixture, "ALT", "Alternative");
        repository.create(newWorkItem(
                fixture, "CRW-10", UtcTimestamp.parse("2026-08-06T12:01:00Z")));
        repository.create(newWorkItem(
                fixture, "CRW-11", UtcTimestamp.parse("2026-08-06T12:02:00Z")));
        WorkItem ready = repository.create(newWorkItem(
                fixture, "CRW-12", UtcTimestamp.parse("2026-08-06T12:03:00Z")));
        repository.update(ready.transitionTo(
                WorkItemStatus.READY,
                fixture.actorId(),
                UtcTimestamp.parse("2026-08-06T12:04:00Z")));
        repository.create(newWorkItem(
                otherProject, "ALT-1", UtcTimestamp.parse("2026-08-06T12:05:00Z")));

        WorkItemQuery firstQuery = new WorkItemQuery(
                fixture.organizationId(),
                fixture.teamId(),
                Optional.of(fixture.projectId()),
                Optional.of(WorkItemStatus.BACKLOG),
                Optional.empty(),
                1);
        WorkItemPage firstPage = repository.findPage(firstQuery);
        WorkItemPage secondPage = repository.findPage(new WorkItemQuery(
                fixture.organizationId(),
                fixture.teamId(),
                Optional.of(fixture.projectId()),
                Optional.of(WorkItemStatus.BACKLOG),
                firstPage.nextCursor(),
                1));

        assertEquals(1, firstPage.items().size());
        assertTrue(firstPage.nextCursor().isPresent());
        assertEquals(1, secondPage.items().size());
        assertTrue(secondPage.nextCursor().isEmpty());
        Set<WorkItemId> returnedIds = new HashSet<>();
        returnedIds.add(firstPage.items().get(0).id());
        returnedIds.add(secondPage.items().get(0).id());
        assertEquals(2, returnedIds.size());
        assertTrue(firstPage.items().get(0).audit().updatedAt()
                .compareTo(secondPage.items().get(0).audit().updatedAt())
                > 0);
        List<String> keys = returnedIds.stream()
                .map(id -> repository.findById(fixture.organizationId(), id).orElseThrow().key().value())
                .sorted()
                .toList();
        assertEquals(List.of("CRW-10", "CRW-11"), keys);
    }

    @Test
    void allocatesTheNextNumericProjectKeyWithoutLexicalOrderingErrors() {
        Fixture fixture = seedFixture("key-allocation");
        String prefix = jdbcTemplate.queryForObject(
                "SELECT project_key FROM crewscope.work_project WHERE id = ?",
                String.class,
                fixture.projectId().value());
        repository.create(newWorkItem(fixture, prefix + "-2", BASE_TIME));
        repository.create(newWorkItem(fixture, prefix + "-10", BASE_TIME));
        WorkProject project = WorkProject.reconstitute(
                fixture.projectId(),
                new WorkProjectScope(
                        fixture.organizationId(), fixture.teamId(), fixture.workspaceId()),
                new WorkProjectKey(prefix),
                "Key allocation",
                WorkProjectStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(fixture.actorId(), BASE_TIME));

        assertEquals(
                new WorkItemKey(prefix + "-11"),
                repository.nextKey(fixture.organizationId(), project));
    }

    private Fixture seedFixture(String suffix) {
        OrganizationId organizationId = OrganizationId.generate();
        TeamId teamId = TeamId.generate();
        WorkspaceId workspaceId = WorkspaceId.generate();
        WorkProjectId projectId = WorkProjectId.generate();
        jdbcTemplate.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, ?, 'ACTIVE')",
                organizationId.value(),
                "Organization " + suffix);
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.team (id, organization_id, name, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """,
                teamId.value(),
                organizationId.value(),
                "Team " + suffix);
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES (?, ?, ?, 'TEAM', ?, 'ACTIVE')
                """,
                workspaceId.value(),
                organizationId.value(),
                teamId.value(),
                "Workspace " + suffix);
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.work_project (
                    id, organization_id, team_id, workspace_id, project_key, name
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                projectId.value(),
                organizationId.value(),
                teamId.value(),
                workspaceId.value(),
                projectKey(suffix),
                "Project " + suffix);
        PrincipalId actorId = seedPrincipal(organizationId, "Actor " + suffix);
        jdbcTemplate.update(
                """
                UPDATE crewscope.organization
                SET created_by_principal_id = ?, updated_by_principal_id = ?
                WHERE id = ?
                """,
                actorId.value(),
                actorId.value(),
                organizationId.value());
        return new Fixture(organizationId, teamId, workspaceId, projectId, actorId);
    }

    private Fixture seedProject(Fixture fixture, String key, String name) {
        WorkProjectId projectId = WorkProjectId.generate();
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.work_project (
                    id, organization_id, team_id, workspace_id, project_key, name,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                projectId.value(),
                fixture.organizationId().value(),
                fixture.teamId().value(),
                fixture.workspaceId().value(),
                key,
                name,
                fixture.actorId().value(),
                fixture.actorId().value());
        return new Fixture(
                fixture.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                projectId,
                fixture.actorId());
    }

    private PrincipalId seedPrincipal(OrganizationId organizationId, String displayName) {
        PrincipalId principalId = PrincipalId.generate();
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, status
                ) VALUES (?, ?, 'USER', ?, 'ACTIVE')
                """,
                principalId.value(),
                organizationId.value(),
                displayName);
        return principalId;
    }

    private static WorkItem newWorkItem(Fixture fixture, String key, UtcTimestamp occurredAt) {
        return WorkItem.create(
                WorkItemId.generate(),
                fixture.scope(),
                new WorkItemKey(key),
                "Work item " + key,
                fixture.actorId(),
                occurredAt);
    }

    private static String projectKey(String suffix) {
        String normalized = suffix.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        return normalized.substring(0, Math.min(10, normalized.length()));
    }

    private record Fixture(
            OrganizationId organizationId,
            TeamId teamId,
            WorkspaceId workspaceId,
            WorkProjectId projectId,
            PrincipalId actorId) {

        private WorkItemScope scope() {
            return new WorkItemScope(organizationId, teamId, workspaceId, projectId);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = WorkItemEntity.class)
    @Import({JpaWorkItemRepositoryAdapter.class, WorkItemEntityMapper.class})
    static class TestApplication {}
}
