package io.crewscope.infrastructure.persistence.workgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.workgraph.WorkGraphQueryService;
import io.crewscope.application.workgraph.WorkItemRelationAdjacency;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import io.crewscope.infrastructure.testsupport.SharedJpaTestApplication;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M11-Q01 attack-set face for the WorkGraph read side (ADR-033): the walls around every
 * derived query. An outsider — an active member of a sibling Team in the same Organization,
 * the most privileged shape an outsider can hold — hits the Team wall as
 * PolicyDeniedException on all four query faces, and the wall fires before any anchor
 * lookup, so a nonexistent anchor answers identically (no existence disclosure). A member
 * quoting foreign coordinates gets the aggregate wall (AggregateNotFoundException) at the
 * predicate level. The HTTP translation of both shapes is pinned in
 * WorkGraphQueryControllerM11Q01Test; this class pins the policy decisions on real rows.
 */
@SpringBootTest(
        classes = SharedJpaTestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.default_schema=crewscope",
            "spring.jpa.open-in-view=false"
        })
class WorkGraphQueryAuthorizationM11Q01IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final UtcTimestamp OCCURRED_AT = UtcTimestamp.parse("2026-10-12T10:00:00Z");

    @Autowired
    private WorkGraphQueryService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Fixture fixture;

    @BeforeEach
    void resetBusinessData() {
        jdbcTemplate.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        fixture = seedFixture();
    }

    /**
     * The sibling-member outsider is denied on every anchored face, and the denial is
     * identical for an anchor that does not exist — the wall is checked before any anchor
     * read, so the answer leaks nothing. The same coordinates answer for the Team's own
     * member, proving the denial is the membership's, not broken wiring.
     */
    @Test
    void outsiderQueriesAreDeniedAtTheTeamWallWithoutExistenceDisclosure() {
        TeamAccessContext outsider = fixture.context(fixture.outsiderId);

        for (WorkItemId anchor : new WorkItemId[] {fixture.anchor, WorkItemId.generate()}) {
            assertThrows(
                    PolicyDeniedException.class,
                    () ->
                        service.relations(
                            outsider, fixture.organizationId, fixture.teamId,
                            fixture.projectId, anchor),
                    "relations must deny the sibling member for anchor " + anchor);
            assertThrows(
                    PolicyDeniedException.class,
                    () ->
                        service.blockingChain(
                            outsider, fixture.organizationId, fixture.teamId,
                            fixture.projectId, anchor),
                    "blockingChain must deny the sibling member for anchor " + anchor);
            assertThrows(
                    PolicyDeniedException.class,
                    () ->
                        service.criticalPath(
                            outsider, fixture.organizationId, fixture.teamId,
                            fixture.projectId, anchor),
                    "criticalPath must deny the sibling member for anchor " + anchor);
        }

        // Contrast on the exact coordinates the outsider was denied: the member sees the edge.
        WorkItemRelationAdjacency adjacency =
                service.relations(
                    fixture.context(fixture.memberId), fixture.organizationId, fixture.teamId,
                    fixture.projectId, fixture.anchor);
        assertEquals(1, adjacency.edges().size());
        assertFalse(adjacency.truncatedByRows());
    }

    /** A Principal of another Organization is denied before any row is read — the org wall. */
    @Test
    void crossOrganizationContextHitsTheOrgWall() {
        TeamAccessContext foreign = fixture.foreignContext();
        assertThrows(
                PolicyDeniedException.class,
                () ->
                    service.relations(
                        foreign, fixture.organizationId, fixture.teamId,
                        fixture.projectId, fixture.anchor));
        assertThrows(
                PolicyDeniedException.class,
                () ->
                    service.currentlyBlocked(
                        foreign, fixture.organizationId, fixture.teamId,
                        Optional.empty(), Optional.empty(), 20));
    }

    /**
     * A genuine member quoting foreign anchor coordinates — an item stored under another
     * Team's project, or an id that was never stored — gets AggregateNotFoundException from
     * the scope predicates: the 404 the wire pins, never a partial projection.
     */
    @Test
    void teamMemberQueryingForeignAnchorCoordinatesGetsAggregateNotFound() {
        TeamAccessContext member = fixture.context(fixture.memberId);
        for (WorkItemId foreign :
                new WorkItemId[] {fixture.foreignTeamAnchor, WorkItemId.generate()}) {
            AggregateNotFoundException missing =
                    assertThrows(
                            AggregateNotFoundException.class,
                            () ->
                                service.relations(
                                    member, fixture.organizationId, fixture.teamId,
                                    fixture.projectId, foreign),
                            "foreign anchor " + foreign + " must hit the aggregate wall");
            assertEquals("WorkItem", missing.error().details().get("aggregateType"));
            assertThrows(
                    AggregateNotFoundException.class,
                    () ->
                        service.blockingChain(
                            member, fixture.organizationId, fixture.teamId,
                            fixture.projectId, foreign));
            assertThrows(
                    AggregateNotFoundException.class,
                    () ->
                        service.criticalPath(
                            member, fixture.organizationId, fixture.teamId,
                            fixture.projectId, foreign));
        }
    }

    /**
     * The Team-wide aggregation denies the sibling member through both entry branches —
     * with and without a project filter — while the Team's own member reads the page.
     */
    @Test
    void currentlyBlockedIsDeniedForOutsidersWithAndWithoutProjectFilter() {
        TeamAccessContext outsider = fixture.context(fixture.outsiderId);
        assertThrows(
                PolicyDeniedException.class,
                () ->
                    service.currentlyBlocked(
                        outsider, fixture.organizationId, fixture.teamId,
                        Optional.empty(), Optional.empty(), 20));
        assertThrows(
                PolicyDeniedException.class,
                () ->
                    service.currentlyBlocked(
                        outsider, fixture.organizationId, fixture.teamId,
                        Optional.of(fixture.projectId), Optional.empty(), 20));

        // Contrast: the member reads the page and sees exactly the one blocked row the
        // fixture seeded — the denial above was the membership's, not empty data.
        var memberPage =
                service.currentlyBlocked(
                        fixture.context(fixture.memberId), fixture.organizationId,
                        fixture.teamId, Optional.empty(), Optional.empty(), 20);
        assertEquals(1, memberPage.items().size());
        assertEquals(fixture.anchor, memberPage.items().get(0).workItemId());
    }

    // ------------------------------------------------------------------ seeding

    /**
     * One Organization, two initialized Teams: the target Team (member + anchor + one edge)
     * and a sibling Team whose owner is the outsider — the strongest shape an outsider can
     * hold, an active member somewhere else in the same Organization.
     */
    private Fixture seedFixture() {
        OrganizationId organizationId = OrganizationId.generate();
        TeamId teamId = TeamId.generate();
        TeamId outsiderTeamId = TeamId.generate();
        WorkspaceId workspaceId = WorkspaceId.generate();
        WorkspaceId outsiderWorkspaceId = WorkspaceId.generate();
        WorkProjectId projectId = WorkProjectId.generate();
        WorkProjectId outsiderProjectId = WorkProjectId.generate();
        PrincipalId memberId = PrincipalId.generate();
        PrincipalId outsiderId = PrincipalId.generate();

        jdbcTemplate.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Org', 'ACTIVE')",
                organizationId.value());
        insertInitializedTeam(organizationId, teamId, workspaceId, projectId, "CRW", memberId);
        insertInitializedTeam(
                organizationId, outsiderTeamId, outsiderWorkspaceId, outsiderProjectId,
                "WGS", outsiderId);

        WorkItemId anchor = insertWorkItem(organizationId, teamId, workspaceId, projectId, "CRW-1");
        WorkItemId blocker = insertWorkItem(organizationId, teamId, workspaceId, projectId, "CRW-2");
        WorkItemId foreignTeamAnchor =
                insertWorkItem(
                    organizationId, outsiderTeamId, outsiderWorkspaceId, outsiderProjectId,
                    "WGS-1");
        insertStoredEdge(organizationId, teamId, memberId, blocker, anchor);

        return new Fixture(
                organizationId, teamId, projectId, memberId, outsiderId, anchor,
                foreignTeamAnchor);
    }

    /** Team + workspace + project + principal + ACTIVE member row, initialized like production. */
    private void insertInitializedTeam(
            OrganizationId organizationId,
            TeamId teamId,
            WorkspaceId workspaceId,
            WorkProjectId projectId,
            String projectKey,
            PrincipalId memberPrincipalId) {
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.team (id, organization_id, name, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """,
                teamId.value(),
                organizationId.value(),
                projectKey + " Team");
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES (?, ?, ?, 'TEAM', ?, 'ACTIVE')
                """,
                workspaceId.value(),
                organizationId.value(),
                teamId.value(),
                projectKey + " Workspace");
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
                projectKey,
                projectKey + " Project");
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, status
                ) VALUES (?, ?, 'USER', ?, 'ACTIVE')
                """,
                memberPrincipalId.value(),
                organizationId.value(),
                projectKey + " Member");
        UUID memberRow = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.team_member (
                    id, organization_id, team_id, user_principal_id, status, join_method,
                    joined_at
                ) VALUES (?, ?, ?, ?, 'ACTIVE', 'IMPORT', ?)
                """,
                memberRow,
                organizationId.value(),
                teamId.value(),
                memberPrincipalId.value(),
                OCCURRED_AT.value().atOffset(ZoneOffset.UTC));
        // a Team is readable only once initialization closes: owner member + default workspace
        jdbcTemplate.update(
                "UPDATE crewscope.team SET owner_member_id = ?, default_workspace_id = ?"
                        + " WHERE id = ?",
                memberRow,
                workspaceId.value(),
                teamId.value());
    }

    private WorkItemId insertWorkItem(
            OrganizationId organizationId,
            TeamId teamId,
            WorkspaceId workspaceId,
            WorkProjectId projectId,
            String itemKey) {
        WorkItemId id = WorkItemId.generate();
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.work_item (
                    id, organization_id, team_id, workspace_id, project_id,
                    item_key, item_type, title, status, priority
                ) VALUES (?, ?, ?, ?, ?, ?, 'TASK', ?, 'BACKLOG', 'MEDIUM')
                """,
                id.value(),
                organizationId.value(),
                teamId.value(),
                workspaceId.value(),
                projectId.value(),
                itemKey,
                "Task " + itemKey);
        return id;
    }

    /** Direct stored-edge insert (src blocks dst), the shape the queries read. */
    private void insertStoredEdge(
            OrganizationId organizationId,
            TeamId teamId,
            PrincipalId createdBy,
            WorkItemId src,
            WorkItemId dst) {
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.work_item_relation (
                    id, organization_id, team_id,
                    src_workspace_id, src_project_id, src_work_item_id,
                    dst_workspace_id, dst_project_id, dst_work_item_id,
                    relation_kind, created_by_principal_id, updated_by_principal_id
                ) SELECT ?, ?, team_id, workspace_id, project_id, ?,
                         workspace_id, project_id, ?, 'BLOCKS', ?, ?
                  FROM crewscope.work_item WHERE id = ?
                """,
                UUID.randomUUID(),
                organizationId.value(),
                src.value(),
                dst.value(),
                createdBy.value(),
                createdBy.value(),
                src.value());
    }

    /** The seeded coordinates plus the two acting principals. */
    private final class Fixture {
        private final OrganizationId organizationId;
        private final TeamId teamId;
        private final WorkProjectId projectId;
        private final PrincipalId memberId;
        private final PrincipalId outsiderId;
        private final WorkItemId anchor;
        private final WorkItemId foreignTeamAnchor;
        // contexts are built per assertion; the cache keeps the Principal rows singletons
        private final Map<PrincipalId, Principal> principals = new ConcurrentHashMap<>();

        private Fixture(
                OrganizationId organizationId,
                TeamId teamId,
                WorkProjectId projectId,
                PrincipalId memberId,
                PrincipalId outsiderId,
                WorkItemId anchor,
                WorkItemId foreignTeamAnchor) {
            this.organizationId = organizationId;
            this.teamId = teamId;
            this.projectId = projectId;
            this.memberId = memberId;
            this.outsiderId = outsiderId;
            this.anchor = anchor;
            this.foreignTeamAnchor = foreignTeamAnchor;
        }

        private TeamAccessContext context(PrincipalId principalId) {
            return new TeamAccessContext(principal(principalId), false);
        }

        /** A USER of another Organization — the org wall must fire before any row is read. */
        private TeamAccessContext foreignContext() {
            return new TeamAccessContext(
                    Principal.create(
                            PrincipalId.generate(),
                            PrincipalScope.organization(OrganizationId.generate()),
                            PrincipalType.USER,
                            Optional.empty(),
                            "Foreign Actor",
                            Optional.empty(),
                            PrincipalVisibility.ORGANIZATION,
                            OCCURRED_AT),
                    false);
        }

        private Principal principal(PrincipalId principalId) {
            return principals.computeIfAbsent(
                    principalId,
                    id ->
                            Principal.create(
                                    id,
                                    PrincipalScope.organization(organizationId),
                                    PrincipalType.USER,
                                    Optional.empty(),
                                    "Actor " + id,
                                    Optional.empty(),
                                    PrincipalVisibility.ORGANIZATION,
                                    OCCURRED_AT));
        }
    }
}
