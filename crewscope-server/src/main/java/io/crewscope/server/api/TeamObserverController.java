package io.crewscope.server.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.teamobserver.TeamObserverEvidenceLink;
import io.crewscope.application.teamobserver.TeamObserverInvocationId;
import io.crewscope.application.teamobserver.TeamObserverInvocationSegment;
import io.crewscope.application.teamobserver.TeamObserverInvocationService;
import io.crewscope.application.teamobserver.TeamObserverSession;
import io.crewscope.application.teamobserver.TeamObserverSessionId;
import io.crewscope.application.teamobserver.TeamObserverStreamEvent;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.teamobserver.TeamSummaryEntry;
import io.crewscope.domain.teamobserver.TeamSummaryResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Conversation-style HTTP/SSE boundary for the fixed, read-only Team Observer Agent. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/team-observer")
public final class TeamObserverController {

    private final TeamObserverInvocationService service;
    private final TeamRequestIdentityResolver identityResolver;

    public TeamObserverController(
            TeamObserverInvocationService service,
            TeamRequestIdentityResolver identityResolver) {
        this.service = service;
        this.identityResolver = identityResolver;
    }

    @PostMapping("/sessions")
    public Mono<ResponseEntity<SessionResponse>> createSession(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId);
        return resolve(authentication, route, exchange)
                .flatMap(access -> blocking(() -> service.createSession(
                        access, route.organizationId(), route.teamId())))
                .map(session -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(SessionResponse.from(session)));
    }

    @PostMapping(
            path = "/sessions/{sessionId}/invocations",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Mono<ResponseEntity<Flux<ServerSentEvent<TeamObserverEventResponse>>>> invoke(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String sessionId,
            @Valid @RequestBody InvocationRequest body,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId);
        TeamObserverSessionId session = sessionId(sessionId);
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return identityResolver.resolve(authentication, route.organizationId(), correlationId)
                .flatMap(access -> blocking(() -> service.invoke(
                                access,
                                route.organizationId(),
                                route.teamId(),
                                session,
                                body.instruction(),
                                body.resolvedLimit(),
                                correlationId))
                        .map(segment -> stream(access, route, segment)));
    }

    /** Reconnects to the same retained invocation; no second model call or write is started. */
    @PostMapping(
            path = "/sessions/{sessionId}/invocations/{invocationId}/resume",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Mono<ResponseEntity<Flux<ServerSentEvent<TeamObserverEventResponse>>>> resume(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String sessionId,
            @PathVariable String invocationId,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId);
        TeamObserverSessionId session = sessionId(sessionId);
        TeamObserverInvocationId invocation = invocationId(invocationId);
        return resolve(authentication, route, exchange)
                .flatMap(access -> blocking(() -> service.resume(
                                access,
                                route.organizationId(),
                                route.teamId(),
                                session,
                                invocation))
                        .map(segment -> stream(access, route, segment)));
    }

    @PostMapping("/sessions/{sessionId}/invocations/{invocationId}/cancel")
    public Mono<ResponseEntity<CancelResponse>> cancel(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String sessionId,
            @PathVariable String invocationId,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId);
        TeamObserverSessionId session = sessionId(sessionId);
        TeamObserverInvocationId invocation = invocationId(invocationId);
        return resolve(authentication, route, exchange)
                .flatMap(access -> blocking(() -> service.cancel(
                        access,
                        route.organizationId(),
                        route.teamId(),
                        session,
                        invocation)))
                .map(cancelled -> ResponseEntity.accepted()
                        .cacheControl(CacheControl.noStore())
                        .body(new CancelResponse(invocation.toString(), cancelled)));
    }

    @GetMapping("/sessions/{sessionId}/invocations/{invocationId}/summary")
    public Mono<ResponseEntity<TeamSummaryResponse>> summary(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String sessionId,
            @PathVariable String invocationId,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId);
        TeamObserverSessionId session = sessionId(sessionId);
        TeamObserverInvocationId invocation = invocationId(invocationId);
        return resolve(authentication, route, exchange)
                .flatMap(access -> blocking(() -> service.summary(
                        access,
                        route.organizationId(),
                        route.teamId(),
                        session,
                        invocation)))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(TeamSummaryResponse.from(value)));
    }

    @GetMapping("/sessions/{sessionId}/invocations/{invocationId}/evidence/{evidenceIndex}")
    public Mono<ResponseEntity<EvidenceResponse>> evidence(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String sessionId,
            @PathVariable String invocationId,
            @PathVariable int evidenceIndex,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId);
        TeamObserverSessionId session = sessionId(sessionId);
        TeamObserverInvocationId invocation = invocationId(invocationId);
        return resolve(authentication, route, exchange)
                .flatMap(access -> blocking(() -> service.evidence(
                        access,
                        route.organizationId(),
                        route.teamId(),
                        session,
                        invocation,
                        evidenceIndex)))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(EvidenceResponse.from(value)));
    }

    private ResponseEntity<Flux<ServerSentEvent<TeamObserverEventResponse>>> stream(
            TeamAccessContext access,
            Route route,
            TeamObserverInvocationSegment segment) {
        Flux<ServerSentEvent<TeamObserverEventResponse>> events =
                JdkFlowAdapter.flowPublisherToFlux(segment.events())
                        .concatMap(event -> authorizeFrame(access, route, segment, event), 1);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(ApiHeaders.INVOCATION_ID, segment.invocationId().toString());
        if (segment.resumed()) {
            response.header("X-CrewScope-Stream-Resumed", "true");
        }
        return response.body(events);
    }

    private Mono<ServerSentEvent<TeamObserverEventResponse>> authorizeFrame(
            TeamAccessContext access,
            Route route,
            TeamObserverInvocationSegment segment,
            TeamObserverStreamEvent event) {
        return blocking(() -> {
            service.requireInvocationAccess(
                    access,
                    route.organizationId(),
                    route.teamId(),
                    segment.sessionId(),
                    segment.invocationId());
            return ServerSentEvent
                    .builder(TeamObserverEventResponse.from(event))
                    .id(Long.toString(event.sequence()))
                    .event(event.type().name())
                    .build();
        });
    }

    private Mono<TeamAccessContext> resolve(
            Authentication authentication, Route route, ServerWebExchange exchange) {
        return identityResolver.resolve(
                authentication, route.organizationId(), ApiCorrelationIds.resolve(exchange));
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    private static Route route(String organizationId, String teamId) {
        try {
            return new Route(OrganizationId.from(organizationId), TeamId.from(teamId));
        } catch (IllegalArgumentException failure) {
            throw invalidIdentifier("organizationId/teamId");
        }
    }

    private static TeamObserverSessionId sessionId(String value) {
        try {
            return TeamObserverSessionId.from(value);
        } catch (IllegalArgumentException failure) {
            throw invalidIdentifier("sessionId");
        }
    }

    private static TeamObserverInvocationId invocationId(String value) {
        try {
            return TeamObserverInvocationId.from(value);
        } catch (IllegalArgumentException failure) {
            throw invalidIdentifier("invocationId");
        }
    }

    private static ApiRequestException invalidIdentifier(String field) {
        return new ApiRequestException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains an invalid identifier",
                Map.of("field", field));
    }

    public static final class InvocationRequest {

        private final String instruction;
        private final Integer maxItemsPerSection;

        @JsonCreator
        public InvocationRequest(
                @JsonProperty("instruction")
                        @NotBlank @Size(max = 4_000) String instruction,
                @JsonProperty("maxItemsPerSection")
                        @Min(1) @Max(50) Integer maxItemsPerSection) {
            this.instruction = instruction;
            this.maxItemsPerSection = maxItemsPerSection;
        }

        public String instruction() {
            return instruction;
        }

        public int resolvedLimit() {
            return maxItemsPerSection == null ? 10 : maxItemsPerSection;
        }

        /** Reject model, Tool, identity, connection, provider and write-command controls. */
        @JsonAnySetter
        void rejectUnknownProperty(String ignoredProperty, Object ignoredValue) {
            throw new IllegalArgumentException("Unsupported Team Observer invocation property");
        }
    }

    public record SessionResponse(
            String sessionId,
            String observerProfileId,
            String mode,
            String createdAt) {

        static SessionResponse from(TeamObserverSession value) {
            return new SessionResponse(
                    value.id().toString(),
                    value.observerProfileId().toString(),
                    "READ_ONLY",
                    value.createdAt().toString());
        }
    }

    public record TeamObserverEventResponse(
            String invocationId,
            long sequence,
            String occurredAt,
            String type,
            Optional<TeamSummaryResponse> summary,
            Optional<String> errorCode) {

        static TeamObserverEventResponse from(TeamObserverStreamEvent value) {
            return new TeamObserverEventResponse(
                    value.invocationId().toString(),
                    value.sequence(),
                    value.occurredAt().toString(),
                    value.type().name(),
                    value.summary().map(TeamSummaryResponse::from),
                    value.errorCode());
        }
    }

    public record TeamSummaryResponse(
            String observerProfileId,
            String generatedAt,
            List<SummaryEntryResponse> progress,
            List<SummaryEntryResponse> blockers,
            List<SummaryEntryResponse> reviewBacklog,
            List<SummaryEntryResponse> pendingConfirmations,
            List<SummaryEntryResponse> anomalies) {

        static TeamSummaryResponse from(TeamSummaryResult value) {
            int index = 0;
            List<SummaryEntryResponse> progress = entries(value.progress(), index);
            index += progress.size();
            List<SummaryEntryResponse> blockers = entries(value.blockers(), index);
            index += blockers.size();
            List<SummaryEntryResponse> review = entries(value.reviewBacklog(), index);
            index += review.size();
            List<SummaryEntryResponse> confirmations =
                    entries(value.pendingConfirmations(), index);
            index += confirmations.size();
            List<SummaryEntryResponse> anomalies = entries(value.anomalies(), index);
            return new TeamSummaryResponse(
                    value.observerProfileId().toString(),
                    value.generatedAt().toString(),
                    progress,
                    blockers,
                    review,
                    confirmations,
                    anomalies);
        }

        private static List<SummaryEntryResponse> entries(
                List<TeamSummaryEntry> values, int offset) {
            List<SummaryEntryResponse> responses = new ArrayList<>();
            for (int index = 0; index < values.size(); index++) {
                TeamSummaryEntry value = values.get(index);
                responses.add(new SummaryEntryResponse(
                        value.section().name(),
                        value.dataScope().name(),
                        value.summary(),
                        offset + index));
            }
            return List.copyOf(responses);
        }
    }

    public record SummaryEntryResponse(
            String section, String dataScope, String summary, int evidenceIndex) {}

    public record EvidenceResponse(
            int evidenceIndex,
            String section,
            String dataScope,
            String summary,
            String path,
            boolean authorized) {

        static EvidenceResponse from(TeamObserverEvidenceLink value) {
            return new EvidenceResponse(
                    value.index(),
                    value.section().name(),
                    value.dataScope().name(),
                    value.summary(),
                    value.path(),
                    true);
        }
    }

    public record CancelResponse(String invocationId, boolean cancelled) {}

    private record Route(OrganizationId organizationId, TeamId teamId) {}
}
