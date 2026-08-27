package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.teamobserver.TeamObserverInvocationId;
import io.crewscope.application.teamobserver.TeamObserverInvocationSegment;
import io.crewscope.application.teamobserver.TeamObserverInvocationService;
import io.crewscope.application.teamobserver.TeamObserverSession;
import io.crewscope.application.teamobserver.TeamObserverSessionId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.teamobserver.TeamObserverInitialization;
import io.crewscope.domain.teamobserver.TeamSummaryDataScope;
import io.crewscope.domain.teamobserver.TeamSummaryEntry;
import io.crewscope.domain.teamobserver.TeamSummaryResult;
import io.crewscope.domain.teamobserver.TeamSummarySection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Public DTO minimization and write-control rejection proof for the M6-A05 HTTP boundary. */
class TeamObserverControllerM6A05Test {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final TeamId TEAM_ID = TeamId.generate();
    private static final TeamMemberId MEMBER_ID = TeamMemberId.generate();
    private static final PrincipalId ACTOR_ID = PrincipalId.generate();
    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-27T05:00:00Z");

    private TeamObserverInvocationService service;
    private TeamAccessContext access;
    private TeamObserverSession session;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        service = mock(TeamObserverInvocationService.class);
        access = mock(TeamAccessContext.class);
        session = new TeamObserverSession(
                TeamObserverSessionId.generate(),
                ORGANIZATION_ID,
                TEAM_ID,
                MEMBER_ID,
                ACTOR_ID,
                TeamObserverInitialization.stableProfileId(TEAM_ID),
                NOW);
        TeamRequestIdentityResolver identities = mock(TeamRequestIdentityResolver.class);
        when(identities.resolve(any(), eq(ORGANIZATION_ID), any()))
                .thenReturn(Mono.just(access));
        client = WebTestClient.bindToController(new TeamObserverController(service, identities))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void sessionResponseContainsOnlyPublicReadOnlyCoordinates() {
        when(service.createSession(access, ORGANIZATION_ID, TEAM_ID)).thenReturn(session);

        client.post()
                .uri(route("/sessions"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().cacheControl(CacheControl.noStore())
                .expectBody()
                .jsonPath("$.sessionId").isEqualTo(session.id().toString())
                .jsonPath("$.observerProfileId").isEqualTo(session.observerProfileId().toString())
                .jsonPath("$.mode").isEqualTo("READ_ONLY")
                .jsonPath("$.memberId").doesNotExist()
                .jsonPath("$.actorId").doesNotExist()
                .jsonPath("$.model").doesNotExist();
    }

    @Test
    void summaryProjectionHidesMemberModelToolAndRawEvidencePath() {
        TeamObserverInvocationId invocationId = TeamObserverInvocationId.generate();
        TeamSummaryResult summary = mock(TeamSummaryResult.class);
        TeamSummaryEntry entry = new TeamSummaryEntry(
                ORGANIZATION_ID,
                TEAM_ID,
                MEMBER_ID,
                TeamSummarySection.PROGRESS,
                TeamSummaryDataScope.WORK_ITEM_SUMMARY,
                "One WorkItem is progressing",
                "/api/v1/private-evidence");
        when(summary.observerProfileId()).thenReturn(session.observerProfileId());
        when(summary.generatedAt()).thenReturn(NOW);
        when(summary.progress()).thenReturn(List.of(entry));
        when(summary.blockers()).thenReturn(List.of());
        when(summary.reviewBacklog()).thenReturn(List.of());
        when(summary.pendingConfirmations()).thenReturn(List.of());
        when(summary.anomalies()).thenReturn(List.of());
        when(service.summary(
                        access,
                        ORGANIZATION_ID,
                        TEAM_ID,
                        session.id(),
                        invocationId))
                .thenReturn(summary);

        client.get()
                .uri(route("/sessions/" + session.id() + "/invocations/"
                        + invocationId + "/summary"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.progress[0].summary").isEqualTo("One WorkItem is progressing")
                .jsonPath("$.progress[0].evidenceIndex").isEqualTo(0)
                .jsonPath("$.progress[0].evidencePath").doesNotExist()
                .jsonPath("$.request").doesNotExist()
                .jsonPath("$.memberId").doesNotExist()
                .jsonPath("$.modelConnectionId").doesNotExist()
                .jsonPath("$.toolPayload").doesNotExist();
    }

    @Test
    void invocationRejectsClientSelectedModelToolsAndWriteCommands() {
        String uri = route("/sessions/" + session.id() + "/invocations");
        client.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"instruction":"Summarize the Team","modelId":"private-model"}
                        """)
                .exchange()
                .expectStatus().isBadRequest();

        client.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"instruction":"Summarize the Team","command":{"type":"WRITE"}}
                        """)
                .exchange()
                .expectStatus().isBadRequest();

        verify(service, never()).invoke(any(), any(), any(), any(), any(), any(Integer.class), any());
    }

    @Test
    void everySseBusinessFrameRevalidatesCurrentInvocationAccess() {
        TeamObserverInvocationId invocationId = TeamObserverInvocationId.generate();
        var first = new io.crewscope.application.teamobserver.TeamObserverStreamEvent(
                invocationId,
                1,
                NOW,
                io.crewscope.application.teamobserver.TeamObserverStreamEvent.Type.STARTED,
                Optional.empty(),
                Optional.empty());
        var second = new io.crewscope.application.teamobserver.TeamObserverStreamEvent(
                invocationId,
                2,
                NOW,
                io.crewscope.application.teamobserver.TeamObserverStreamEvent.Type.CANCELLED,
                Optional.empty(),
                Optional.empty());
        TeamObserverInvocationSegment segment = new TeamObserverInvocationSegment(
                session.id(),
                invocationId,
                JdkFlowAdapter.publisherToFlowPublisher(Flux.just(first, second)),
                false);
        when(service.invoke(
                        eq(access),
                        eq(ORGANIZATION_ID),
                        eq(TEAM_ID),
                        eq(session.id()),
                        eq("Summarize the Team"),
                        eq(10),
                        any()))
                .thenReturn(segment);

        client.post()
                .uri(route("/sessions/" + session.id() + "/invocations"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"instruction\":\"Summarize the Team\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(TeamObserverController.TeamObserverEventResponse.class)
                .hasSize(2);

        verify(service, times(2)).requireInvocationAccess(
                access, ORGANIZATION_ID, TEAM_ID, session.id(), invocationId);
    }

    private static String route(String suffix) {
        return "/api/v1/organizations/" + ORGANIZATION_ID + "/teams/" + TEAM_ID
                + "/team-observer" + suffix;
    }
}
