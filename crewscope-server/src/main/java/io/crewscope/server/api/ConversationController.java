package io.crewscope.server.api;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.conversation.AddConversationParticipantCommand;
import io.crewscope.application.conversation.ConversationApplicationService;
import io.crewscope.application.conversation.ConversationDetails;
import io.crewscope.application.conversation.ConversationMessageCursor;
import io.crewscope.application.conversation.ConversationPage;
import io.crewscope.application.conversation.ConversationParticipantView;
import io.crewscope.application.conversation.CreateConversationCommand;
import io.crewscope.application.conversation.MessagePage;
import io.crewscope.application.conversation.PostConversationMessageCommand;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.domain.conversation.Conversation;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationParticipant;
import io.crewscope.domain.conversation.ConversationParticipantId;
import io.crewscope.domain.conversation.ConversationStatus;
import io.crewscope.domain.conversation.ConversationVisibility;
import io.crewscope.domain.conversation.Message;
import io.crewscope.domain.conversation.MessageContent;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Team-scoped HTTP boundary for Conversation discovery and participant management. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/conversations")
public final class ConversationController {

  private final ConversationApplicationService service;
  private final TeamRequestIdentityResolver identityResolver;
  private final ConversationCursorCodec cursorCodec = new ConversationCursorCodec();
  private final ConversationMessageCursorCodec messageCursorCodec =
      new ConversationMessageCursorCodec();

  public ConversationController(
      ConversationApplicationService service, TeamRequestIdentityResolver identityResolver) {
    this.service = service;
    this.identityResolver = identityResolver;
  }

  @PostMapping
  public Mono<ResponseEntity<CommandReceiptResponse>> create(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
      @Valid @RequestBody CreateConversationRequest request,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    return command(
        authentication,
        organization,
        ApiHeaders.requireIdempotencyKey(key),
        exchange,
        context ->
            service.create(
                context,
                team,
                new CreateConversationCommand(request.title(), request.visibility())));
  }

  @GetMapping
  public Mono<ResponseEntity<ConversationPageResponse>> list(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @RequestParam(required = false) ConversationStatus status,
      @RequestParam(required = false) String after,
      @RequestParam(required = false) Integer limit,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    Optional<io.crewscope.application.conversation.ConversationListCursor> cursor =
        Optional.ofNullable(after).map(cursorCodec::decode);
    return query(
            authentication,
            organization,
            exchange,
            access ->
                service.list(
                    access,
                    organization,
                    team,
                    Optional.ofNullable(status),
                    cursor,
                    ApiPagination.limit(limit)))
        .map(
            page ->
                ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(ConversationPageResponse.from(page, cursorCodec)));
  }

  @GetMapping("/{conversationId}")
  public Mono<ResponseEntity<ConversationDetailsResponse>> get(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String conversationId,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    ConversationId conversation = conversationId(conversationId);
    return query(
            authentication,
            organization,
            exchange,
            access -> service.get(access, organization, team, conversation))
        .map(
            details ->
                ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(ConversationDetailsResponse.from(details)));
  }

  @PostMapping("/{conversationId}/participants")
  public Mono<ResponseEntity<CommandReceiptResponse>> addParticipant(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String conversationId,
      @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
      @Valid @RequestBody AddParticipantRequest request,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    ConversationId conversation = conversationId(conversationId);
    PrincipalId target = principalId(request.userPrincipalId());
    return command(
        authentication,
        organization,
        ApiHeaders.requireIdempotencyKey(key),
        exchange,
        context ->
            service.addParticipant(
                context,
                team,
                conversation,
                new AddConversationParticipantCommand(target)));
  }

  @DeleteMapping("/{conversationId}/participants/{participantId}")
  public Mono<ResponseEntity<CommandReceiptResponse>> removeParticipant(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String conversationId,
      @PathVariable String participantId,
      @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    return command(
        authentication,
        organization,
        ApiHeaders.requireIdempotencyKey(key),
        exchange,
        context ->
            service.removeParticipant(
                context,
                team,
                conversationId(conversationId),
                participantId(participantId)));
  }

  @GetMapping("/{conversationId}/messages")
  public Mono<ResponseEntity<MessagePageResponse>> messages(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String conversationId,
      @RequestParam(required = false) String after,
      @RequestParam(required = false) Integer limit,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    ConversationId conversation = conversationId(conversationId);
    Optional<ConversationMessageCursor> cursor =
        Optional.ofNullable(after)
            .map(token -> messageCursorCodec.decode(token, conversation));
    return query(
            authentication,
            organization,
            exchange,
            access ->
                service.messages(
                    access,
                    organization,
                    team,
                    conversation,
                    cursor,
                    ApiPagination.limit(limit)))
        .map(
            page ->
                ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(MessagePageResponse.from(page, messageCursorCodec)));
  }

  @PostMapping("/{conversationId}/messages")
  public Mono<ResponseEntity<CommandReceiptResponse>> postMessage(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String conversationId,
      @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
      @Valid @RequestBody PostMessageRequest request,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    ConversationId conversation = conversationId(conversationId);
    return command(
        authentication,
        organization,
        ApiHeaders.requireIdempotencyKey(key),
        exchange,
        context ->
            service.postUserMessage(
                context,
                team,
                conversation,
                PostConversationMessageCommand.fromMarkdown(request.content())));
  }

  private <T> Mono<ResponseEntity<CommandReceiptResponse>> command(
      Authentication authentication,
      OrganizationId organizationId,
      IdempotencyKey idempotencyKey,
      ServerWebExchange exchange,
      Function<TeamCommandContext, CommandExecution<T>> action) {
    UUID correlationId = ApiCorrelationIds.resolve(exchange);
    return identityResolver
        .resolve(authentication, organizationId, correlationId)
        .flatMap(
            access ->
                blocking(
                    () ->
                        action.apply(
                            new TeamCommandContext(
                                access, idempotencyKey, correlationId, Optional.empty()))))
        .map(CommandReceiptResponse::accepted);
  }

  private <T> Mono<T> query(
      Authentication authentication,
      OrganizationId organizationId,
      ServerWebExchange exchange,
      Function<TeamAccessContext, T> action) {
    return identityResolver
        .resolve(authentication, organizationId, ApiCorrelationIds.resolve(exchange))
        .flatMap(access -> blocking(() -> action.apply(access)));
  }

  private static <T> Mono<T> blocking(Callable<T> action) {
    return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
  }

  private static OrganizationId organizationId(String value) {
    try {
      return OrganizationId.from(value);
    } catch (IllegalArgumentException exception) {
      throw invalidIdentifier("organizationId");
    }
  }

  private static TeamId teamId(String value) {
    try {
      return TeamId.from(value);
    } catch (IllegalArgumentException exception) {
      throw invalidIdentifier("teamId");
    }
  }

  private static ConversationId conversationId(String value) {
    try {
      return ConversationId.from(value);
    } catch (IllegalArgumentException exception) {
      throw invalidIdentifier("conversationId");
    }
  }

  private static ConversationParticipantId participantId(String value) {
    try {
      return ConversationParticipantId.from(value);
    } catch (IllegalArgumentException exception) {
      throw invalidIdentifier("participantId");
    }
  }

  private static PrincipalId principalId(String value) {
    try {
      return PrincipalId.from(value);
    } catch (IllegalArgumentException exception) {
      throw invalidIdentifier("userPrincipalId");
    }
  }

  private static ApiRequestException invalidIdentifier(String field) {
    return new ApiRequestException(
        org.springframework.http.HttpStatus.BAD_REQUEST,
        "invalid_request",
        "Request contains an invalid identifier",
        Map.of("field", field));
  }

  public record CreateConversationRequest(
      @NotBlank @Size(max = Conversation.MAX_TITLE_LENGTH) String title,
      @NotNull ConversationVisibility visibility) {}

  public record AddParticipantRequest(@NotBlank String userPrincipalId) {}

  public record PostMessageRequest(
      @NotBlank @Size(max = MessageContent.MAX_LENGTH) String content) {}

  public record ConversationPageResponse(
      List<ConversationResponse> items, String nextCursor) {

    static ConversationPageResponse from(
        ConversationPage page, ConversationCursorCodec cursorCodec) {
      return new ConversationPageResponse(
          page.conversations().stream().map(ConversationResponse::from).toList(),
          page.nextCursor().map(cursorCodec::encode).orElse(null));
    }
  }

  public record ConversationDetailsResponse(
      ConversationResponse conversation, List<ConversationParticipantResponse> participants) {

    static ConversationDetailsResponse from(ConversationDetails details) {
      return new ConversationDetailsResponse(
          ConversationResponse.from(details.conversation()),
          details.participants().stream()
              .map(ConversationParticipantResponse::from)
              .toList());
    }
  }

  public record ConversationResponse(
      String id,
      String organizationId,
      String teamId,
      String workspaceId,
      String ownerMemberId,
      String ownerPrincipalId,
      String personalAgentPrincipalId,
      String title,
      String visibility,
      String status,
      Long lastMessageSequence,
      long version,
      String createdAt,
      String updatedAt) {

    static ConversationResponse from(Conversation conversation) {
      return new ConversationResponse(
          conversation.id().toString(),
          conversation.scope().organizationId().toString(),
          conversation.scope().teamId().toString(),
          conversation.scope().workspaceId().toString(),
          conversation.ownerMemberId().toString(),
          conversation.ownerPrincipalId().toString(),
          conversation.personalAgentPrincipalId().toString(),
          conversation.title(),
          conversation.visibility().name(),
          conversation.status().name(),
          conversation.lastMessageSequence().map(value -> value.value()).orElse(null),
          conversation.version(),
          conversation.audit().createdAt().toString(),
          conversation.audit().updatedAt().toString());
    }
  }

  public record ConversationParticipantResponse(
      String id,
      String conversationId,
      String principalId,
      String teamMemberId,
      String displayName,
      String principalType,
      String ownerPrincipalId,
      String ownerDisplayName,
      String role,
      String status,
      String joinedByPrincipalId,
      String joinedAt,
      String leftAt,
      long version) {

    static ConversationParticipantResponse from(ConversationParticipantView view) {
      ConversationParticipant participant = view.participant();
      return new ConversationParticipantResponse(
          participant.id().toString(),
          participant.conversationId().toString(),
          participant.principalId().toString(),
          participant.teamMemberId().map(Object::toString).orElse(null),
          view.principal().displayName(),
          view.principal().type().name(),
          view.principal().ownerPrincipalId().map(Object::toString).orElse(null),
          view.owner().map(Principal::displayName).orElse(null),
          participant.role().name(),
          participant.status().name(),
          participant.joinedByPrincipalId().toString(),
          participant.joinedAt().toString(),
          participant.leftAt().map(Object::toString).orElse(null),
          participant.version());
    }
  }

  public record MessagePageResponse(List<MessageResponse> items, String nextCursor) {

    static MessagePageResponse from(
        MessagePage page, ConversationMessageCursorCodec cursorCodec) {
      return new MessagePageResponse(
          page.messages().stream().map(MessageResponse::from).toList(),
          page.nextCursor().map(cursorCodec::encode).orElse(null));
    }
  }

  public record MessageResponse(
      String id,
      String conversationId,
      long sequence,
      String type,
      String participantId,
      String authorPrincipalId,
      String content,
      String createdAt) {

    static MessageResponse from(Message message) {
      return new MessageResponse(
          message.id().toString(),
          message.conversationId().toString(),
          message.sequence().value(),
          message.type().name(),
          message.participantId().map(Object::toString).orElse(null),
          message.authorPrincipalId().map(Object::toString).orElse(null),
          message.content().markdown(),
          message.audit().createdAt().toString());
    }
  }
}
