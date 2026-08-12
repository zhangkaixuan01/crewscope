package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.crewscope.application.execution.AguiTransientPayload;
import io.crewscope.application.conversation.ClarificationAnswers;
import io.crewscope.application.execution.ConversationAgentCancelExecution;
import io.crewscope.application.execution.ConversationAgentSegment;
import io.crewscope.application.execution.ExecutionCancelResult;
import io.crewscope.application.execution.ExecutionSegmentKind;
import io.crewscope.application.execution.PersonalAgentInvocationService;
import io.crewscope.application.execution.RuntimeInvocationId;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.RealtimeEventEnvelope;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Exercises the controlled M2-A03 SSE DTO, headers and cancellation result boundary. */
class PersonalAgentInvocationControllerTest {

  private final OrganizationId organizationId = OrganizationId.generate();
  private final TeamId teamId = TeamId.generate();
  private final ConversationId conversationId = ConversationId.generate();
  private final RuntimeInvocationId invocationId = RuntimeInvocationId.generate();
  private final UUID segmentId = UUID.randomUUID();
  private final UtcTimestamp now = UtcTimestamp.parse("2026-08-10T12:00:00Z");
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
  private PersonalAgentInvocationService service;
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    service = mock(PersonalAgentInvocationService.class);
    TeamRequestIdentityResolver identityResolver =
        (authentication, organization, correlationId) ->
            Mono.just(new TeamAccessContext(owner, false));
    client =
        WebTestClient.bindToController(
                new PersonalAgentInvocationController(service, identityResolver))
            .controllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @Test
  void streamsSafeEnvelopeWithInvocationHeaders() {
    RealtimeEventEnvelope<AguiTransientPayload.RunStarted> started =
        RealtimeEventEnvelope.transientAgUi(
            UUID.randomUUID(),
            EventType.from("RUN_STARTED"),
            SchemaVersion.V1,
            UUID.randomUUID(),
            Optional.empty(),
            now,
            new AguiTransientPayload.RunStarted(
                conversationId.toString(),
                invocationId.toString(),
                segmentId,
                ExecutionSegmentKind.INVOKE));
    when(service.invoke(any(), any(), any(), any()))
        .thenReturn(
            new ConversationAgentSegment(
                invocationId, segmentId, publisher(started), true));

    client
        .post()
        .uri(root() + "/agent-invocations")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "invoke-http-1")
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.TEXT_EVENT_STREAM)
        .bodyValue("{\"message\":\"Review the release\"}")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("Cache-Control", "no-store")
        .expectHeader()
        .valueEquals(ApiHeaders.INVOCATION_ID, invocationId.toString())
        .expectHeader()
        .valueEquals(ApiHeaders.IDEMPOTENCY_REPLAYED, "true")
        .expectBody(String.class)
        .value(body -> org.assertj.core.api.Assertions.assertThat(body)
            .contains("event:RUN_STARTED")
            .contains("\"streamType\":\"AG_UI\""));
  }

  @Test
  void rejectsClientRuntimeControlFieldsBeforeServiceInvocation() {
    String[] forgedFields = {
      "\"agentId\":\"client-owned\"",
      "\"threadId\":\"client-owned\"",
      "\"runId\":\"client-owned\"",
      "\"tools\":[{\"name\":\"shell\"}]",
      "\"runtime\":\"client-owned\"",
      "\"principalId\":\"client-owned\"",
      "\"role\":\"TEAM_OWNER\"",
      "\"sessionId\":\"client-owned\"",
      "\"providerBindingId\":\"client-owned\"",
      "\"context\":{\"teamId\":\"client-owned\"}",
      "\"state\":{\"authorized\":true}",
      "\"forwardedProps\":{\"authorized\":true}"
    };
    for (int index = 0; index < forgedFields.length; index++) {
      client
          .post()
          .uri(root() + "/agent-invocations")
          .header(ApiHeaders.IDEMPOTENCY_KEY, "invoke-http-injection-" + index)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue("{\"message\":\"hello\"," + forgedFields[index] + "}")
          .exchange()
          .expectStatus()
          .isBadRequest()
          .expectBody()
          .jsonPath("$.code")
          .isEqualTo("invalid_request");
    }

    verifyNoInteractions(service);
  }

  @Test
  void resumesOnlyWithBoundedFieldKeyedClarificationAnswers() {
    RealtimeEventEnvelope<AguiTransientPayload.RunStarted> started =
        RealtimeEventEnvelope.transientAgUi(
            UUID.randomUUID(),
            EventType.from("RUN_STARTED"),
            SchemaVersion.V1,
            UUID.randomUUID(),
            Optional.empty(),
            now,
            new AguiTransientPayload.RunStarted(
                conversationId.toString(),
                invocationId.toString(),
                segmentId,
                ExecutionSegmentKind.RESUME));
    when(service.resume(any(), any(), any(), any(), any(ClarificationAnswers.class)))
        .thenReturn(
            new ConversationAgentSegment(
                invocationId, segmentId, publisher(started), false));

    client
        .post()
        .uri(root() + "/agent-invocations/" + invocationId + "/resume")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "resume-http-1")
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.TEXT_EVENT_STREAM)
        .bodyValue("{\"answers\":{\"repository\":\"crewscope-java\"}}")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals(ApiHeaders.INVOCATION_ID, invocationId.toString());

    client
        .post()
        .uri(root() + "/agent-invocations/" + invocationId + "/resume")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "resume-http-injection-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"answers\":{\"repository\":\"crewscope-java\"},\"toolCallId\":\"x\"}")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("invalid_request");
  }

  @Test
  void returnsAcceptedCancelAndMarksReplay() {
    when(service.cancel(any(), any(), any(), any(), any()))
        .thenReturn(
            new ConversationAgentCancelExecution(
                invocationId,
                CompletableFuture.completedFuture(ExecutionCancelResult.ALREADY_TERMINAL),
                true));

    client
        .post()
        .uri(root() + "/agent-invocations/" + invocationId + "/cancel")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "cancel-http-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"reason\":\"Owner requested cancellation\"}")
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectHeader()
        .valueEquals(ApiHeaders.IDEMPOTENCY_REPLAYED, "true")
        .expectBody()
        .jsonPath("$.result")
        .isEqualTo("ALREADY_TERMINAL")
        .jsonPath("$.invocationId")
        .isEqualTo(invocationId.toString());
  }

  private String root() {
    return "/api/v1/organizations/"
        + organizationId
        + "/teams/"
        + teamId
        + "/conversations/"
        + conversationId;
  }

  private static Flow.Publisher<RealtimeEventEnvelope<? extends AguiTransientPayload>> publisher(
      RealtimeEventEnvelope<? extends AguiTransientPayload> event) {
    return subscriber ->
        subscriber.onSubscribe(
            new Flow.Subscription() {
              private boolean emitted;

              @Override
              public void request(long count) {
                if (!emitted && count > 0) {
                  emitted = true;
                  subscriber.onNext(event);
                  subscriber.onComplete();
                }
              }

              @Override
              public void cancel() {
                emitted = true;
              }
            });
  }
}
