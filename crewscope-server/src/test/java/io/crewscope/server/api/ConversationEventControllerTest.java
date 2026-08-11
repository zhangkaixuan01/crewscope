package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.conversation.ConversationApplicationService;
import io.crewscope.application.conversation.ConversationEvent;
import io.crewscope.application.conversation.ConversationEventCursor;
import io.crewscope.application.conversation.ConversationEventCursorExpiredException;
import io.crewscope.application.conversation.ConversationEventPage;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.conversation.ConversationId;
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
import io.crewscope.server.config.application.ConversationEventStreamProperties;
import java.time.Duration;
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

/** Proves JSON history, SSE Last-Event-ID and safe Cursor failures at the HTTP boundary. */
class ConversationEventControllerTest {

  private final OrganizationId organizationId = OrganizationId.generate();
  private final TeamId teamId = TeamId.generate();
  private final ConversationId conversationId = ConversationId.generate();
  private final UtcTimestamp now = UtcTimestamp.parse("2026-08-11T04:00:00Z");
  private final Principal owner =
      Principal.create(
          PrincipalId.generate(),
          PrincipalScope.organization(organizationId),
          PrincipalType.USER,
          Optional.empty(),
          "Owner",
          Optional.empty(),
          PrincipalVisibility.ORGANIZATION,
          now);
  private final ConversationEventCursorCodec codec = new ConversationEventCursorCodec();

  private ConversationApplicationService service;
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    service = mock(ConversationApplicationService.class);
    TeamRequestIdentityResolver resolver =
        (authentication, organization, correlationId) ->
            Mono.just(new TeamAccessContext(owner, false));
    ConversationEventStreamProperties properties = new ConversationEventStreamProperties();
    properties.setPollInterval(Duration.ofHours(1));
    client =
        WebTestClient.bindToController(
                new ConversationEventController(service, resolver, properties))
            .controllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @Test
  void returnsAscendingJsonPageWithResumeCursor() {
    ConversationEvent event = event(1);
    when(service.events(
            any(),
            eq(organizationId),
            eq(teamId),
            eq(conversationId),
            eq(Optional.empty()),
            eq(1)))
        .thenReturn(new ConversationEventPage(List.of(event), true));

    client
        .get()
        .uri(root() + "?limit=1")
        .accept(MediaType.APPLICATION_JSON)
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("Cache-Control", "no-store")
        .expectBody()
        .jsonPath("$.hasMore")
        .isEqualTo(true)
        .jsonPath("$.nextCursor")
        .isEqualTo(codec.encode(event.cursor()))
        .jsonPath("$.items[0].event.streamType")
        .isEqualTo("CONVERSATION")
        .jsonPath("$.items[0].event.domainEventId")
        .isEqualTo(event.envelope().domainEventId().orElseThrow().toString());
  }

  @Test
  void resumesSseAfterLastEventIdAndUsesCursorAsSseId() {
    ConversationEventCursor prior =
        new ConversationEventCursor(
            organizationId, teamId, conversationId, 1, UUID.randomUUID());
    ConversationEvent next = event(2);
    when(service.events(
            any(),
            eq(organizationId),
            eq(teamId),
            eq(conversationId),
            eq(Optional.of(prior)),
            eq(100)))
        .thenReturn(new ConversationEventPage(List.of(next), false));

    FluxExchangeResult<RealtimeEventResponse> result =
        client
            .get()
            .uri(root())
            .header(ApiHeaders.LAST_EVENT_ID, codec.encode(prior))
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .valueEquals("Cache-Control", "no-store")
            .returnResult(RealtimeEventResponse.class);

    StepVerifier.create(result.getResponseBody())
        .assertNext(envelope ->
            org.junit.jupiter.api.Assertions.assertEquals(
                next.envelope().eventId(), envelope.eventId()))
        .thenCancel()
        .verify(Duration.ofSeconds(5));
  }

  @Test
  void resolvesCurrentIdentityAgainBeforeEachSsePoll() {
    AtomicInteger resolutions = new AtomicInteger();
    TeamRequestIdentityResolver resolver =
        (authentication, organization, correlationId) -> {
          resolutions.incrementAndGet();
          return Mono.just(new TeamAccessContext(owner, false));
        };
    ConversationEventStreamProperties properties = new ConversationEventStreamProperties();
    properties.setPollInterval(Duration.ofMillis(5));
    WebTestClient pollingClient =
        WebTestClient.bindToController(
                new ConversationEventController(service, resolver, properties))
            .controllerAdvice(new ApiExceptionHandler())
            .build();
    ConversationEvent update = event(1);
    when(service.events(
            any(),
            eq(organizationId),
            eq(teamId),
            eq(conversationId),
            eq(Optional.empty()),
            eq(100)))
        .thenReturn(
            new ConversationEventPage(List.of(), false),
            new ConversationEventPage(List.of(update), false));

    FluxExchangeResult<RealtimeEventResponse> result =
        pollingClient
            .get()
            .uri(root())
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult(RealtimeEventResponse.class);

    StepVerifier.create(result.getResponseBody()).expectNextCount(1).thenCancel().verify();
    org.junit.jupiter.api.Assertions.assertTrue(resolutions.get() >= 2);
  }

  @Test
  void rejectsCrossConversationAndMapsCompactedPositionToGone() {
    ConversationEventCursor cursor =
        new ConversationEventCursor(
            organizationId, teamId, conversationId, 1, UUID.randomUUID());
    client
        .get()
        .uri(
            "/api/v1/organizations/"
                + organizationId
                + "/teams/"
                + teamId
                + "/conversations/"
                + ConversationId.generate()
                + "/events?after="
                + codec.encode(cursor))
        .accept(MediaType.APPLICATION_JSON)
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("invalid_cursor");

    when(service.events(any(), eq(organizationId), eq(teamId), eq(conversationId), any(), eq(50)))
        .thenThrow(new ConversationEventCursorExpiredException());
    client
        .get()
        .uri(root() + "?after=" + codec.encode(cursor))
        .accept(MediaType.APPLICATION_JSON)
        .exchange()
        .expectStatus()
        .isEqualTo(410)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("cursor_expired");
  }

  private ConversationEvent event(long position) {
    UUID streamEventId = UUID.randomUUID();
    UUID domainEventId = UUID.randomUUID();
    return new ConversationEvent(
        new ConversationEventCursor(
            organizationId, teamId, conversationId, position, streamEventId),
        new RealtimeEventEnvelope<>(
            streamEventId,
            Optional.of(domainEventId),
            StreamType.CONVERSATION,
            EventType.from("CONVERSATION_MESSAGE_POSTED"),
            SchemaVersion.V1,
            Optional.of(new AggregateReference("CONVERSATION", conversationId.value())),
            Optional.of(position),
            UUID.randomUUID(),
            Optional.empty(),
            now,
            Map.of("contentMarkdown", "event " + position)));
  }

  private String root() {
    return "/api/v1/organizations/"
        + organizationId
        + "/teams/"
        + teamId
        + "/conversations/"
        + conversationId
        + "/events";
  }
}
