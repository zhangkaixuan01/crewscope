package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.workgraph.WorkGraphQueryService;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * M11-Q01 attack-set HTTP face for the query side: an outsider (or a member addressing
 * foreign coordinates) must hit the team wall as 403 {@code policy_denied} / the anchor
 * wall as 404 {@code aggregate_not_found} — never a partial projection, and never an
 * existence disclosure beyond the two opaque error shapes. The service-level walls are
 * pinned in WorkGraphQueryAuthorizationM11Q01IntegrationTest; this class pins their
 * translation onto the wire.
 */
class WorkGraphQueryControllerM11Q01Test {

    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");
    private static final UtcTimestamp OCCURRED_AT = UtcTimestamp.parse("2026-10-12T10:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final WorkProjectId projectId = WorkProjectId.generate();
    private final WorkItemId anchor = WorkItemId.generate();

    private WorkGraphQueryService queryService;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        queryService = mock(WorkGraphQueryService.class);
        WorkGraphBlockedCursorCodec cursorCodec =
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
                        "Outsider",
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
    void theTeamWallAnswersForbiddenOnEveryQueryFaceWithoutLeakingCoordinates() {
        when(queryService.relations(any(), eq(organizationId), eq(teamId), eq(projectId), eq(anchor)))
                .thenThrow(new PolicyDeniedException("read relations of this work item"));
        when(queryService.blockingChain(any(), eq(organizationId), eq(teamId), eq(projectId), eq(anchor)))
                .thenThrow(new PolicyDeniedException("walk the blocking chain of this work item"));
        when(queryService.criticalPath(any(), eq(organizationId), eq(teamId), eq(projectId), eq(anchor)))
                .thenThrow(new PolicyDeniedException("read the critical path of this work item"));
        when(queryService.currentlyBlocked(
                        any(), eq(organizationId), eq(teamId), eq(Optional.empty()),
                        eq(Optional.empty()), eq(20)))
                .thenThrow(new PolicyDeniedException("read the blocked aggregation of this team"));

        // All four faces answer the same opaque shape: 403 policy_denied, no-store, and a
        // body that carries only the error envelope — no edges, no titles, no anchors.
        for (String path :
                new String[] {
                    rootProject() + "/work-items/" + anchor + "/relations",
                    rootProject() + "/work-items/" + anchor + "/blocking-chain",
                    rootProject() + "/work-items/" + anchor + "/critical-path",
                    root() + "/work-graph/blocked-work-items"
                }) {
            client
                    .get()
                    .uri(path)
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectHeader()
                    .cacheControl(CacheControl.noStore())
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("policy_denied")
                    .jsonPath("$.edges")
                    .doesNotExist()
                    .jsonPath("$.steps")
                    .doesNotExist()
                    .jsonPath("$.chain")
                    .doesNotExist()
                    .jsonPath("$.items")
                    .doesNotExist();
        }
    }

    @Test
    void foreignAnchorCoordinatesAnswerNotFoundWithoutALeastDisclosure() {
        when(queryService.relations(any(), eq(organizationId), eq(teamId), eq(projectId), eq(anchor)))
                .thenThrow(new AggregateNotFoundException("WorkItem", anchor));

        client
                .get()
                .uri(rootProject() + "/work-items/" + anchor + "/relations")
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectHeader()
                .cacheControl(CacheControl.noStore())
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("aggregate_not_found")
                .jsonPath("$.edges")
                .doesNotExist();
    }

    private String root() {
        return "/api/v1/organizations/" + organizationId + "/teams/" + teamId;
    }

    private String rootProject() {
        return root() + "/work-projects/" + projectId;
    }
}
