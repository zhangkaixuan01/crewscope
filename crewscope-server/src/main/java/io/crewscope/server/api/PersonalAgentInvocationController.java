package io.crewscope.server.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.crewscope.agentscope.agui.ControlledAguiClientInput;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.conversation.ClarificationAnswers;
import io.crewscope.application.execution.AguiTransientPayload;
import io.crewscope.application.execution.ConversationAgentCancelExecution;
import io.crewscope.application.execution.ConversationAgentSegment;
import io.crewscope.application.execution.ExecutionCancelResult;
import io.crewscope.application.execution.PersonalAgentInvocationService;
import io.crewscope.application.execution.RuntimeInvocationId;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Controlled Conversation-scoped HTTP/SSE boundary for the owner's Personal Agent. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/conversations")
public final class PersonalAgentInvocationController {

  private final PersonalAgentInvocationService service;
  private final TeamRequestIdentityResolver identityResolver;

  public PersonalAgentInvocationController(
      PersonalAgentInvocationService service, TeamRequestIdentityResolver identityResolver) {
    this.service = service;
    this.identityResolver = identityResolver;
  }

  @PostMapping(
      path = "/{conversationId}/agent-invocations",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Mono<ResponseEntity<Flux<ServerSentEvent<RealtimeEventResponse<? extends AguiTransientPayload>>>>>
      invoke(
          @PathVariable String organizationId,
          @PathVariable String teamId,
          @PathVariable String conversationId,
          @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
          @Valid @RequestBody ControlledAguiClientInput request,
          Authentication authentication,
          ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    ConversationId conversation = conversationId(conversationId);
    IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
    UUID correlationId = ApiCorrelationIds.resolve(exchange);
    return identityResolver
        .resolve(authentication, organization, correlationId)
        .flatMap(
            access ->
                blocking(
                    () ->
                        service.invoke(
                            commandContext(access, idempotencyKey, correlationId),
                            team,
                            conversation,
                            request.getMessage())))
        .map(this::sseResponse);
  }

  @PostMapping(
      path = "/{conversationId}/agent-invocations/{invocationId}/resume",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Mono<ResponseEntity<Flux<ServerSentEvent<RealtimeEventResponse<? extends AguiTransientPayload>>>>>
      resume(
          @PathVariable String organizationId,
          @PathVariable String teamId,
          @PathVariable String conversationId,
          @PathVariable String invocationId,
          @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
          @Valid @RequestBody ClarificationResumeRequest request,
          Authentication authentication,
          ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    ConversationId conversation = conversationId(conversationId);
    RuntimeInvocationId invocation = invocationId(invocationId);
    IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
    UUID correlationId = ApiCorrelationIds.resolve(exchange);
    return identityResolver
        .resolve(authentication, organization, correlationId)
        .flatMap(
            access ->
                blocking(
                    () ->
                        service.resume(
                            commandContext(access, idempotencyKey, correlationId),
                            team,
                            conversation,
                            invocation,
                            request.toAnswers())))
        .map(this::sseResponse);
  }

  @PostMapping("/{conversationId}/agent-invocations/{invocationId}/cancel")
  public Mono<ResponseEntity<CancelResponse>> cancel(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String conversationId,
      @PathVariable String invocationId,
      @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
      @Valid @RequestBody CancelRequest request,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    ConversationId conversation = conversationId(conversationId);
    RuntimeInvocationId invocation = invocationId(invocationId);
    IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
    UUID correlationId = ApiCorrelationIds.resolve(exchange);
    return identityResolver
        .resolve(authentication, organization, correlationId)
        .flatMap(
            access ->
                blocking(
                    () ->
                        service.cancel(
                            commandContext(access, idempotencyKey, correlationId),
                            team,
                            conversation,
                            invocation,
                            request.reason())))
        .flatMap(execution -> cancelResponse(execution, correlationId));
  }

  private ResponseEntity<Flux<ServerSentEvent<RealtimeEventResponse<? extends AguiTransientPayload>>>>
      sseResponse(ConversationAgentSegment segment) {
    Flux<ServerSentEvent<RealtimeEventResponse<? extends AguiTransientPayload>>> body =
        JdkFlowAdapter.flowPublisherToFlux(segment.events())
            .map(
                envelope ->
                    ServerSentEvent.<RealtimeEventResponse<? extends AguiTransientPayload>>builder(
                            RealtimeEventResponse.from(envelope))
                        .id(envelope.eventId().toString())
                        .event(envelope.eventType().value())
                        .build());
    ResponseEntity.BodyBuilder response =
        ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header(ApiHeaders.INVOCATION_ID, segment.invocationId().toString());
    if (segment.replayed()) {
      response.header(ApiHeaders.IDEMPOTENCY_REPLAYED, "true");
    }
    return response.body(body);
  }

  private Mono<ResponseEntity<CancelResponse>> cancelResponse(
      ConversationAgentCancelExecution execution, UUID correlationId) {
    return Mono.fromCompletionStage(execution.result())
        .map(
            result -> {
              ResponseEntity.BodyBuilder response =
                  ResponseEntity.accepted()
                      .cacheControl(CacheControl.noStore())
                      .header(ApiHeaders.INVOCATION_ID, execution.invocationId().toString());
              if (execution.replayed()) {
                response.header(ApiHeaders.IDEMPOTENCY_REPLAYED, "true");
              }
              return response.body(
                  new CancelResponse(
                      execution.invocationId().toString(), result, correlationId));
            });
  }

  private static TeamCommandContext commandContext(
      TeamAccessContext access, IdempotencyKey idempotencyKey, UUID correlationId) {
    return new TeamCommandContext(access, idempotencyKey, correlationId, Optional.empty());
  }

  private static <T> Mono<T> blocking(Callable<T> action) {
    return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
  }

  private static OrganizationId organizationId(String value) {
    try {
      return OrganizationId.from(value);
    } catch (IllegalArgumentException failure) {
      throw invalidIdentifier("organizationId");
    }
  }

  private static TeamId teamId(String value) {
    try {
      return TeamId.from(value);
    } catch (IllegalArgumentException failure) {
      throw invalidIdentifier("teamId");
    }
  }

  private static ConversationId conversationId(String value) {
    try {
      return ConversationId.from(value);
    } catch (IllegalArgumentException failure) {
      throw invalidIdentifier("conversationId");
    }
  }

  private static RuntimeInvocationId invocationId(String value) {
    try {
      return RuntimeInvocationId.from(value);
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

  public static final class CancelRequest {

    @NotBlank @Size(max = 500)
    private final String reason;

    @JsonCreator
    public CancelRequest(@JsonProperty("reason") String reason) {
      this.reason = reason;
    }

    public String reason() {
      return reason;
    }

    /** Rejects client attempts to smuggle runtime or identity controls into cancellation. */
    @JsonAnySetter
    void rejectUnknownProperty(String ignoredProperty, Object ignoredValue) {
      throw new IllegalArgumentException("Unsupported cancel request property");
    }
  }

  public record ClarificationResumeRequest(
      @NotNull @Size(min = 1, max = ClarificationAnswers.MAX_ANSWERS)
          Map<
                  @Pattern(regexp = ClarificationAnswers.FIELD_KEY_PATTERN) String,
                  @NotBlank @Size(max = ClarificationAnswers.MAX_ANSWER_LENGTH) String>
              answers) {

    public ClarificationResumeRequest {
      answers = answers == null ? null : Map.copyOf(answers);
    }

    ClarificationAnswers toAnswers() {
      return new ClarificationAnswers(answers);
    }

    /** Rejects Tool, Session and other runtime fields outside the public clarification DTO. */
    @JsonAnySetter
    void rejectUnknownProperty(String ignoredProperty, Object ignoredValue) {
      throw new IllegalArgumentException("Unsupported clarification resume property");
    }
  }

  public record CancelResponse(
      String invocationId, ExecutionCancelResult result, UUID correlationId) {}
}
