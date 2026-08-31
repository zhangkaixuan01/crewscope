package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.conversation.ConversationApplicationService;
import io.crewscope.application.conversation.ConversationDetails;
import io.crewscope.application.conversation.ConversationListCursor;
import io.crewscope.application.conversation.ConversationMessageCursor;
import io.crewscope.application.conversation.ConversationPage;
import io.crewscope.application.conversation.ConversationParticipantView;
import io.crewscope.application.conversation.MessagePage;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationMessageAppend;
import io.crewscope.domain.conversation.ConversationVisibility;
import io.crewscope.domain.conversation.MessageContent;
import io.crewscope.domain.conversation.MessageId;
import io.crewscope.domain.conversation.PersonalConversationInitialization;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Proves all M2-A01 routes, opaque cursors, no-store responses and receipt semantics. */
class ConversationControllerTest {

  private final OrganizationId organizationId = OrganizationId.generate();
  private final UtcTimestamp now = UtcTimestamp.parse("2026-08-10T09:00:00Z");
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
  private final TeamInitialization initialization =
      TeamInitialization.create(owner, "Conversation Team", now);
  private final PersonalConversationInitialization conversation =
      PersonalConversationInitialization.start(
          ConversationId.generate(),
          initialization.defaultWorkspace(),
          initialization.ownerMember(),
          owner,
          initialization.ownerPersonalAgent(),
          "Release review",
          ConversationVisibility.PRIVATE,
          now);
  private final ConversationMessageAppend message =
      conversation
          .conversation()
          .appendMessage(
              MessageId.generate(),
              conversation.ownerParticipant(),
              owner,
              new MessageContent("Review the release"),
              now);

  private ConversationApplicationService service;
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    service = mock(ConversationApplicationService.class);
    TeamRequestIdentityResolver resolver =
        (authentication, organization, correlationId) ->
            Mono.just(new TeamAccessContext(owner, false));
    client =
        WebTestClient.bindToController(new ConversationController(service, resolver))
            .controllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @Test
  void createsConversationWithAcceptedNoStoreReceipt() {
    CommandReceipt receipt = receipt(0);
    when(service.create(any(), eq(initialization.team().id()), any()))
        .thenReturn(CommandExecution.completed(conversation, receipt));

    client
        .post()
        .uri(root())
        .header(ApiHeaders.IDEMPOTENCY_KEY, "conversation-create-http-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"title\":\"Release review\",\"visibility\":\"PRIVATE\"}")
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectHeader()
        .valueEquals("Cache-Control", "no-store")
        .expectBody()
        .jsonPath("$.commandId")
        .isEqualTo(receipt.commandId().toString());
  }

  @Test
  void listsAndReadsConversationWithNoStoreAndOpaqueCursor() {
    ConversationListCursor cursor =
        ConversationListCursor.from(conversation.conversation());
    String token = new ConversationCursorCodec().encode(cursor);
    when(service.list(
            any(),
            eq(organizationId),
            eq(initialization.team().id()),
            eq(Optional.empty()),
            eq(Optional.of(cursor)),
            eq(20)))
        .thenReturn(new ConversationPage(List.of(conversation.conversation()), Optional.of(cursor)));
    when(service.get(any(), any(), any(), eq(conversation.conversation().id())))
        .thenReturn(
            new ConversationDetails(
                conversation.conversation(),
                List.of(
                    new ConversationParticipantView(
                        conversation.ownerParticipant(), owner, Optional.empty()),
                    new ConversationParticipantView(
                        conversation.agentParticipant(),
                        initialization.ownerPersonalAgent().agentPrincipal(),
                        Optional.of(owner)))));

    client
        .get()
        .uri(root() + "?after=" + token + "&limit=20")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("Cache-Control", "no-store")
        .expectBody()
        .jsonPath("$.items[0].title")
        .isEqualTo("Release review")
        .jsonPath("$.nextCursor")
        .isEqualTo(token);

    client
        .get()
        .uri(root() + "/" + conversation.conversation().id())
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("Cache-Control", "no-store")
        .expectBody()
        .jsonPath("$.participants.length()")
        .isEqualTo(2)
        .jsonPath("$.participants[0].displayName")
        .isEqualTo("Owner")
        .jsonPath("$.participants[0].principalType")
        .isEqualTo("USER")
        .jsonPath("$.participants[1].displayName")
        .isEqualTo("Owner · Personal Agent")
        .jsonPath("$.participants[1].principalType")
        .isEqualTo("PERSONAL_AGENT")
        .jsonPath("$.participants[1].ownerPrincipalId")
        .isEqualTo(owner.id().toString())
        .jsonPath("$.participants[1].ownerDisplayName")
        .isEqualTo("Owner")
        .jsonPath("$.conversation.personalAgentPrincipalId")
        .isEqualTo(initialization.ownerPersonalAgent().agentPrincipal().id().toString());
  }

  @Test
  void managesParticipantsAndReturnsConversationBoundMessageHistory() {
    CommandReceipt addReceipt = receipt(0);
    CommandReceipt removeReceipt = receipt(1);
    when(service.addParticipant(any(), any(), any(), any()))
        .thenReturn(CommandExecution.completed(conversation.ownerParticipant(), addReceipt));
    when(service.removeParticipant(any(), any(), any(), any()))
        .thenReturn(CommandExecution.completed(conversation.ownerParticipant(), removeReceipt));
    ConversationMessageCursor cursor =
        new ConversationMessageCursor(
            conversation.conversation().id(), message.message().sequence());
    String token = new ConversationMessageCursorCodec().encode(cursor);
    when(service.messages(
            any(),
            eq(organizationId),
            eq(initialization.team().id()),
            eq(conversation.conversation().id()),
            eq(Optional.of(cursor)),
            eq(10)))
        .thenReturn(new MessagePage(List.of(message.message()), Optional.of(cursor)));

    client
        .post()
        .uri(root() + "/" + conversation.conversation().id() + "/participants")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "participant-add-http-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"userPrincipalId\":\"" + owner.id() + "\"}")
        .exchange()
        .expectStatus()
        .isAccepted();

    client
        .delete()
        .uri(
            root()
                + "/"
                + conversation.conversation().id()
                + "/participants/"
                + conversation.ownerParticipant().id())
        .header(ApiHeaders.IDEMPOTENCY_KEY, "participant-remove-http-1")
        .exchange()
        .expectStatus()
        .isAccepted();

    client
        .get()
        .uri(
            root()
                + "/"
                + conversation.conversation().id()
                + "/messages?after="
                + token
                + "&limit=10")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("Cache-Control", "no-store")
        .expectBody()
        .jsonPath("$.items[0].content")
        .isEqualTo("Review the release")
        .jsonPath("$.nextCursor")
        .isEqualTo(token);
  }

  @Test
  void postsUserMessageAndMarksAnIdempotentReplay() {
    CommandReceipt receipt = receipt(1);
    when(service.postUserMessage(any(), any(), any(), any()))
        .thenReturn(CommandExecution.completed(message, receipt))
        .thenReturn(CommandExecution.replayed(receipt));
    String uri = root() + "/" + conversation.conversation().id() + "/messages";

    client
        .post()
        .uri(uri)
        .header(ApiHeaders.IDEMPOTENCY_KEY, "message-http-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"content\":\"Review the release\"}")
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectHeader()
        .valueEquals("Cache-Control", "no-store")
        .expectHeader()
        .doesNotExist(ApiHeaders.IDEMPOTENCY_REPLAYED);

    client
        .post()
        .uri(uri)
        .header(ApiHeaders.IDEMPOTENCY_KEY, "message-http-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"content\":\"Review the release\"}")
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectHeader()
        .valueEquals(ApiHeaders.IDEMPOTENCY_REPLAYED, "true");
  }

  @Test
  void validatesMessageContentAndIdempotencyKeyBeforeCallingTheService() {
    String uri = root() + "/" + conversation.conversation().id() + "/messages";

    client
        .post()
        .uri(uri)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"content\":\"valid\"}")
        .exchange()
        .expectStatus()
        .isBadRequest();

    client
        .post()
        .uri(uri)
        .header(ApiHeaders.IDEMPOTENCY_KEY, "message-http-invalid-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"content\":\"unsafe\\u0000content\"}")
        .exchange()
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("invalid_value");
  }

  @Test
  void rejectsInvalidIdentifiersAndCrossConversationMessageCursors() {
    client
        .get()
        .uri(
            "/api/v1/organizations/"
                + organizationId
                + "/teams/"
                + initialization.team().id()
                + "/conversations/not-a-uuid")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.details.field")
        .isEqualTo("conversationId");

    ConversationMessageCursor other =
        new ConversationMessageCursor(ConversationId.generate(), message.message().sequence());
    client
        .get()
        .uri(
            root()
                + "/"
                + conversation.conversation().id()
                + "/messages?after="
                + new ConversationMessageCursorCodec().encode(other))
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("invalid_cursor");
  }

  private String root() {
    return "/api/v1/organizations/"
        + organizationId
        + "/teams/"
        + initialization.team().id()
        + "/conversations";
  }

  private static CommandReceipt receipt(long version) {
    return new CommandReceipt(
        UUID.randomUUID(), UUID.randomUUID(), version, UUID.randomUUID());
  }
}
