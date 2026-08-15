package io.crewscope.server.api;

import io.crewscope.application.task.TaskEvent;
import io.crewscope.application.task.TaskEventContext;
import io.crewscope.application.task.TaskEventCursor;
import io.crewscope.application.task.TaskEventPage;
import io.crewscope.application.task.TaskEventService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.ExecutionLeaseId;
import io.crewscope.domain.task.StepExecutionId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.server.config.application.TaskEventStreamProperties;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Member-authorized JSON history and resumable SSE boundary for durable Task events. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/tasks")
public final class TaskEventController {

    private final TaskEventService service;
    private final TeamRequestIdentityResolver identityResolver;
    private final TaskEventStreamProperties properties;
    private final TaskEventCursorCodec cursorCodec = new TaskEventCursorCodec();

    public TaskEventController(
            TaskEventService service,
            TeamRequestIdentityResolver identityResolver,
            TaskEventStreamProperties properties) {
        this.service = service;
        this.identityResolver = identityResolver;
        this.properties = properties;
    }

    @GetMapping(path = "/{taskId}/events", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<TaskEventPageResponse>> history(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) Integer limit,
            Authentication authentication,
            ServerWebExchange exchange) {
        StreamRoute route = route(organizationId, teamId, taskId);
        Optional<TaskEventCursor> cursor = decode(after, route);
        return resolve(authentication, route.organizationId(), exchange)
                .flatMap(access -> page(access, route, cursor, ApiPagination.limit(limit)))
                .map(result -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(TaskEventPageResponse.from(result, cursorCodec)));
    }

    @GetMapping(path = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Mono<ResponseEntity<Flux<ServerSentEvent<TaskEventResponse>>>> stream(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            @RequestHeader(name = ApiHeaders.LAST_EVENT_ID, required = false) String lastEventId,
            @RequestParam(required = false) String after,
            Authentication authentication,
            ServerWebExchange exchange) {
        StreamRoute route = route(organizationId, teamId, taskId);
        Optional<TaskEventCursor> cursor = decode(resolveResumeToken(lastEventId, after), route);
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return resolve(authentication, route.organizationId(), correlationId)
                // Authorize and validate the requested Cursor before the HTTP 200 is committed.
                .flatMap(access -> page(access, route, cursor, properties.getBatchSize())
                        .map(initial -> sseResponse(
                                authentication, correlationId, route, cursor, initial)));
    }

    private ResponseEntity<Flux<ServerSentEvent<TaskEventResponse>>> sseResponse(
            Authentication authentication,
            UUID correlationId,
            StreamRoute route,
            Optional<TaskEventCursor> requestedCursor,
            TaskEventPage initialPage) {
        AtomicReference<TaskEventCursor> position = new AtomicReference<>(
                initialPage.nextCursor().orElse(requestedCursor.orElse(null)));
        Flux<TaskEvent> initial = Flux.fromIterable(initialPage.events())
                .doOnNext(event -> position.set(event.cursor()));
        Flux<TaskEvent> updates = initialPage.streamComplete()
                ? Flux.empty()
                : Flux.interval(properties.getPollInterval())
                        // Only empty poll opportunities may be coalesced; event rows are never dropped.
                        .onBackpressureDrop()
                        .concatMap(ignored -> resolve(
                                        authentication, route.organizationId(), correlationId)
                                .flatMap(access -> page(
                                        access,
                                        route,
                                        Optional.ofNullable(position.get()),
                                        properties.getBatchSize())), 1)
                        .takeUntil(TaskEventPage::streamComplete)
                        .concatMapIterable(TaskEventPage::events)
                        .doOnNext(event -> position.set(event.cursor()));
        Flux<ServerSentEvent<TaskEventResponse>> body = Flux.concat(initial, updates)
                // Long-lived slow connections are rotated at a resumable Cursor boundary.
                .take(properties.getMaximumEventsPerConnection())
                .map(event -> ServerSentEvent.<TaskEventResponse>builder(
                                TaskEventResponse.from(event, cursorCodec))
                        .id(cursorCodec.encode(event.cursor()))
                        .event(event.envelope().eventType().value())
                        .build());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private Mono<TaskEventPage> page(
            TeamAccessContext access,
            StreamRoute route,
            Optional<TaskEventCursor> cursor,
            int limit) {
        return blocking(() -> service.events(
                access,
                route.organizationId(),
                route.teamId(),
                route.taskId(),
                cursor,
                limit));
    }

    private Mono<TeamAccessContext> resolve(
            Authentication authentication,
            OrganizationId organizationId,
            ServerWebExchange exchange) {
        return resolve(authentication, organizationId, ApiCorrelationIds.resolve(exchange));
    }

    private Mono<TeamAccessContext> resolve(
            Authentication authentication, OrganizationId organizationId, UUID correlationId) {
        return identityResolver.resolve(authentication, organizationId, correlationId);
    }

    private Optional<TaskEventCursor> decode(String token, StreamRoute route) {
        return Optional.ofNullable(token)
                .filter(value -> !value.isBlank())
                .map(value -> cursorCodec.decode(
                        value, route.organizationId(), route.teamId(), route.taskId()));
    }

    private static String resolveResumeToken(String lastEventId, String after) {
        boolean hasHeader = lastEventId != null && !lastEventId.isBlank();
        boolean hasParameter = after != null && !after.isBlank();
        if (hasHeader && hasParameter && !lastEventId.equals(after)) {
            throw new ApiRequestException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "invalid_cursor",
                    "Last-Event-ID and after must identify the same position",
                    Map.of("header", ApiHeaders.LAST_EVENT_ID, "parameter", "after"));
        }
        return hasHeader ? lastEventId : after;
    }

    private static StreamRoute route(String organizationId, String teamId, String taskId) {
        try {
            return new StreamRoute(
                    OrganizationId.from(organizationId), TeamId.from(teamId), TaskId.from(taskId));
        } catch (IllegalArgumentException exception) {
            throw new ApiRequestException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "invalid_request",
                    "Request contains an invalid identifier",
                    Map.of("route", "task-events"));
        }
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    private record StreamRoute(
            OrganizationId organizationId, TeamId teamId, TaskId taskId) {}

    public record TaskEventPageResponse(
            List<TaskEventResponse> items,
            boolean hasMore,
            boolean taskTerminal,
            String nextCursor) {

        static TaskEventPageResponse from(TaskEventPage page, TaskEventCursorCodec codec) {
            return new TaskEventPageResponse(
                    page.events().stream().map(event -> TaskEventResponse.from(event, codec)).toList(),
                    page.hasMore(),
                    page.taskTerminal(),
                    page.nextCursor().map(codec::encode).orElse(null));
        }
    }

    public record TaskEventResponse(
            String cursor,
            TaskEventContextResponse context,
            boolean projectionGap,
            RealtimeEventResponse<Map<String, Object>> event) {

        static TaskEventResponse from(TaskEvent value, TaskEventCursorCodec codec) {
            return new TaskEventResponse(
                    codec.encode(value.cursor()),
                    TaskEventContextResponse.from(value.context()),
                    value.projectionGap(),
                    RealtimeEventResponse.from(value.envelope()));
        }
    }

    public record TaskEventContextResponse(
            UUID taskId,
            UUID taskExecutionId,
            UUID stepExecutionId,
            UUID agentRunId,
            UUID executionLeaseId) {

        static TaskEventContextResponse from(TaskEventContext value) {
            return new TaskEventContextResponse(
                    value.taskId().value(),
                    value.taskExecutionId().map(TaskExecutionId::value).orElse(null),
                    value.stepExecutionId().map(StepExecutionId::value).orElse(null),
                    value.agentRunId().map(AgentRunId::value).orElse(null),
                    value.executionLeaseId().map(ExecutionLeaseId::value).orElse(null));
        }
    }
}
