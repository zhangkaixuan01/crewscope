package io.crewscope.application.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.conversation.ConversationApplicationService;
import io.crewscope.application.conversation.ClarificationQuestionV1;
import io.crewscope.application.conversation.ClarificationRequestV1;
import io.crewscope.application.conversation.MessageRepository;
import io.crewscope.application.conversation.TaskIntentApplicationService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.domain.conversation.AgentRuntimeSession;
import io.crewscope.domain.runtime.RuntimeCapabilities;
import io.crewscope.domain.conversation.ConversationMessageAppend;
import io.crewscope.domain.conversation.Message;
import io.crewscope.domain.conversation.MessageContent;
import io.crewscope.domain.conversation.MessageId;
import io.crewscope.domain.conversation.MessageSequence;
import io.crewscope.domain.conversation.MessageType;
import io.crewscope.domain.conversation.PersonalConversationInitialization;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.IdempotencyConflictException;
import io.crewscope.domain.shared.event.RealtimeEventEnvelope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.workspace.WorkspaceType;
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

/** Covers invoke replay, server-held Interrupt resume and explicit cancel orchestration. */
class PersonalAgentInvocationServiceTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-10T13:00:00Z");

    @Test
    void invokesOnceResumesPendingTokenAndReplaysSegmentsAndCancel() {
        Fixture fixture = Fixture.create();
        ConversationApplicationService conversations = mock(ConversationApplicationService.class);
        MessageRepository messages = mock(MessageRepository.class);
        RecordingRuntime runtime = new RecordingRuntime();
        PersonalAgentExecutionContextResolver resolver = (
                access, organizationId, teamId, conversationId, invocationId, correlationId) ->
                fixture.resolved(invocationId, correlationId);
        ConversationMessageAppend firstAppend = new ConversationMessageAppend(
                fixture.firstConversation, fixture.firstMessage);
        ConversationMessageAppend answerAppend = new ConversationMessageAppend(
                fixture.answerConversation, fixture.answerMessage);
        CommandReceipt firstReceipt = receipt(1);
        CommandReceipt answerReceipt = receipt(2);
        when(conversations.postUserMessage(any(), any(), any(), any()))
                .thenReturn(CommandExecution.completed(firstAppend, firstReceipt))
                .thenReturn(CommandExecution.replayed(firstReceipt))
                .thenReturn(CommandExecution.completed(answerAppend, answerReceipt));
        when(messages.findByClientMessageKey(any(), any(), any()))
                .thenReturn(Optional.of(fixture.firstMessage));
        when(conversations.commitAgentMessage(any(), any(), any(), any()))
                .thenReturn(fixture.answerMessage);
        PersonalAgentInvocationService service = new PersonalAgentInvocationService(
                conversations,
                mock(TaskIntentApplicationService.class),
                messages,
                resolver,
                runtime,
                new ConversationExecutionEventMapper(
                        Validation.buildDefaultValidatorFactory().getValidator()),
                () -> NOW);

        ConversationAgentSegment invoked = service.invoke(
                fixture.context("invoke-1"),
                fixture.initialization.team().id(),
                fixture.conversation.conversation().id(),
                "Need clarification");
        ConversationAgentSegment replayedInvoke = service.invoke(
                fixture.context("invoke-1"),
                fixture.initialization.team().id(),
                fixture.conversation.conversation().id(),
                "Need clarification");
        assertFalse(invoked.replayed());
        assertTrue(replayedInvoke.replayed());
        assertEquals(invoked.invocationId(), replayedInvoke.invocationId());
        assertEquals(List.of("RUN_STARTED", "RUN_INTERRUPTED"), collect(invoked));

        ConversationAgentSegment resumed = service.resume(
                fixture.context("resume-1"),
                fixture.initialization.team().id(),
                fixture.conversation.conversation().id(),
                invoked.invocationId(),
                "Use the production repository");
        ConversationAgentSegment replayedResume = service.resume(
                fixture.context("resume-1"),
                fixture.initialization.team().id(),
                fixture.conversation.conversation().id(),
                invoked.invocationId(),
                "Use the production repository");
        assertEquals(
                List.of("RUN_STARTED", "TEXT_MESSAGE_CONTENT", "RUN_FINISHED"),
                collect(resumed));
        assertTrue(replayedResume.replayed());
        assertEquals("pending-clarification", runtime.resumedToken);
        assertThrows(
                IdempotencyConflictException.class,
                () -> service.resume(
                        fixture.context("resume-1"),
                        fixture.initialization.team().id(),
                        fixture.conversation.conversation().id(),
                        invoked.invocationId(),
                        "Use a different repository"));

        ConversationAgentCancelExecution canceled = service.cancel(
                fixture.context("cancel-1"),
                fixture.initialization.team().id(),
                fixture.conversation.conversation().id(),
                invoked.invocationId(),
                "No longer needed");
        ConversationAgentCancelExecution replayedCancel = service.cancel(
                fixture.context("cancel-1"),
                fixture.initialization.team().id(),
                fixture.conversation.conversation().id(),
                invoked.invocationId(),
                "No longer needed");
        assertEquals(
                ExecutionCancelResult.ALREADY_TERMINAL,
                canceled.result().toCompletableFuture().join());
        assertTrue(replayedCancel.replayed());
        assertEquals(1, runtime.invokeCalls.get());
        assertEquals(1, runtime.resumeCalls.get());
        assertEquals(1, runtime.cancelCalls.get());
        verify(conversations, times(1)).commitAgentMessage(any(), any(), any(), any());
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

    private static CommandReceipt receipt(long version) {
        return new CommandReceipt(
                UUID.randomUUID(), UUID.randomUUID(), version, UUID.randomUUID());
    }

    private static final class RecordingRuntime implements ExecutionRuntime {

        private final AtomicInteger invokeCalls = new AtomicInteger();
        private final AtomicInteger resumeCalls = new AtomicInteger();
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private String resumedToken;

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
            return handle(
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
            resumedToken = request.interruptToken().value();
            return handle(
                    request.invocationId(),
                    new ExecutionEventPayload.Started(ExecutionSegmentKind.RESUME),
                    new ExecutionEventPayload.TextDelta("Continuing safely"),
                    new ExecutionEventPayload.Completed());
        }

        @Override
        public java.util.concurrent.CompletionStage<ExecutionCancelResult> cancel(
                ConversationCancelRequest request) {
            cancelCalls.incrementAndGet();
            return CompletableFuture.completedFuture(ExecutionCancelResult.ALREADY_TERMINAL);
        }

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
                    io.crewscope.domain.conversation.ConversationId.generate(),
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
            return new Fixture(
                    owner,
                    initialization,
                    conversation,
                    session,
                    first.message(),
                    first.conversation(),
                    answer.conversation(),
                    answer.message());
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
                    WorkspaceType.TEAM,
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
