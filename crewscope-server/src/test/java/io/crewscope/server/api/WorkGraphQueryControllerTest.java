package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.workgraph.BlockedItemPosition;
import io.crewscope.application.workgraph.BlockedWorkItemPage;
import io.crewscope.application.workgraph.BlockedWorkItemRow;
import io.crewscope.application.workgraph.CriticalPathResult;
import io.crewscope.application.workgraph.WorkGraphQueryService;
import io.crewscope.application.workgraph.WorkItemRelationAdjacency;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workgraph.BlockingChain;
import io.crewscope.domain.workgraph.WorkItemRelation;
import io.crewscope.domain.workgraph.WorkItemRelationId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * Query-side HTTP contract of the WorkGraph API (M11-A02): the four reads answer with the
 * whitelisted projection fields, truncation flags survive the wire, the blocked-work page
 * round-trips its signed cursor, and validation failures map onto explicit 400s.
 */
class WorkGraphQueryControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final UtcTimestamp OCCURRED_AT = UtcTimestamp.parse("2026-10-11T10:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final WorkProjectId projectId = WorkProjectId.generate();
    private final WorkItemId anchor = WorkItemId.generate();
    private final WorkItemId other = WorkItemId.generate();

    private WorkGraphQueryService queryService;
    private WebTestClient client;
    private WorkGraphBlockedCursorCodec cursorCodec;

    @BeforeEach
    void setUp() {
        queryService = mock(WorkGraphQueryService.class);
        cursorCodec =
                new WorkGraphBlockedCursorCodec(
                        new TeamActivityCursorKeyRing(
                                "test-key",
                                Map.of(
                                        "test-key",
                                        Base64.getEncoder().encodeToString(new byte[48]))),
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        Duration.ofDays(30));
        Principal actor =
                Principal.create(
                        PrincipalId.generate(),
                        PrincipalScope.organization(organizationId),
                        PrincipalType.USER,
                        Optional.empty(),
                        "Viewer",
                        Optional.empty(),
                        PrincipalVisibility.ORGANIZATION,
                        OCCURRED_AT);
        TeamRequestIdentityResolver resolver =
                (authentication, organization, correlationId) ->
                        Mono.just(new TeamAccessContext(actor, false));
        client =
                WebTestClient.bindToController(
                        new WorkGraphQueryController(queryService, resolver, cursorCodec))
                        .controllerAdvice(new ApiExceptionHandler())
                        .build();
    }

    @Test
    void relationsProjectAnchorRelativeWhitelistedFields() {
        WorkItemRelation stored = storedRelation();
        WorkItemRelationAdjacency adjacency =
                new WorkItemRelationAdjacency(
                        anchor,
                        List.of(WorkItemRelationAdjacency.Edge.of(anchor, stored)),
                        false);
        when(queryService.relations(any(), eq(organizationId), eq(teamId), eq(projectId), eq(anchor)))
                .thenReturn(adjacency);

        client
                .get()
                .uri(rootProject() + "/work-items/" + anchor + "/relations")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .expectBody()
                .jsonPath("$.anchor")
                .isEqualTo(anchor.toString())
                .jsonPath("$.truncatedByRows")
                .isEqualTo(false)
                .jsonPath("$.edges.length()")
                .isEqualTo(1)
                .jsonPath("$.edges[0].relationId")
                .isEqualTo(stored.id().toString())
                .jsonPath("$.edges[0].direction")
                .isEqualTo("OUTGOING")
                .jsonPath("$.edges[0].otherWorkItemId")
                .isEqualTo(other.toString())
                .jsonPath("$.edges[0].crossProject")
                .isEqualTo(false)
                .jsonPath("$.edges[0].views.length()")
                .isEqualTo(2)
                .jsonPath("$.edges[0].views[0].type")
                .isEqualTo("BLOCKS")
                .jsonPath("$.edges[0].views[0].fromWorkItemId")
                .isEqualTo(anchor.toString())
                .jsonPath("$.edges[0].views[1].type")
                .isEqualTo("DEPENDS_ON")
                .jsonPath("$.edges[0].views[1].fromWorkItemId")
                .isEqualTo(other.toString())
                .jsonPath("$.edges[0].createdAt")
                .isEqualTo(OCCURRED_AT.toString());
    }

    @Test
    void anEmptyGraphStillAnswersOkWithExplicitTruncationFlags() {
        when(queryService.relations(any(), eq(organizationId), eq(teamId), eq(projectId), eq(anchor)))
                .thenReturn(new WorkItemRelationAdjacency(anchor, List.of(), false));

        client
                .get()
                .uri(rootProject() + "/work-items/" + anchor + "/relations")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.edges.length()")
                .isEqualTo(0)
                .jsonPath("$.truncatedByRows")
                .isEqualTo(false);
    }

    @Test
    void blockingChainCarriesItsTruncationFlags() {
        BlockingChain chain =
                new BlockingChain(
                        anchor,
                        List.of(new BlockingChain.Step(other, 1)),
                        true,
                        false);
        when(queryService.blockingChain(any(), eq(organizationId), eq(teamId), eq(projectId), eq(anchor)))
                .thenReturn(chain);

        client
                .get()
                .uri(rootProject() + "/work-items/" + anchor + "/blocking-chain")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.anchor")
                .isEqualTo(anchor.toString())
                .jsonPath("$.steps.length()")
                .isEqualTo(1)
                .jsonPath("$.steps[0].workItemId")
                .isEqualTo(other.toString())
                .jsonPath("$.steps[0].depth")
                .isEqualTo(1)
                .jsonPath("$.truncatedByDepth")
                .isEqualTo(true)
                .jsonPath("$.truncatedByRows")
                .isEqualTo(false);
    }

    @Test
    void criticalPathAnswersTheTopologicalChain() {
        when(queryService.criticalPath(any(), eq(organizationId), eq(teamId), eq(projectId), eq(anchor)))
                .thenReturn(new CriticalPathResult(List.of(other, anchor), false, false));

        client
                .get()
                .uri(rootProject() + "/work-items/" + anchor + "/critical-path")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.chain.length()")
                .isEqualTo(2)
                .jsonPath("$.chain[0]")
                .isEqualTo(other.toString())
                .jsonPath("$.chain[1]")
                .isEqualTo(anchor.toString())
                .jsonPath("$.truncatedByDepth")
                .isEqualTo(false)
                .jsonPath("$.truncatedByRows")
                .isEqualTo(false);
    }

    @Test
    void blockedWorkItemsAnswerWhitelistedRowsAndASignedCursor() {
        BlockedItemPosition position =
                new BlockedItemPosition(OCCURRED_AT, WorkItemId.generate());
        when(queryService.currentlyBlocked(
                        any(), eq(organizationId), eq(teamId), eq(Optional.empty()),
                        eq(Optional.empty()), eq(20)))
                .thenReturn(new BlockedWorkItemPage(List.of(blockedRow()), Optional.of(position)));

        client
                .get()
                .uri(root() + "/work-graph/blocked-work-items")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.items.length()")
                .isEqualTo(1)
                .jsonPath("$.items[0].itemKey")
                .isEqualTo("CRW-1")
                .jsonPath("$.items[0].blockerCount")
                .isEqualTo(1)
                .jsonPath("$.items[0].blockers.length()")
                .isEqualTo(1)
                .jsonPath("$.items[0].blockers[0].status")
                .isEqualTo("IN_PROGRESS")
                .jsonPath("$.items[0].crossesProject")
                .isEqualTo(false)
                .jsonPath("$.nextCursor")
                .isNotEmpty();
    }

    @Test
    void theDecodedCursorRestartsTheNextPage() {
        BlockedItemPosition position =
                new BlockedItemPosition(OCCURRED_AT, WorkItemId.generate());
        String token = cursorCodec.encode(organizationId, teamId, position);
        when(queryService.currentlyBlocked(
                        any(), eq(organizationId), eq(teamId), eq(Optional.empty()),
                        eq(Optional.of(position)), eq(20)))
                .thenReturn(new BlockedWorkItemPage(List.of(), Optional.empty()));

        client
                .get()
                .uri(root() + "/work-graph/blocked-work-items?after=" + token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.items.length()")
                .isEqualTo(0)
                .jsonPath("$.nextCursor")
                .doesNotExist();

        verify(queryService)
                .currentlyBlocked(
                        any(), eq(organizationId), eq(teamId), eq(Optional.empty()),
                        eq(Optional.of(position)), eq(20));
    }

    @Test
    void limitOutOfRangeIsRejectedExplicitly() {
        client
                .get()
                .uri(root() + "/work-graph/blocked-work-items?limit=0")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("invalid_request");
        client
                .get()
                .uri(root() + "/work-graph/blocked-work-items?limit=101")
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void aCursorOfAnotherStreamIsInvalid() {
        String foreign =
                cursorCodec.encode(organizationId, TeamId.generate(), new BlockedItemPosition(OCCURRED_AT, WorkItemId.generate()));

        client
                .get()
                .uri(root() + "/work-graph/blocked-work-items?after=" + foreign)
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("invalid_cursor");
    }

    private String root() {
        return "/api/v1/organizations/" + organizationId + "/teams/" + teamId;
    }

    private String rootProject() {
        return root() + "/work-projects/" + projectId;
    }

    private WorkItemRelation storedRelation() {
        return WorkItemRelation.reconstitute(
                WorkItemRelationId.generate(),
                new WorkItemRelation.RelationEndpoint(anchor, anchorScope()),
                new WorkItemRelation.RelationEndpoint(other, anchorScope()),
                io.crewscope.domain.workgraph.WorkItemRelationType.BLOCKS,
                io.crewscope.domain.shared.audit.AuditMetadata.createdBy(
                        PrincipalId.generate(), OCCURRED_AT));
    }

    private WorkItemScope anchorScope() {
        return new WorkItemScope(
                organizationId, teamId, WorkspaceId.generate(), projectId);
    }

    private static BlockedWorkItemRow blockedRow() {
        return new BlockedWorkItemRow(
                WorkItemId.generate(),
                "CRW-1",
                "Blocked task",
                WorkItemStatus.IN_PROGRESS,
                WorkProjectId.generate(),
                OCCURRED_AT,
                1,
                false,
                List.of(
                        new BlockedWorkItemRow.Blocker(
                                WorkItemId.generate(),
                                "Blocking task",
                                WorkItemStatus.IN_PROGRESS,
                                WorkProjectId.generate())));
    }
}
