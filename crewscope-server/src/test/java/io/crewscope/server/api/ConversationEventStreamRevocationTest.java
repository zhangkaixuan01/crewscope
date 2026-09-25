package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.conversation.ConversationApplicationService;
import io.crewscope.application.conversation.ConversationEvent;
import io.crewscope.application.conversation.ConversationEventCursor;
import io.crewscope.application.conversation.ConversationEventPage;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.PolicyDeniedException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * M9b-A07 revocation bound for the Conversation SSE boundary: an update batch read while the
 * member was still authorized is cut short when the post-batch revalidation fails, and flows
 * unchanged when the member remains authorized.
 */
class ConversationEventStreamRevocationTest {

  private final OrganizationId organizationId = OrganizationId.generate();
  private final TeamId teamId = TeamId.generate();
  private final ConversationId conversationId = ConversationId.generate();
  private final UtcTimestamp now = UtcTimestamp.parse("2026-09-09T04:00:00Z");
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

  private ConversationApplicationService service;
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    service = mock(ConversationApplicationService.class);
    TeamRequestIdentityResolver resolver =
        (authentication, organization, correlationId) -> Mono.just(new TeamAccessContext(owner, false));
    ConversationEventStreamProperties properties = new ConversationEventStreamProperties();
    properties.setPollInterval(Duration.ofMillis(5));
    client =
        WebTestClient.bindToController(
                new ConversationEventController(service, resolver, properties))
            .controllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @Test
  void cutsTheBatchShortWhenRevocationLandsBetweenItsReadAndEmission() {
    ConversationEvent committed = event(1);
    ConversationEvent revoked = event(2);
    when(service.events(
            any(), eq(organizationId), eq(teamId), eq(conversationId), any(), anyInt()))
        .thenReturn(
            new ConversationEventPage(List.of(committed), false),
            new ConversationEventPage(List.of(revoked), false));
    doThrow(new PolicyDeniedException("access this Team's Conversations"))
        .when(service)
        .requireStillReadable(any(), eq(organizationId), eq(teamId), eq(conversationId));

    FluxExchangeResult<RealtimeEventResponse> result = client
        .get()
        .uri(root())
        .accept(MediaType.TEXT_EVENT_STREAM)
        .exchange()
        .expectStatus()
        .isOk()
        .returnResult(RealtimeEventResponse.class);

    // The committed initial row flows; the revoked update batch is cut before its row.
    StepVerifier.create(result.getResponseBody())
        .assertNext(envelope -> assertEquals(committed.envelope().eventId(), envelope.eventId()))
        .expectError(PolicyDeniedException.class)
        .verify(Duration.ofSeconds(5));

    verify(service, times(2))
        .events(any(), eq(organizationId), eq(teamId), eq(conversationId), any(), anyInt());
    verify(service)
        .requireStillReadable(any(), eq(organizationId), eq(teamId), eq(conversationId));
  }

  @Test
  void emitsAnUpdateBatchAfterItsSuccessfulRevalidation() {
    ConversationEvent update = event(1);
    when(service.events(
            any(), eq(organizationId), eq(teamId), eq(conversationId), any(), anyInt()))
        .thenReturn(
            new ConversationEventPage(List.of(), false),
            new ConversationEventPage(List.of(update), false),
            new ConversationEventPage(List.of(), false));

    FluxExchangeResult<RealtimeEventResponse> result = client
        .get()
        .uri(root())
        .accept(MediaType.TEXT_EVENT_STREAM)
        .exchange()
        .expectStatus()
        .isOk()
        .returnResult(RealtimeEventResponse.class);

    StepVerifier.create(result.getResponseBody())
        .assertNext(envelope -> assertEquals(update.envelope().eventId(), envelope.eventId()))
        .thenCancel()
        .verify(Duration.ofSeconds(5));

    // Exactly one revalidation per non-empty batch; empty poll batches skip it.
    verify(service)
        .requireStillReadable(any(), eq(organizationId), eq(teamId), eq(conversationId));
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
