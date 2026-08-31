package io.crewscope.application.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.execution.AgentMessageCandidate;
import io.crewscope.application.execution.RuntimeInvocationId;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.conversation.Conversation;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationParticipant;
import io.crewscope.domain.conversation.ConversationParticipantRole;
import io.crewscope.domain.conversation.ConversationParticipantStatus;
import io.crewscope.domain.conversation.Message;
import io.crewscope.domain.conversation.MessageType;
import io.crewscope.domain.conversation.ConversationVisibility;
import io.crewscope.domain.conversation.ConversationVisibilityPolicy;
import io.crewscope.domain.conversation.PersonalConversationInitialization;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.IdempotencyConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamJoinMethod;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workspace.AgentProfile;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Proves trusted Personal Agent resolution, visibility and participant lifecycle use cases. */
class ConversationApplicationServiceTest {

  private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-10T08:00:00Z");

  @Test
  void createsOwnerAndAgentParticipantsFromCurrentServerFacts() {
    Fixture fixture = new Fixture();

    var execution =
        fixture.service.create(
            fixture.commandContext("conversation-create-1"),
            fixture.initialization.team().id(),
            new CreateConversationCommand("  Incident review  ", ConversationVisibility.PRIVATE));

    PersonalConversationInitialization created = execution.result().orElseThrow();
    assertEquals("Incident review", created.conversation().title());
    assertEquals(fixture.owner.id(), created.conversation().ownerPrincipalId());
    assertEquals(
        fixture.initialization.ownerPersonalAgent().agentPrincipal().id(),
        created.conversation().personalAgentPrincipalId());
    assertEquals(2, fixture.store.participants.size());
    assertEquals(0, execution.receipt().committedVersion());
    assertEquals("CONVERSATION_CREATED", fixture.store.events.get(0).eventType().value());
    assertEquals(1, fixture.store.conversationEventCount);
    assertEquals(1, fixture.store.outboxCount);
  }

  @Test
  void listsOnlyPolicyDiscoverableRowsAndBindsTheQueryToTheActor() {
    Fixture fixture = new Fixture();
    PersonalConversationInitialization own = fixture.conversation(ConversationVisibility.PRIVATE);
    Principal other = fixture.activeUser("Other");
    TeamMember otherMember = fixture.addMember(other);
    PersonalConversationInitialization hidden =
        PersonalConversationInitialization.start(
            ConversationId.generate(),
            fixture.initialization.defaultWorkspace(),
            otherMember,
            other,
            io.crewscope.domain.workspace.PersonalAgentInitialization.createDefault(
                otherMember, fixture.initialization.defaultWorkspace(), other, NOW),
            "Hidden",
            ConversationVisibility.PRIVATE,
            NOW);
    fixture.store.add(hidden);
    fixture.store.queryPage =
        new ConversationPage(
            List.of(own.conversation(), hidden.conversation()), Optional.empty());

    ConversationPage page =
        fixture.service.list(
            fixture.access(fixture.owner),
            fixture.organizationId,
            fixture.initialization.team().id(),
            Optional.empty(),
            Optional.empty(),
            20);

    assertEquals(List.of(own.conversation()), page.conversations());
    assertEquals(fixture.owner.id(), fixture.store.lastConversationQuery.viewerPrincipalId());
  }

  @Test
  void hidesUnreadablePrivateDetailsAsMissing() {
    Fixture fixture = new Fixture();
    Principal other = fixture.activeUser("Other");
    TeamMember otherMember = fixture.addMember(other);
    PersonalConversationInitialization hidden =
        PersonalConversationInitialization.start(
            ConversationId.generate(),
            fixture.initialization.defaultWorkspace(),
            otherMember,
            other,
            io.crewscope.domain.workspace.PersonalAgentInitialization.createDefault(
                otherMember, fixture.initialization.defaultWorkspace(), other, NOW),
            "Hidden",
            ConversationVisibility.PRIVATE,
            NOW);
    fixture.store.add(hidden);

    assertThrows(
        AggregateNotFoundException.class,
        () ->
            fixture.service.get(
                fixture.access(fixture.owner),
                fixture.organizationId,
                fixture.initialization.team().id(),
                hidden.conversation().id()));
  }

  @Test
  void appliesTheLeftParticipantHistoryCutoffToTheRepositoryQuery() {
    Fixture fixture = new Fixture();
    PersonalConversationInitialization base = fixture.conversation(ConversationVisibility.PRIVATE);
    Principal memberUser = fixture.activeUser("Member");
    TeamMember member = fixture.addMember(memberUser);
    ConversationParticipant participant =
        ConversationParticipant.joinMember(
            base.conversation(), member, memberUser, fixture.owner, NOW);
    ConversationParticipant left =
        participant.leave(
            fixture.owner, UtcTimestamp.parse("2026-08-10T08:10:00Z"));
    fixture.store.participants.put(left.id(), left);

    fixture.service.messages(
        fixture.access(memberUser),
        fixture.organizationId,
        fixture.initialization.team().id(),
        base.conversation().id(),
        Optional.empty(),
        50);
    fixture.service.events(
        fixture.access(memberUser),
        fixture.organizationId,
        fixture.initialization.team().id(),
        base.conversation().id(),
        Optional.empty(),
        50);

    assertEquals(left.leftAt(), fixture.store.lastMessageQuery.visibleThrough());
    assertEquals(
        left.leftAt(), fixture.store.lastConversationEventQuery.visibleThrough());
    assertTrue(fixture.store.lastMessageQuery.cursor().isEmpty());
  }

  @Test
  void ownerAddsAndRemovesAStableMemberParticipant() {
    Fixture fixture = new Fixture();
    PersonalConversationInitialization base = fixture.conversation(ConversationVisibility.TEAM);
    Principal memberUser = fixture.activeUser("Member");
    TeamMember member = fixture.addMember(memberUser);

    ConversationParticipant joined =
        fixture.service
            .addParticipant(
                fixture.commandContext("participant-add-1"),
                fixture.initialization.team().id(),
                base.conversation().id(),
                new AddConversationParticipantCommand(memberUser.id()))
            .result()
            .orElseThrow();
    ConversationParticipant left =
        fixture.service
            .removeParticipant(
                fixture.commandContext("participant-remove-1"),
                fixture.initialization.team().id(),
                base.conversation().id(),
                joined.id())
            .result()
            .orElseThrow();

    assertEquals(member.id(), joined.teamMemberId().orElseThrow());
    assertEquals(ConversationParticipantStatus.ACTIVE, joined.status());
    assertEquals(ConversationParticipantStatus.LEFT, left.status());
    assertEquals(
        List.of(
            "CONVERSATION_PARTICIPANT_JOINED", "CONVERSATION_PARTICIPANT_LEFT"),
        fixture.store.events.stream().map(value -> value.eventType().value()).toList());
  }

  @Test
  void allowsMemberToLeaveSelfAndProtectsOwnerParticipation() {
    Fixture fixture = new Fixture();
    PersonalConversationInitialization base = fixture.conversation(ConversationVisibility.TEAM);
    Principal memberUser = fixture.activeUser("Member");
    TeamMember member = fixture.addMember(memberUser);
    ConversationParticipant joined =
        ConversationParticipant.joinMember(
            base.conversation(), member, memberUser, fixture.owner, NOW);
    fixture.store.participants.put(joined.id(), joined);

    ConversationParticipant left =
        fixture.service
            .removeParticipant(
                fixture.commandContext(memberUser, "participant-self-remove-1"),
                fixture.initialization.team().id(),
                base.conversation().id(),
                joined.id())
            .result()
            .orElseThrow();

    assertEquals(ConversationParticipantStatus.LEFT, left.status());
    assertThrows(
        DomainValidationException.class,
        () ->
            fixture.service.removeParticipant(
                fixture.commandContext("participant-owner-remove-1"),
                fixture.initialization.team().id(),
                base.conversation().id(),
                base.ownerParticipant().id()));
  }

  @Test
  void keepsArchivedConversationReadableAndRejectsParticipantMutation() {
    Fixture fixture = new Fixture();
    PersonalConversationInitialization base = fixture.conversation(ConversationVisibility.PRIVATE);
    Conversation archived =
        base.conversation()
            .archive(fixture.owner, UtcTimestamp.parse("2026-08-10T08:20:00Z"));
    fixture.store.conversations.put(archived.id(), archived);

    ConversationDetails details =
        fixture.service.get(
            fixture.access(fixture.owner),
            fixture.organizationId,
            fixture.initialization.team().id(),
            archived.id());

    assertEquals(
        io.crewscope.domain.conversation.ConversationStatus.ARCHIVED,
        details.conversation().status());
    ConversationParticipantView agentView =
        details.participants().stream()
            .filter(view -> view.participant().role() == ConversationParticipantRole.AGENT)
            .findFirst()
            .orElseThrow();
    assertEquals(
        fixture.initialization.ownerPersonalAgent().agentPrincipal().displayName(),
        agentView.principal().displayName());
    assertEquals(fixture.owner.id(), agentView.owner().orElseThrow().id());
    assertEquals("Owner", agentView.owner().orElseThrow().displayName());
    assertThrows(
        DomainValidationException.class,
        () ->
            fixture.service.addParticipant(
                fixture.commandContext("participant-archive-1"),
                fixture.initialization.team().id(),
                archived.id(),
                new AddConversationParticipantCommand(fixture.owner.id())));
  }

  @Test
  void participantViewRejectsPrincipalTypesThatDoNotMatchTheParticipationRole() {
    Fixture fixture = new Fixture();
    PersonalConversationInitialization base = fixture.conversation(ConversationVisibility.PRIVATE);
    Principal serviceWithOwnerParticipantId =
        Principal.create(
            base.conversation().ownerPrincipalId(),
            PrincipalScope.organization(fixture.organizationId),
            PrincipalType.SERVICE,
            Optional.empty(),
            "Runtime service",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ConversationParticipantView(
                base.ownerParticipant(), serviceWithOwnerParticipantId, Optional.empty()));
  }

  @Test
  void postsUserMessageWithServerAuthorshipSequenceEventAndClientKey() {
    Fixture fixture = new Fixture();
    PersonalConversationInitialization base = fixture.conversation(ConversationVisibility.PRIVATE);

    var execution =
        fixture.service.postUserMessage(
            fixture.commandContext("message-post-1"),
            fixture.initialization.team().id(),
            base.conversation().id(),
            PostConversationMessageCommand.fromMarkdown("  **Ship** safely.  "));

    Message message = execution.result().orElseThrow().message();
    assertEquals(MessageType.USER_MESSAGE, message.type());
    assertEquals(1L, message.sequence().value());
    assertEquals("**Ship** safely.", message.content().markdown());
    assertEquals(fixture.owner.id(), message.authorPrincipalId().orElseThrow());
    assertEquals("message-post-1", fixture.store.lastClientMessageKey);
    assertEquals(1, fixture.store.messages.size());
    assertEquals(1, fixture.store.conversations.get(base.conversation().id()).version());
    assertEquals("CONVERSATION_MESSAGE_POSTED", fixture.store.events.get(0).eventType().value());
    assertEquals("CONVERSATION", fixture.store.events.get(0).aggregate().type());
    assertEquals(1, execution.receipt().committedVersion());
  }

  @Test
  void commitsAgentReplyWithPersonalAgentActorAndStableSegmentKey() {
    Fixture fixture = new Fixture();
    PersonalConversationInitialization base = fixture.conversation(ConversationVisibility.PRIVATE);
    RuntimeInvocationId invocationId = RuntimeInvocationId.generate();
    UUID segmentId = UUID.randomUUID();
    AgentMessageCandidate candidate =
        new AgentMessageCandidate(
            invocationId,
            segmentId,
            base.conversation().id(),
            base.agentParticipant().id(),
            fixture.initialization.ownerPersonalAgent().agentPrincipal().id(),
            new io.crewscope.domain.conversation.MessageContent("Agent answer"),
            NOW);

    Message committed =
        fixture.service.commitAgentMessage(
            candidate, fixture.organizationId, UUID.randomUUID(), Optional.empty());
    Message replayed =
        fixture.service.commitAgentMessage(
            candidate, fixture.organizationId, UUID.randomUUID(), Optional.empty());

    assertEquals(committed.id(), replayed.id());
    assertEquals(MessageType.AGENT_MESSAGE, committed.type());
    assertEquals(1L, committed.sequence().value());
    assertEquals("agent:" + invocationId + ":" + segmentId, fixture.store.lastClientMessageKey);
    assertEquals(1, fixture.store.events.size());
    assertEquals(
        io.crewscope.domain.shared.event.EventActorType.PERSONAL_AGENT,
        fixture.store.events.get(0).actor().type());
    assertEquals(1, fixture.store.outboxCount);
  }

  @Test
  void replaysSameMessageAndRejectsChangedContentForTheSameKey() {
    Fixture fixture = new Fixture();
    PersonalConversationInitialization base = fixture.conversation(ConversationVisibility.PRIVATE);

    var first =
        fixture.service.postUserMessage(
            fixture.commandContext("message-replay-1"),
            fixture.initialization.team().id(),
            base.conversation().id(),
            PostConversationMessageCommand.fromMarkdown("Same content"));
    var replay =
        fixture.service.postUserMessage(
            fixture.commandContext("message-replay-1"),
            fixture.initialization.team().id(),
            base.conversation().id(),
            PostConversationMessageCommand.fromMarkdown(" Same content "));

    assertTrue(replay.replayed());
    assertEquals(first.receipt(), replay.receipt());
    assertEquals(1, fixture.store.messages.size());
    assertThrows(
        IdempotencyConflictException.class,
        () ->
            fixture.service.postUserMessage(
                fixture.commandContext("message-replay-1"),
                fixture.initialization.team().id(),
                base.conversation().id(),
                PostConversationMessageCommand.fromMarkdown("Changed content")));
  }

  @Test
  void requiresAnActiveUserParticipantEvenWhenTeamConversationIsDiscoverable() {
    Fixture fixture = new Fixture();
    PersonalConversationInitialization base = fixture.conversation(ConversationVisibility.TEAM);
    Principal memberUser = fixture.activeUser("Member");
    fixture.addMember(memberUser);

    assertThrows(
        PolicyDeniedException.class,
        () ->
            fixture.service.postUserMessage(
                fixture.commandContext(memberUser, "message-nonparticipant-1"),
                fixture.initialization.team().id(),
                base.conversation().id(),
                PostConversationMessageCommand.fromMarkdown("Cannot write")));
  }

  @Test
  void rejectsMessagesFromLeftParticipantsAndArchivedConversations() {
    Fixture fixture = new Fixture();
    PersonalConversationInitialization base = fixture.conversation(ConversationVisibility.PRIVATE);
    Principal memberUser = fixture.activeUser("Member");
    TeamMember member = fixture.addMember(memberUser);
    ConversationParticipant left =
        ConversationParticipant.joinMember(
                base.conversation(), member, memberUser, fixture.owner, NOW)
            .leave(fixture.owner, UtcTimestamp.parse("2026-08-10T08:05:00Z"));
    fixture.store.participants.put(left.id(), left);

    assertThrows(
        PolicyDeniedException.class,
        () ->
            fixture.service.postUserMessage(
                fixture.commandContext(memberUser, "message-left-1"),
                fixture.initialization.team().id(),
                base.conversation().id(),
                PostConversationMessageCommand.fromMarkdown("Too late")));

    Conversation archived =
        base.conversation().archive(fixture.owner, UtcTimestamp.parse("2026-08-10T08:10:00Z"));
    fixture.store.conversations.put(archived.id(), archived);
    assertThrows(
        DomainValidationException.class,
        () ->
            fixture.service.postUserMessage(
                fixture.commandContext("message-archived-1"),
                fixture.initialization.team().id(),
                archived.id(),
                PostConversationMessageCommand.fromMarkdown("Too late")));

    Principal outsider = fixture.activeUser("Archived outsider");
    fixture.addMember(outsider);
    assertThrows(
        AggregateNotFoundException.class,
        () ->
            fixture.service.postUserMessage(
                fixture.commandContext(outsider, "message-hidden-archive-1"),
                fixture.initialization.team().id(),
                archived.id(),
                PostConversationMessageCommand.fromMarkdown("Must remain hidden")));
  }

  private static final class Fixture {

    private final OrganizationId organizationId = OrganizationId.generate();
    private final Principal owner =
        ConversationApplicationServiceTest.activeUser(organizationId, "Owner");
    private final TeamInitialization initialization =
        TeamInitialization.create(owner, "Conversation Team", NOW);
    private final Store store = new Store();
    private final ConversationApplicationService service;

    private Fixture() {
      store.members.add(initialization.ownerMember());
      store.principals.put(owner.id(), owner);
      store.principals.put(
          initialization.ownerPersonalAgent().agentPrincipal().id(),
          initialization.ownerPersonalAgent().agentPrincipal());
      store.profiles.put(
          initialization.ownerMember().id(),
          initialization.ownerPersonalAgent().agentProfile());
      service =
          new ConversationApplicationService(
              proxy(
                  ConversationRepository.class,
                  (method, args) ->
                      switch (method) {
                        case "create" -> {
                          Conversation value = (Conversation) args[0];
                          store.conversations.put(value.id(), value);
                          yield value;
                        }
                        case "update" -> {
                          Conversation value = (Conversation) args[0];
                          store.conversations.put(value.id(), value);
                          yield value;
                        }
                        case "findById", "lockById" ->
                            Optional.ofNullable(store.conversations.get(args[1]));
                        case "findPage" -> {
                          store.lastConversationQuery = (ConversationQuery) args[0];
                          yield store.queryPage;
                        }
                        default -> null;
                      }),
              proxy(
                  ConversationParticipantRepository.class,
                  (method, args) ->
                      switch (method) {
                        case "create", "update" -> {
                          ConversationParticipant value = (ConversationParticipant) args[0];
                          store.participants.put(value.id(), value);
                          yield value;
                        }
                        case "findById" -> Optional.ofNullable(store.participants.get(args[1]));
                        case "findByConversation" ->
                            store.participants.values().stream()
                                .filter(value -> value.conversationId().equals(args[1]))
                                .toList();
                        default -> null;
                      }),
              proxy(
                  MessageRepository.class,
                  (method, args) -> {
                    return switch (method) {
                      case "create" -> {
                        Message value = (Message) args[0];
                        store.messages.put(value.id(), value);
                        @SuppressWarnings("unchecked")
                        Optional<String> clientKey = (Optional<String>) args[1];
                        store.lastClientMessageKey = clientKey.orElse(null);
                        clientKey.ifPresent(key -> store.messagesByClientKey.put(key, value));
                        yield value;
                      }
                      case "findPage" -> {
                        store.lastMessageQuery = (MessageHistoryQuery) args[0];
                        yield new MessagePage(List.of(), Optional.empty());
                      }
                      case "findById" -> Optional.ofNullable(store.messages.get(args[1]));
                      case "findByClientMessageKey" ->
                          Optional.ofNullable(store.messagesByClientKey.get(args[2]));
                      default -> null;
                    };
                  }),
              proxy(
                  ConversationEventRepository.class,
                  (method, args) -> {
                    if ("append".equals(method)) {
                      store.conversationEventCount++;
                      return null;
                    }
                    if ("findPage".equals(method)) {
                      store.lastConversationEventQuery = (ConversationEventQuery) args[0];
                      return new ConversationEventPage(List.of(), false);
                    }
                    return null;
                  }),
              proxy(
                  TeamRepository.class,
                  (method, args) ->
                      switch (method) {
                        case "findUninitializedById" -> Optional.empty();
                        case "findById", "lockById" -> Optional.of(initialization.team());
                        default -> null;
                      }),
              proxy(
                  WorkspaceRepository.class,
                  (method, args) -> Optional.of(initialization.defaultWorkspace())),
              proxy(
                  TeamMembershipQuery.class,
                  (method, args) -> List.copyOf(store.members)),
              proxy(
                  PrincipalRepository.class,
                  (method, args) ->
                      switch (method) {
                        case "findById" -> Optional.ofNullable(store.principals.get(args[1]));
                        case "findByIds" -> {
                          @SuppressWarnings("unchecked")
                          Set<PrincipalId> ids = (Set<PrincipalId>) args[1];
                          yield ids.stream()
                              .map(store.principals::get)
                              .filter(Objects::nonNull)
                              .toList();
                        }
                        default -> null;
                      }),
              proxy(
                  AgentProfileRepository.class,
                  (method, args) ->
                      "findActiveDefaultPersonal".equals(method)
                          ? Optional.ofNullable(store.profiles.get(args[1]))
                          : Optional.empty()),
              proxy(
                  TeamRoleRepository.class,
                  (method, args) -> initialization.builtInRoles()),
              proxy(
                  MemberRoleRepository.class,
                  (method, args) -> List.of(initialization.ownerRole())),
              proxy(
                  DomainEventStore.class,
                  (method, args) -> {
                    store.events.add((DomainEventEnvelope<?>) args[0]);
                    return null;
                  }),
              proxy(
                  OutboxRepository.class,
                  (method, args) -> {
                    store.outboxCount++;
                    return null;
                  }),
              proxy(
                  CommandReceiptStore.class,
                  (method, args) -> {
                    if ("reserve".equals(method)) {
                      CommandReservationRequest request = (CommandReservationRequest) args[0];
                      CommandReservationRequest existing =
                          store.reservations.get(request.idempotencyKey().value());
                      if (existing == null) {
                        store.reservations.put(request.idempotencyKey().value(), request);
                        return CommandReservation.newlyAcquired();
                      }
                      if (!existing.requestHash().equals(request.requestHash())
                          || !existing.commandType().equals(request.commandType())) {
                        throw new IdempotencyConflictException(
                            request.idempotencyKey().value(),
                            existing.requestHash().value(),
                            request.requestHash().value());
                      }
                      return CommandReservation.replay(
                          store.receipts.get(request.idempotencyKey().value()));
                    }
                    if ("complete".equals(method)) {
                      store.receipts.put(
                          ((IdempotencyKey) args[1]).value(), (CommandReceipt) args[2]);
                    }
                    return null;
                  }),
              new DirectTransactionExecutor(),
              TimeProvider.from(
                  Clock.fixed(Instant.parse("2026-08-10T08:00:00Z"), ZoneOffset.UTC)),
              new ConversationVisibilityPolicy());
    }

    private PersonalConversationInitialization conversation(ConversationVisibility visibility) {
      PersonalConversationInitialization value =
          PersonalConversationInitialization.start(
              ConversationId.generate(),
              initialization.defaultWorkspace(),
              initialization.ownerMember(),
              owner,
              initialization.ownerPersonalAgent(),
              "Conversation",
              visibility,
              NOW);
      store.add(value);
      return value;
    }

    private TeamMember addMember(Principal user) {
      TeamMember member =
          initialization.team().joinMember(
              TeamMemberId.generate(), user, TeamJoinMethod.OIDC, NOW);
      store.members.add(member);
      store.principals.put(user.id(), user);
      return member;
    }

    private Principal activeUser(String name) {
      return ConversationApplicationServiceTest.activeUser(organizationId, name);
    }

    private TeamAccessContext access(Principal actor) {
      return new TeamAccessContext(actor, false);
    }

    private TeamCommandContext commandContext(String key) {
      return commandContext(owner, key);
    }

    private TeamCommandContext commandContext(Principal actor, String key) {
      return new TeamCommandContext(
          access(actor), new IdempotencyKey(key), UUID.randomUUID(), Optional.empty());
    }
  }

  private static final class Store {

    private final Map<ConversationId, Conversation> conversations = new LinkedHashMap<>();
    private final Map<io.crewscope.domain.conversation.ConversationParticipantId,
            ConversationParticipant>
        participants = new LinkedHashMap<>();
    private final Map<PrincipalId, Principal> principals = new LinkedHashMap<>();
    private final Map<io.crewscope.domain.conversation.MessageId, Message> messages =
        new LinkedHashMap<>();
    private final Map<String, Message> messagesByClientKey = new LinkedHashMap<>();
    private final Map<TeamMemberId, AgentProfile> profiles = new LinkedHashMap<>();
    private final Map<String, CommandReservationRequest> reservations = new LinkedHashMap<>();
    private final Map<String, CommandReceipt> receipts = new LinkedHashMap<>();
    private int conversationEventCount;
    private ConversationEventQuery lastConversationEventQuery;
    private final List<TeamMember> members = new ArrayList<>();
    private final List<DomainEventEnvelope<?>> events = new ArrayList<>();
    private ConversationPage queryPage = new ConversationPage(List.of(), Optional.empty());
    private ConversationQuery lastConversationQuery;
    private MessageHistoryQuery lastMessageQuery;
    private String lastClientMessageKey;
    private int outboxCount;

    private void add(PersonalConversationInitialization initialization) {
      conversations.put(initialization.conversation().id(), initialization.conversation());
      participants.put(initialization.ownerParticipant().id(), initialization.ownerParticipant());
      participants.put(initialization.agentParticipant().id(), initialization.agentParticipant());
    }
  }

  private static Principal activeUser(OrganizationId organizationId, String name) {
    return Principal.create(
        PrincipalId.generate(),
        PrincipalScope.organization(organizationId),
        PrincipalType.USER,
        Optional.empty(),
        name,
        Optional.empty(),
        PrincipalVisibility.ORGANIZATION,
        NOW);
  }

  private static final class DirectTransactionExecutor implements TransactionExecutor {

    @Override
    public <T> T required(Supplier<T> operation) {
      return operation.get();
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, BiFunction<String, Object[], Object> handler) {
    return (T)
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (instance, method, arguments) -> {
              if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                  case "toString" -> type.getSimpleName() + "Proxy";
                  case "hashCode" -> System.identityHashCode(instance);
                  case "equals" -> instance == arguments[0];
                  default -> null;
                };
              }
              return handler.apply(
                  method.getName(), arguments == null ? new Object[0] : arguments);
            });
  }
}
