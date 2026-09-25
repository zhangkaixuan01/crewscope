package io.crewscope.application.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.conversation.ClarificationQuestionV1;
import io.crewscope.application.conversation.ClarificationRequestV1;
import io.crewscope.application.conversation.ConversationApplicationService;
import io.crewscope.application.conversation.MessageRepository;
import io.crewscope.application.conversation.TaskIntentApplicationService;
import io.crewscope.application.team.MemberAuthorizationGuard;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.domain.conversation.AgentRuntimeSession;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationMessageAppend;
import io.crewscope.domain.conversation.Message;
import io.crewscope.domain.conversation.MessageContent;
import io.crewscope.domain.conversation.MessageId;
import io.crewscope.domain.conversation.PersonalConversationInitialization;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.runtime.RuntimeCapabilities;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.RealtimeEventEnvelope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import jakarta.validation.Validation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * M9b-A07: a revoked owner loses an in-flight Personal Agent run at the next event boundary, and
 * the cached segment replay is denied outright instead of re-serving the projection.
 */
class PersonalAgentRevocationGuardTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-10T13:00:00Z");

    @Test
    void revocationMidRunFailsTheSegmentClosedBeforeTheNextCommit() {
        Fixture fixture = Fixture.create();
        ConversationApplicationService conversations = mock(ConversationApplicationService.class);
        MessageRepository messages = mock(MessageRepository.class);
        MemberAuthorizationGuard guard = mock(MemberAuthorizationGuard.class);
        ConversationMessageAppend append = new ConversationMessageAppend(
                fixture.firstConversation, fixture.firstMessage);
        when(conversations.postUserMessage(any(), any(), any(), any()))
                .thenReturn(CommandExecution.completed(append, receipt()));
        when(conversations.commitAgentMessage(any(), any(), any(), any()))
                .thenReturn(fixture.answerMessage);
        // The first runtime event (RUN_STARTED) passes; the Interrupted boundary lands after
        // the revocation and must fail the segment closed.
        doNothing()
                .doThrow(new PolicyDeniedException("keep executing with this Team membership"))
                .when(guard)
                .requireParticipation(any(), any(), any());
        PersonalAgentInvocationService service = service(conversations, messages, guard, fixture);

        ConversationAgentSegment invoked = service.invoke(
                fixture.context("invoke-1"),
                fixture.initialization.team().id(),
                fixture.conversation.conversation().id(),
                "Need clarification");

        assertEquals(List.of("RUN_STARTED", "RUN_ERROR"), collect(invoked));
        verify(conversations, times(0)).commitAgentMessage(any(), any(), any(), any());
        // The failed segment is terminal, so configuration refresh is safe again immediately.
        assertEquals(
                "refreshed",
                org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.atSafePoint(
                        fixture.owner.scope().organizationId(),
                        fixture.initialization.team().id(),
                        fixture.conversation.conversation().id(),
                        () -> "refreshed")));
    }

    @Test
    void revocationBeforeReplayDeniesTheCachedInvokeSegment() {
        Fixture fixture = Fixture.create();
        ConversationApplicationService conversations = mock(ConversationApplicationService.class);
        MessageRepository messages = mock(MessageRepository.class);
        MemberAuthorizationGuard guard = mock(MemberAuthorizationGuard.class);
        ConversationMessageAppend append = new ConversationMessageAppend(
                fixture.firstConversation, fixture.firstMessage);
        when(conversations.postUserMessage(any(), any(), any(), any()))
                .thenReturn(CommandExecution.completed(append, receipt()));
        PersonalAgentInvocationService service = service(conversations, messages, guard, fixture);
        TeamId teamId = fixture.initialization.team().id();
        ConversationId conversationId = fixture.conversation.conversation().id();

        ConversationAgentSegment first = service.invoke(
                fixture.context("invoke-1"), teamId, conversationId, "Need clarification");
        assertEquals(List.of("RUN_STARTED", "RUN_INTERRUPTED"), collect(first));

        doThrow(new PolicyDeniedException("keep executing with this Team membership"))
                .when(guard)
                .requireParticipation(
                        eq(fixture.owner.scope().organizationId()), eq(teamId),
                        eq(fixture.owner.id()));
        assertThrows(
                PolicyDeniedException.class,
                () -> service.invoke(
                        fixture.context("invoke-1"), teamId, conversationId, "Need clarification"));
        // The runtime was not started a second time; the denial happened before the replay.
        assertEquals(1, fixture.runtime.invokeCalls.get());
    }

    @Test
    void revocationBeforeReplayDeniesTheCachedResumeSegment() {
        Fixture fixture = Fixture.create();
        ConversationApplicationService conversations = mock(ConversationApplicationService.class);
        MessageRepository messages = mock(MessageRepository.class);
        MemberAuthorizationGuard guard = mock(MemberAuthorizationGuard.class);
        ConversationMessageAppend firstAppend = new ConversationMessageAppend(
                fixture.firstConversation, fixture.firstMessage);
        ConversationMessageAppend answerAppend = new ConversationMessageAppend(
                fixture.answerConversation, fixture.answerMessage);
        when(conversations.postUserMessage(any(), any(), any(), any()))
                .thenReturn(CommandExecution.completed(firstAppend, receipt()))
                .thenReturn(CommandExecution.completed(answerAppend, receipt()));
        when(conversations.commitAgentMessage(any(), any(), any(), any()))
                .thenReturn(fixture.answerMessage);
        PersonalAgentInvocationService service = service(conversations, messages, guard, fixture);
        TeamId teamId = fixture.initialization.team().id();
        ConversationId conversationId = fixture.conversation.conversation().id();

        ConversationAgentSegment invoked = service.invoke(
                fixture.context("invoke-1"), teamId, conversationId, "Need clarification");
        ConversationAgentSegment resumed = service.resume(
                fixture.context("resume-1"),
                teamId,
                conversationId,
                invoked.invocationId(),
                "Use the production repository");
        assertEquals(
                List.of("RUN_STARTED", "TEXT_MESSAGE_CONTENT", "RUN_FINISHED"), collect(resumed));

        doThrow(new PolicyDeniedException("keep executing with this Team membership"))
                .when(guard)
                .requireParticipation(
                        eq(fixture.owner.scope().organizationId()), eq(teamId),
                        eq(fixture.owner.id()));
        assertThrows(
                PolicyDeniedException.class,
                () -> service.resume(
                        fixture.context("resume-1"),
                        teamId,
                        conversationId,
                        invoked.invocationId(),
                        "Use the production repository"));
        assertEquals(1, fixture.runtime.resumeCalls.get());
        InOrder ordered = Mockito.inOrder(guard);
        ordered.verify(guard, Mockito.atLeastOnce()).requireParticipation(any(), any(), any());
    }

    private static PersonalAgentInvocationService service(
            ConversationApplicationService conversations,
            MessageRepository messages,
            MemberAuthorizationGuard guard,
            Fixture fixture) {
        CountingRuntime runtime = fixture.runtime;
        PersonalAgentExecutionContextResolver resolver = (
                access, organizationId, teamId, conversationId, invocationId, correlationId) ->
                fixture.resolved(invocationId, correlationId);
        return new PersonalAgentInvocationService(
                conversations,
                mock(TaskIntentApplicationService.class),
                messages,
                resolver,
                runtime,
                new ConversationExecutionEventMapper(
                        Validation.buildDefaultValidatorFactory().getValidator()),
                guard,
                () -> NOW);
    }

    private static CommandReceipt receipt() {
        return new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID());
    }

    private static List<String> collect(ConversationAgentSegment segment) {
        List<String> eventTypes = new ArrayList<>();
        segment.events().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(RealtimeEventEnvelope<? extends AguiTransientPayload> item) {
                eventTypes.add(item.eventType().value());
            }

            @Override
            public void onError(Throwable throwable) {
                throw new AssertionError(throwable);
            }

            @Override
            public void onComplete() {}
        });
        return eventTypes;
    }

    private static final class CountingRuntime implements ExecutionRuntime {

        private final AtomicInteger invokeCalls = new AtomicInteger();
        private final AtomicInteger resumeCalls = new AtomicInteger();

        @Override
        public RuntimeDescriptor descriptor() {
            return new RuntimeDescriptor("test-runtime", "Test Runtime", "1.0.0");
        }

        @Override
        public RuntimeCapabilities capabilities() {
            return RuntimeCapabilities.of();
        }

        @Override
        public ExecutionHandle invokeConversation(ConversationExecutionRequest request) {
            invokeCalls.incrementAndGet();
            return Handles.handle(
                    request.invocationId(),
                    new ExecutionEventPayload.Started(ExecutionSegmentKind.INVOKE),
                    new ExecutionEventPayload.Interrupted(
                            new ExecutionInterruptToken("pending-clarification"),
                            ExecutionInterruptKind.CLARIFICATION,
                            "Which repository should I use?",
                            Optional.of(new ClarificationRequestV1(
                                    "1",
                                    "Repository selection is required",
                                    List.of(new ClarificationQuestionV1(
                                            "repository",
                                            "Which repository should I use?",
                                            null,
                                            true,
                                            List.of()))))));
        }

        @Override
        public ExecutionHandle resumeConversation(ConversationResumeRequest request) {
            resumeCalls.incrementAndGet();
            return Handles.handle(
                    request.invocationId(),
                    new ExecutionEventPayload.Started(ExecutionSegmentKind.RESUME),
                    new ExecutionEventPayload.TextDelta("Continuing safely"),
                    new ExecutionEventPayload.Completed());
        }

        @Override
        public java.util.concurrent.CompletionStage<ExecutionCancelResult> cancel(
                ConversationCancelRequest request) {
            return CompletableFuture.completedFuture(ExecutionCancelResult.ALREADY_TERMINAL);
        }
    }

    private static final class Handles {

        private static ExecutionHandle handle(
                RuntimeInvocationId invocationId, ExecutionEventPayload... payloads) {
            List<ExecutionEvent> events = new ArrayList<>();
            for (int index = 0; index < payloads.length; index++) {
                events.add(new ExecutionEvent(invocationId, index + 1, NOW, payloads[index]));
            }
            return new ExecutionHandle(invocationId, new FinitePublisher(events));
        }
    }

    private static final class FinitePublisher implements Flow.Publisher<ExecutionEvent> {

        private final List<ExecutionEvent> events;

        private FinitePublisher(List<ExecutionEvent> events) {
            this.events = events;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super ExecutionEvent> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {
                private int cursor;
                private boolean done;

                @Override
                public void request(long count) {
                    if (done || count <= 0) {
                        return;
                    }
                    long remaining = count;
                    while (!done && remaining-- > 0 && cursor < events.size()) {
                        subscriber.onNext(events.get(cursor++));
                    }
                    if (cursor == events.size() && !done) {
                        done = true;
                        subscriber.onComplete();
                    }
                }

                @Override
                public void cancel() {
                    done = true;
                }
            });
        }
    }

    private static final class Fixture {

        private final Principal owner;
        private final TeamInitialization initialization;
        private final PersonalConversationInitialization conversation;
        private final AgentRuntimeSession session;
        private final Message firstMessage;
        private final io.crewscope.domain.conversation.Conversation firstConversation;
        private final io.crewscope.domain.conversation.Conversation answerConversation;
        private final Message answerMessage;
        private final CountingRuntime runtime = new CountingRuntime();

        private Fixture(
                Principal owner,
                TeamInitialization initialization,
                PersonalConversationInitialization conversation,
                AgentRuntimeSession session,
                Message firstMessage,
                io.crewscope.domain.conversation.Conversation firstConversation,
                io.crewscope.domain.conversation.Conversation answerConversation,
                Message answerMessage) {
            this.owner = owner;
            this.initialization = initialization;
            this.conversation = conversation;
            this.session = session;
            this.firstMessage = firstMessage;
            this.firstConversation = firstConversation;
            this.answerConversation = answerConversation;
            this.answerMessage = answerMessage;
        }

        private static Fixture create() {
            OrganizationId organizationId = OrganizationId.generate();
            Principal owner = Principal.create(
                    PrincipalId.generate(),
                    PrincipalScope.organization(organizationId),
                    PrincipalType.USER,
                    Optional.empty(),
                    "Owner",
                    Optional.empty(),
                    PrincipalVisibility.ORGANIZATION,
                    NOW);
            TeamInitialization initialization = TeamInitialization.create(owner, "Team", NOW);
            PersonalConversationInitialization conversation = PersonalConversationInitialization.start(
                    ConversationId.generate(),
                    initialization.defaultWorkspace(),
                    initialization.ownerMember(),
                    owner,
                    initialization.ownerPersonalAgent(),
                    "Agent chat",
                    io.crewscope.domain.conversation.ConversationVisibility.PRIVATE,
                    NOW);
            AgentRuntimeSession session = AgentRuntimeSession.initializePersonal(
                    conversation.conversation(),
                    initialization.defaultWorkspace(),
                    initialization.ownerMember(),
                    owner,
                    initialization.ownerPersonalAgent(),
                    NOW);
            ConversationMessageAppend first = conversation.conversation().appendMessage(
                    MessageId.generate(),
                    conversation.ownerParticipant(),
                    owner,
                    new MessageContent("Need clarification"),
                    NOW);
            ConversationMessageAppend answer = first.conversation().appendMessage(
                    MessageId.generate(),
                    conversation.ownerParticipant(),
                    owner,
                    new MessageContent("Use the production repository"),
                    NOW);
            Fixture fixture = new Fixture(
                    owner,
                    initialization,
                    conversation,
                    session,
                    first.message(),
                    first.conversation(),
                    answer.conversation(),
                    answer.message());
            return fixture;
        }

        private TeamCommandContext context(String key) {
            return new TeamCommandContext(
                    new TeamAccessContext(owner, false),
                    IdempotencyKey.from(key),
                    UUID.randomUUID(),
                    Optional.empty());
        }

        private ResolvedPersonalAgentExecution resolved(
                RuntimeInvocationId invocationId, UUID correlationId) {
            PlatformExecutionContext context = new PlatformExecutionContext(
                    session.scope(),
                    io.crewscope.domain.workspace.WorkspaceType.TEAM,
                    session.ownerPrincipalId(),
                    session.ownerMemberId(),
                    Set.of(),
                    Set.of(),
                    session.personalAgentPrincipalId(),
                    session.agentProfileId(),
                    session.agentProfileVersion(),
                    session.conversationId(),
                    conversation.conversation().visibility(),
                    conversation.ownerParticipant().id(),
                    conversation.agentParticipant().id(),
                    session.id(),
                    session.agentScopeKey(),
                    invocationId,
                    correlationId,
                    Set.of(),
                    Map.of());
            return new ResolvedPersonalAgentExecution(session, context);
        }
    }
}
