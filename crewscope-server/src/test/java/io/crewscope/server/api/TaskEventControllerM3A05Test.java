package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.task.TaskEvent;
import io.crewscope.application.task.TaskEventContext;
import io.crewscope.application.task.TaskEventCursor;
import io.crewscope.application.task.TaskEventCursorExpiredException;
import io.crewscope.application.task.TaskEventPage;
import io.crewscope.application.task.TaskEventService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.RealtimeEventEnvelope;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.event.StreamType;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.server.config.application.TaskEventStreamProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Covers Task history catch-up, resumable SSE, bounded delivery and continuous authorization. */
class TaskEventControllerM3A05Test {

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final TaskId taskId = TaskId.generate();
    private final TaskExecutionId executionId = TaskExecutionId.generate();
    private final AgentRunId runId = AgentRunId.generate();
    private final UtcTimestamp now = UtcTimestamp.parse("2026-08-15T08:00:00Z");
    private final Principal owner = Principal.create(
            PrincipalId.generate(),
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            "Owner",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            now);
    private final TaskEventCursorCodec codec = new TaskEventCursorCodec();

    private TaskEventService service;
    private TaskEventStreamProperties properties;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        service = mock(TaskEventService.class);
        properties = new TaskEventStreamProperties();
        properties.setPollInterval(Duration.ofHours(1));
        TeamRequestIdentityResolver resolver = (authentication, organization, correlationId) ->
                Mono.just(new TeamAccessContext(owner, false));
        client = client(resolver, properties);
    }

    @Test
    void returnsAscendingHistoryWithContextProjectionGapAndCrossStreamDomainIdentity() {
        UUID sharedDomainEventId = UUID.randomUUID();
        TaskEvent value = event(1, 3, sharedDomainEventId, true);
        when(service.events(
                        any(), eq(organizationId), eq(teamId), eq(taskId), eq(Optional.empty()), eq(1)))
                .thenReturn(new TaskEventPage(List.of(value), true, false));

        client.get()
                .uri(root() + "?limit=1")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.hasMore").isEqualTo(true)
                .jsonPath("$.items[0].projectionGap").isEqualTo(true)
                .jsonPath("$.items[0].context.taskExecutionId")
                .isEqualTo(executionId.toString())
                .jsonPath("$.items[0].context.agentRunId").isEqualTo(runId.toString())
                .jsonPath("$.items[0].event.streamType").isEqualTo("TASK")
                .jsonPath("$.items[0].event.domainEventId")
                .isEqualTo(sharedDomainEventId.toString());
    }

    @Test
    void resumesAfterLastEventIdAndClosesAfterTheTerminalHistoryIsDrained() {
        TaskEventCursor prior = new TaskEventCursor(
                organizationId, teamId, taskId, 1, UUID.randomUUID());
        TaskEvent next = event(2, 1, UUID.randomUUID(), false);
        when(service.events(
                        any(),
                        eq(organizationId),
                        eq(teamId),
                        eq(taskId),
                        eq(Optional.of(prior)),
                        eq(100)))
                .thenReturn(new TaskEventPage(List.of(next), false, true));

        FluxExchangeResult<TaskEventController.TaskEventResponse> result = client.get()
                .uri(root())
                .header(ApiHeaders.LAST_EVENT_ID, codec.encode(prior))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(TaskEventController.TaskEventResponse.class);

        StepVerifier.create(result.getResponseBody())
                .assertNext(value -> assertEquals(
                        next.envelope().eventId(), value.event().eventId()))
                .verifyComplete();
    }

    @Test
    void rotatesAConnectionAtTheConfiguredEventLimitWithoutDroppingItsResumeCursor() {
        properties.setMaximumEventsPerConnection(1);
        TaskEvent first = event(1, 0, UUID.randomUUID(), false);
        TaskEvent second = event(2, 1, UUID.randomUUID(), false);
        when(service.events(
                        any(), eq(organizationId), eq(teamId), eq(taskId), eq(Optional.empty()), eq(100)))
                .thenReturn(new TaskEventPage(List.of(first, second), false, false));

        FluxExchangeResult<TaskEventController.TaskEventResponse> result = client.get()
                .uri(root())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(TaskEventController.TaskEventResponse.class);

        StepVerifier.create(result.getResponseBody())
                .assertNext(value -> assertEquals(codec.encode(first.cursor()), value.cursor()))
                .verifyComplete();
    }

    @Test
    void fiveForcedDisconnectsDeliverTenEventsExactlyOnceWithoutVersionRollback() {
        properties.setMaximumEventsPerConnection(2);
        List<TaskEvent> source = java.util.stream.LongStream.rangeClosed(1, 10)
                .mapToObj(position -> event(position, position, UUID.randomUUID(), false))
                .toList();
        when(service.events(
                        any(),
                        eq(organizationId),
                        eq(teamId),
                        eq(taskId),
                        any(),
                        eq(100)))
                .thenAnswer(invocation -> {
                    Optional<TaskEventCursor> after = invocation.getArgument(4);
                    int from = after.map(cursor -> Math.toIntExact(cursor.position())).orElse(0);
                    int to = Math.min(from + 2, source.size());
                    boolean hasMore = to < source.size();
                    return new TaskEventPage(source.subList(from, to), hasMore, true);
                });

        List<TaskEventController.TaskEventResponse> delivered = new ArrayList<>();
        String resumeCursor = "";
        for (int connection = 0; connection < 5; connection++) {
            FluxExchangeResult<TaskEventController.TaskEventResponse> result = client.get()
                    .uri(root())
                    .header(ApiHeaders.LAST_EVENT_ID, resumeCursor)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .exchange()
                    .expectStatus().isOk()
                    .returnResult(TaskEventController.TaskEventResponse.class);
            List<TaskEventController.TaskEventResponse> batch = result.getResponseBody()
                    .collectList()
                    .block(Duration.ofSeconds(10));
            assertEquals(2, batch == null ? 0 : batch.size());
            delivered.addAll(batch);
            resumeCursor = batch.get(batch.size() - 1).cursor();
        }

        assertEquals(
                source.stream().map(value -> value.envelope().eventId()).toList(),
                delivered.stream().map(value -> value.event().eventId()).toList());
        assertEquals(10, new HashSet<>(delivered.stream()
                .map(value -> value.event().eventId())
                .toList()).size());
        assertEquals(
                java.util.stream.LongStream.rangeClosed(1, 10).boxed().toList(),
                delivered.stream().map(value -> value.event().aggregateVersion()).toList());
    }

    @Test
    void resolvesCurrentIdentityAgainBeforePollingForNewFacts() {
        AtomicInteger resolutions = new AtomicInteger();
        TeamRequestIdentityResolver resolver = (authentication, organization, correlationId) -> {
            resolutions.incrementAndGet();
            return Mono.just(new TeamAccessContext(owner, false));
        };
        properties.setPollInterval(Duration.ofMillis(5));
        WebTestClient pollingClient = client(resolver, properties);
        TaskEvent update = event(1, 0, UUID.randomUUID(), false);
        when(service.events(
                        any(), eq(organizationId), eq(teamId), eq(taskId), eq(Optional.empty()), eq(100)))
                .thenReturn(
                        new TaskEventPage(List.of(), false, false),
                        new TaskEventPage(List.of(update), false, true));

        FluxExchangeResult<TaskEventController.TaskEventResponse> result = pollingClient.get()
                .uri(root())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(TaskEventController.TaskEventResponse.class);

        StepVerifier.create(result.getResponseBody()).expectNextCount(1).verifyComplete();
        assertTrue(resolutions.get() >= 2);
    }

    @Test
    void rejectsCrossTaskAndMismatchedResumePositionsAndMapsExpiredCursorToGone() {
        TaskEventCursor cursor = new TaskEventCursor(
                organizationId, teamId, taskId, 1, UUID.randomUUID());
        client.get()
                .uri("/api/v1/organizations/" + organizationId + "/teams/" + teamId
                        + "/tasks/" + TaskId.generate() + "/events?after=" + codec.encode(cursor))
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("invalid_cursor");

        client.get()
                .uri(root() + "?after=" + codec.encode(cursor))
                .header(ApiHeaders.LAST_EVENT_ID, codec.encode(new TaskEventCursor(
                        organizationId, teamId, taskId, 2, UUID.randomUUID())))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isBadRequest();

        when(service.events(any(), eq(organizationId), eq(teamId), eq(taskId), any(), eq(50)))
                .thenThrow(new TaskEventCursorExpiredException());
        client.get()
                .uri(root() + "?after=" + codec.encode(cursor))
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isEqualTo(410)
                .expectBody().jsonPath("$.code").isEqualTo("cursor_expired");
    }

    private WebTestClient client(
            TeamRequestIdentityResolver resolver, TaskEventStreamProperties streamProperties) {
        return WebTestClient.bindToController(
                        new TaskEventController(service, resolver, streamProperties))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private TaskEvent event(
            long position, long aggregateVersion, UUID domainEventId, boolean projectionGap) {
        UUID streamEventId = UUID.randomUUID();
        return new TaskEvent(
                new TaskEventCursor(
                        organizationId, teamId, taskId, position, streamEventId),
                TaskEventContext.agentRun(
                        taskId, executionId, Optional.empty(), runId),
                projectionGap,
                new RealtimeEventEnvelope<>(
                        streamEventId,
                        Optional.of(domainEventId),
                        StreamType.TASK,
                        EventType.from("AGENT_RUN_EVENT_RECORDED"),
                        SchemaVersion.V1,
                        Optional.of(new AggregateReference("AGENT_RUN", runId.value())),
                        Optional.of(aggregateVersion),
                        UUID.randomUUID(),
                        Optional.empty(),
                        now,
                        Map.of("eventKind", "PROGRESS", "safeText", "working")));
    }

    private String root() {
        return "/api/v1/organizations/" + organizationId + "/teams/" + teamId
                + "/tasks/" + taskId + "/events";
    }
}
