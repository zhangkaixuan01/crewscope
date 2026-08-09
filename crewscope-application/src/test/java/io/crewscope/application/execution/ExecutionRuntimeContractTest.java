package io.crewscope.application.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.conversation.AgentRuntimeSession;
import io.crewscope.domain.conversation.AgentRuntimeSessionId;
import io.crewscope.domain.conversation.AgentRuntimeSessionStatus;
import io.crewscope.domain.conversation.AgentRuntimeStateReference;
import io.crewscope.domain.conversation.AgentScopeSessionKey;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationParticipantId;
import io.crewscope.domain.conversation.ConversationScope;
import io.crewscope.domain.conversation.Message;
import io.crewscope.domain.conversation.MessageContent;
import io.crewscope.domain.conversation.MessageId;
import io.crewscope.domain.conversation.MessageSequence;
import io.crewscope.domain.conversation.MessageType;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Locks the framework-independent request, event stream and cancellation protocol of the Port. */
class ExecutionRuntimeContractTest {

    private static final UtcTimestamp OCCURRED_AT =
            UtcTimestamp.parse("2026-08-09T03:00:00Z");

    @Test
    void acceptsOnlyAnActiveRuntimeSession() {
        Fixture fixture = Fixture.create(AgentRuntimeSessionStatus.DISABLED);
        RuntimeInvocationId resumeInvocationId = RuntimeInvocationId.generate();
        UUID resumeCorrelationId = UUID.randomUUID();

        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.executionRequest(Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationResumeRequest(
                        resumeInvocationId,
                        fixture.session(),
                        new ExecutionInterruptToken("pending-1"),
                        UUID.randomUUID(),
                        fixture.userMessage(),
                        resumeCorrelationId,
                        fixture.context(resumeInvocationId, resumeCorrelationId)));

        // Cancellation remains available after configuration is disabled so an already-running
        // invocation can still be stopped explicitly.
        RuntimeInvocationId cancelInvocationId = RuntimeInvocationId.generate();
        UUID cancelCorrelationId = UUID.randomUUID();
        ConversationCancelRequest cancelRequest = new ConversationCancelRequest(
                cancelInvocationId,
                fixture.session(),
                "operator requested cancellation",
                cancelCorrelationId,
                fixture.context(cancelInvocationId, cancelCorrelationId));
        assertSame(fixture.session(), cancelRequest.runtimeSession());
    }

    @Test
    void closesInputMessageToTheSessionOwnerAndBoundConversation() {
        Fixture fixture = Fixture.create(AgentRuntimeSessionStatus.ACTIVE);
        ConversationScope foreignScope = new ConversationScope(
                OrganizationId.generate(), TeamId.generate(), WorkspaceId.generate());

        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.requestWith(fixture.message(
                        foreignScope,
                        fixture.session().conversationId(),
                        MessageType.USER_MESSAGE,
                        fixture.session().ownerPrincipalId())));
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.requestWith(fixture.message(
                        fixture.session().scope(),
                        ConversationId.generate(),
                        MessageType.USER_MESSAGE,
                        fixture.session().ownerPrincipalId())));
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.requestWith(fixture.message(
                        fixture.session().scope(),
                        fixture.session().conversationId(),
                        MessageType.AGENT_MESSAGE,
                        fixture.session().personalAgentPrincipalId())));
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.requestWith(fixture.message(
                        fixture.session().scope(),
                        fixture.session().conversationId(),
                        MessageType.USER_MESSAGE,
                        PrincipalId.generate())));

        assertSame(fixture.userMessage(), fixture.executionRequest(Optional.empty()).inputMessage());
    }

    @Test
    void closesStructuredOutputSchemaJavaTypeAndValue() {
        StructuredOutputSpec<TaskProjection> spec =
                new StructuredOutputSpec<>("task-intent/v1", TaskProjection.class);
        TaskProjection projection = new TaskProjection("inspect repository");
        ExecutionEventPayload.StructuredOutput<TaskProjection> payload =
                new ExecutionEventPayload.StructuredOutput<>(spec, projection);

        assertSame(projection, payload.value());
        assertEquals("task-intent/v1", payload.spec().schemaId());
        assertThrows(ClassCastException.class, () -> spec.requireValue("wrong-type"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new StructuredOutputSpec<>("unversioned", TaskProjection.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> new StructuredOutputSpec<>("task-intent/v1", Object.class));
    }

    @Test
    void propagatesDemandAndCompletesOnlyAfterOneTerminalEvent() {
        RuntimeInvocationId invocationId = RuntimeInvocationId.generate();
        DemandPublisher source = new DemandPublisher(List.of(
                event(invocationId, 1, new ExecutionEventPayload.Started(ExecutionSegmentKind.INVOKE)),
                event(invocationId, 2, new ExecutionEventPayload.TextDelta("working")),
                event(invocationId, 3, new ExecutionEventPayload.Completed())));
        RecordingSubscriber subscriber = subscribe(new ExecutionHandle(invocationId, source));

        assertTrue(subscriber.events.isEmpty());
        subscriber.subscription.request(1);
        assertEquals(1, subscriber.events.size());
        assertFalse(subscriber.completed);

        subscriber.subscription.request(2);
        assertEquals(3, subscriber.events.size());
        assertTrue(subscriber.completed);
        assertNull(subscriber.failure);
        assertEquals(List.of(1L, 2L), source.requests);
    }

    @Test
    void allowsOnlyOneSubscriberPerHandle() {
        RuntimeInvocationId invocationId = RuntimeInvocationId.generate();
        ExecutionHandle handle = new ExecutionHandle(
                invocationId,
                new DemandPublisher(List.of(
                        event(invocationId, 1, new ExecutionEventPayload.Started(ExecutionSegmentKind.INVOKE)),
                        event(invocationId, 2, new ExecutionEventPayload.Completed()))));
        RecordingSubscriber first = subscribe(handle);
        RecordingSubscriber second = subscribe(handle);

        assertNull(first.failure);
        assertInstanceOf(ExecutionProtocolException.class, second.failure);
        assertTrue(second.events.isEmpty());
    }

    @Test
    void treatsSubscriptionCancellationAsTransportOnly() {
        Fixture fixture = Fixture.create(AgentRuntimeSessionStatus.ACTIVE);
        RuntimeInvocationId invocationId = RuntimeInvocationId.generate();
        DemandPublisher source = new DemandPublisher(List.of(
                event(invocationId, 1, new ExecutionEventPayload.Started(ExecutionSegmentKind.INVOKE)),
                event(invocationId, 2, new ExecutionEventPayload.Completed())));
        RecordingRuntime runtime = new RecordingRuntime(source);
        UUID invokeCorrelationId = UUID.randomUUID();
        ConversationExecutionRequest request = new ConversationExecutionRequest(
                invocationId,
                fixture.session(),
                fixture.userMessage(),
                Optional.empty(),
                invokeCorrelationId,
                fixture.context(invocationId, invokeCorrelationId));
        RecordingSubscriber subscriber = subscribe(runtime.invokeConversation(request));

        subscriber.subscription.request(1);
        subscriber.subscription.cancel();
        subscriber.subscription.request(1);

        assertTrue(source.canceled);
        assertEquals(1, subscriber.events.size());
        assertFalse(subscriber.completed);
        assertNull(subscriber.failure);
        assertEquals(0, runtime.cancelCalls.get());

        UUID cancelCorrelationId = UUID.randomUUID();
        ConversationCancelRequest cancelRequest = new ConversationCancelRequest(
                invocationId,
                fixture.session(),
                "owner canceled the invocation",
                cancelCorrelationId,
                fixture.context(invocationId, cancelCorrelationId));
        assertEquals(
                ExecutionCancelResult.ACCEPTED,
                runtime.cancel(cancelRequest).toCompletableFuture().join());
        assertEquals(1, runtime.cancelCalls.get());
    }

    @Test
    void rejectsInvalidDemandAtTheHandleBoundary() {
        RuntimeInvocationId invocationId = RuntimeInvocationId.generate();
        DemandPublisher source = new DemandPublisher(List.of(
                event(invocationId, 1, new ExecutionEventPayload.Started(ExecutionSegmentKind.INVOKE)),
                event(invocationId, 2, new ExecutionEventPayload.Completed())));
        RecordingSubscriber subscriber = subscribe(new ExecutionHandle(invocationId, source));

        subscriber.subscription.request(0);

        assertTrue(source.canceled);
        assertInstanceOf(IllegalArgumentException.class, subscriber.failure);
    }

    @Test
    void validatesInvocationSequenceFirstEventAndUniqueTerminal() {
        RuntimeInvocationId invocationId = RuntimeInvocationId.generate();

        assertStreamFailure(
                invocationId,
                List.of(event(
                        invocationId, 1, new ExecutionEventPayload.TextDelta("missing start"))));
        assertStreamFailure(
                invocationId,
                List.of(
                        event(invocationId, 1, new ExecutionEventPayload.Started(ExecutionSegmentKind.INVOKE)),
                        event(invocationId, 3, new ExecutionEventPayload.Completed())));
        assertStreamFailure(
                invocationId,
                List.of(
                        event(invocationId, 1, new ExecutionEventPayload.Started(ExecutionSegmentKind.INVOKE)),
                        event(RuntimeInvocationId.generate(), 2, new ExecutionEventPayload.Completed())));
        assertStreamFailure(
                invocationId,
                List.of(
                        event(invocationId, 1, new ExecutionEventPayload.Started(ExecutionSegmentKind.INVOKE)),
                        event(invocationId, 2, new ExecutionEventPayload.Completed()),
                        event(invocationId, 3, new ExecutionEventPayload.TextDelta("after terminal"))));
    }

    @Test
    void rejectsSourceCompletionWithoutAProtocolTerminal() {
        RuntimeInvocationId invocationId = RuntimeInvocationId.generate();
        DemandPublisher source = new DemandPublisher(List.of(
                event(invocationId, 1, new ExecutionEventPayload.Started(ExecutionSegmentKind.INVOKE)),
                event(invocationId, 2, new ExecutionEventPayload.TextDelta("unfinished"))));
        RecordingSubscriber subscriber = subscribe(new ExecutionHandle(invocationId, source));

        subscriber.subscription.request(2);

        assertInstanceOf(ExecutionProtocolException.class, subscriber.failure);
        assertFalse(subscriber.completed);
    }

    @Test
    void representsInterruptResumeAndAllStableTerminalResults() {
        Fixture fixture = Fixture.create(AgentRuntimeSessionStatus.ACTIVE);
        RuntimeInvocationId invocationId = RuntimeInvocationId.generate();
        ExecutionInterruptToken token = new ExecutionInterruptToken("pending-tool-confirmation-1");
        UUID correlationId = UUID.randomUUID();
        ConversationResumeRequest resume = new ConversationResumeRequest(
                invocationId,
                fixture.session(),
                token,
                UUID.randomUUID(),
                fixture.userMessage(),
                correlationId,
                fixture.context(invocationId, correlationId));
        ExecutionFailure failure = new ExecutionFailure(
                ExecutionFailureCategory.MODEL_RATE_LIMITED,
                true,
                "The model is temporarily rate limited",
                Optional.of("MODEL_RATE_LIMITED"));

        assertEquals(invocationId, resume.invocationId());
        assertEquals(token, resume.interruptToken());
        assertEquals(
                ExecutionTerminalStatus.INTERRUPTED,
                new ExecutionEventPayload.Interrupted(
                                token,
                                ExecutionInterruptKind.TOOL_APPROVAL,
                                "Approve repository write")
                        .terminalStatus()
                        .orElseThrow());
        assertEquals(
                ExecutionTerminalStatus.CANCELED,
                new ExecutionEventPayload.Canceled("Canceled by owner")
                        .terminalStatus()
                        .orElseThrow());
        assertEquals(
                ExecutionTerminalStatus.FAILED,
                new ExecutionEventPayload.Failed(failure).terminalStatus().orElseThrow());
        assertTrue(failure.retryable());
        assertEquals("MODEL_RATE_LIMITED", failure.runtimeCode().orElseThrow());
        assertEquals(
                List.of(
                        ExecutionCancelResult.ACCEPTED,
                        ExecutionCancelResult.ALREADY_TERMINAL,
                        ExecutionCancelResult.NOT_FOUND),
                List.of(ExecutionCancelResult.values()));
    }

    private static RecordingSubscriber subscribe(ExecutionHandle handle) {
        RecordingSubscriber subscriber = new RecordingSubscriber();
        handle.events().subscribe(subscriber);
        return subscriber;
    }

    private static void assertStreamFailure(
            RuntimeInvocationId invocationId, List<ExecutionEvent> events) {
        RecordingSubscriber subscriber =
                subscribe(new ExecutionHandle(invocationId, new DemandPublisher(events)));
        subscriber.subscription.request(Long.MAX_VALUE);
        assertInstanceOf(ExecutionProtocolException.class, subscriber.failure);
        assertFalse(subscriber.completed);
    }

    private static ExecutionEvent event(
            RuntimeInvocationId invocationId, long sequence, ExecutionEventPayload payload) {
        return new ExecutionEvent(invocationId, sequence, OCCURRED_AT, payload);
    }

    private record TaskProjection(String summary) {}

    private record Fixture(AgentRuntimeSession session, Message userMessage) {

        private static Fixture create(AgentRuntimeSessionStatus status) {
            ConversationScope scope = new ConversationScope(
                    OrganizationId.generate(), TeamId.generate(), WorkspaceId.generate());
            ConversationId conversationId = ConversationId.generate();
            TeamMemberId ownerMemberId = TeamMemberId.generate();
            PrincipalId ownerPrincipalId = PrincipalId.generate();
            PrincipalId agentPrincipalId = PrincipalId.generate();
            AgentRuntimeSessionId sessionId = AgentRuntimeSessionId.forPersonalConversation(
                    conversationId, ownerMemberId, agentPrincipalId);
            AgentRuntimeSession session = AgentRuntimeSession.reconstitute(
                    sessionId,
                    scope,
                    conversationId,
                    ownerMemberId,
                    ownerPrincipalId,
                    agentPrincipalId,
                    AgentProfileId.generate(),
                    1,
                    AgentScopeSessionKey.forPersonalConversation(
                            scope.organizationId(),
                            ownerMemberId,
                            agentPrincipalId,
                            conversationId,
                            sessionId),
                    AgentRuntimeStateReference.forSession(sessionId),
                    status,
                    0,
                    AuditMetadata.createdBy(ownerPrincipalId, OCCURRED_AT));
            return new Fixture(
                    session,
                    message(
                            scope,
                            conversationId,
                            MessageType.USER_MESSAGE,
                            ownerPrincipalId));
        }

        private ConversationExecutionRequest executionRequest(
                Optional<StructuredOutputSpec<?>> structuredOutput) {
            RuntimeInvocationId invocationId = RuntimeInvocationId.generate();
            UUID correlationId = UUID.randomUUID();
            return new ConversationExecutionRequest(
                    invocationId,
                    session,
                    userMessage,
                    structuredOutput,
                    correlationId,
                    context(invocationId, correlationId));
        }

        private ConversationExecutionRequest requestWith(Message message) {
            RuntimeInvocationId invocationId = RuntimeInvocationId.generate();
            UUID correlationId = UUID.randomUUID();
            return new ConversationExecutionRequest(
                    invocationId,
                    session,
                    message,
                    Optional.empty(),
                    correlationId,
                    context(invocationId, correlationId));
        }

        private PlatformExecutionContext context(
                RuntimeInvocationId invocationId, UUID correlationId) {
            return new PlatformExecutionContext(
                    session.scope(),
                    io.crewscope.domain.workspace.WorkspaceType.TEAM,
                    session.ownerPrincipalId(),
                    session.ownerMemberId(),
                    java.util.Set.of(),
                    java.util.Set.of(),
                    session.personalAgentPrincipalId(),
                    session.agentProfileId(),
                    session.agentProfileVersion(),
                    session.conversationId(),
                    io.crewscope.domain.conversation.ConversationVisibility.PRIVATE,
                    ConversationParticipantId.forPrincipal(
                            session.conversationId(), session.ownerPrincipalId()),
                    ConversationParticipantId.forPrincipal(
                            session.conversationId(), session.personalAgentPrincipalId()),
                    session.id(),
                    session.agentScopeKey(),
                    invocationId,
                    correlationId,
                    java.util.Set.of(),
                    java.util.Map.of());
        }

        private static Message message(
                ConversationScope scope,
                ConversationId conversationId,
                MessageType type,
                PrincipalId author) {
            return Message.reconstitute(
                    MessageId.generate(),
                    scope,
                    conversationId,
                    MessageSequence.first(),
                    type,
                    Optional.of(ConversationParticipantId.forPrincipal(conversationId, author)),
                    Optional.of(author),
                    new MessageContent("Execute this request"),
                    AuditMetadata.createdBy(author, OCCURRED_AT));
        }
    }

    /** Deterministic finite source used to prove demand and upstream transport cancellation. */
    private static final class DemandPublisher implements Flow.Publisher<ExecutionEvent> {

        private final List<ExecutionEvent> events;
        private final List<Long> requests = new ArrayList<>();
        private boolean subscribed;
        private boolean canceled;

        private DemandPublisher(List<ExecutionEvent> events) {
            this.events = List.copyOf(events);
        }

        @Override
        public void subscribe(Flow.Subscriber<? super ExecutionEvent> subscriber) {
            if (subscribed) {
                throw new IllegalStateException("test source supports one subscriber");
            }
            subscribed = true;
            subscriber.onSubscribe(new Flow.Subscription() {
                private int index;
                private boolean completed;

                @Override
                public void request(long itemCount) {
                    if (canceled || completed) {
                        return;
                    }
                    requests.add(itemCount);
                    long remaining = itemCount;
                    while (remaining > 0 && index < events.size() && !canceled) {
                        subscriber.onNext(events.get(index++));
                        remaining--;
                    }
                    if (index == events.size() && !canceled && !completed) {
                        completed = true;
                        subscriber.onComplete();
                    }
                }

                @Override
                public void cancel() {
                    canceled = true;
                }
            });
        }
    }

    private static final class RecordingSubscriber implements Flow.Subscriber<ExecutionEvent> {

        private final List<ExecutionEvent> events = new ArrayList<>();
        private Flow.Subscription subscription;
        private Throwable failure;
        private boolean completed;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
        }

        @Override
        public void onNext(ExecutionEvent item) {
            events.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            failure = throwable;
        }

        @Override
        public void onComplete() {
            completed = true;
        }
    }

    private static final class RecordingRuntime implements ExecutionRuntime {

        private final Flow.Publisher<ExecutionEvent> source;
        private final AtomicInteger cancelCalls = new AtomicInteger();

        private RecordingRuntime(Flow.Publisher<ExecutionEvent> source) {
            this.source = source;
        }

        @Override
        public RuntimeDescriptor descriptor() {
            return new RuntimeDescriptor("contract-runtime", "Contract Runtime", "1.0.0");
        }

        @Override
        public RuntimeCapabilities capabilities() {
            return RuntimeCapabilities.of(
                    RuntimeCapability.CONVERSATION,
                    RuntimeCapability.STREAMING,
                    RuntimeCapability.CANCEL);
        }

        @Override
        public ExecutionHandle invokeConversation(ConversationExecutionRequest request) {
            return new ExecutionHandle(request.invocationId(), source);
        }

        @Override
        public ExecutionHandle resumeConversation(ConversationResumeRequest request) {
            return new ExecutionHandle(request.invocationId(), source);
        }

        @Override
        public CompletionStage<ExecutionCancelResult> cancel(ConversationCancelRequest request) {
            cancelCalls.incrementAndGet();
            return CompletableFuture.completedFuture(ExecutionCancelResult.ACCEPTED);
        }
    }
}
