package io.crewscope.application.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.conversation.AgentRuntimeSessionId;
import io.crewscope.domain.conversation.AgentScopeSessionKey;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationParticipantId;
import io.crewscope.domain.conversation.ConversationScope;
import io.crewscope.domain.conversation.ConversationVisibility;
import io.crewscope.domain.shared.event.RealtimeEventEnvelope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workspace.WorkspaceType;
import jakarta.validation.Validation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Verifies the M2 process-local replay, backpressure and final-message ordering boundary. */
class ReplayableExecutionSegmentTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-10T10:00:00Z");

    @Test
    void commitsAgentMessageBeforeFinishedAndReplaysWithoutResubscribingRuntime() {
        Fixture fixture = Fixture.create();
        AtomicBoolean committed = new AtomicBoolean();
        DemandPublisher runtimeEvents = fixture.completedEvents();
        ReplayableExecutionSegment segment = fixture.segment(
                runtimeEvents,
                candidate -> committed.set(true));

        RecordingSubscriber first = new RecordingSubscriber(committed);
        segment.subscribe(first);
        first.subscription.request(1);
        assertEquals(List.of("RUN_STARTED"), first.eventTypes());
        assertFalse(first.completed);
        first.subscription.cancel();

        RecordingSubscriber replay = new RecordingSubscriber(committed);
        segment.subscribe(replay);
        replay.subscription.request(Long.MAX_VALUE);

        assertEquals(
                List.of("RUN_STARTED", "TEXT_MESSAGE_CONTENT", "RUN_FINISHED"),
                replay.eventTypes());
        assertTrue(replay.finishedObservedAfterCommit);
        assertTrue(replay.completed);
        assertNull(replay.failure);
        assertEquals(1, runtimeEvents.subscriptions);
        assertFalse(runtimeEvents.canceled);
    }

    @Test
    void convertsReplyCommitFailureToSafeRunErrorWithoutFinished() {
        Fixture fixture = Fixture.create();
        ReplayableExecutionSegment segment = fixture.segment(
                fixture.completedEvents(),
                candidate -> {
                    throw new IllegalStateException("database-secret");
                });
        RecordingSubscriber subscriber = new RecordingSubscriber(new AtomicBoolean());
        segment.subscribe(subscriber);
        subscriber.subscription.request(Long.MAX_VALUE);

        assertEquals(
                List.of("RUN_STARTED", "TEXT_MESSAGE_CONTENT", "RUN_ERROR"),
                subscriber.eventTypes());
        RealtimeEventEnvelope<? extends AguiTransientPayload> error =
                subscriber.events.get(subscriber.events.size() - 1);
        assertFalse(error.toString().contains("database-secret"));
        assertTrue(subscriber.completed);
    }

    private record Fixture(
            RuntimeInvocationId invocationId,
            PlatformExecutionContext context,
            UUID segmentId) {

        private static Fixture create() {
            ConversationScope scope = new ConversationScope(
                    OrganizationId.generate(), TeamId.generate(), WorkspaceId.generate());
            ConversationId conversationId = ConversationId.generate();
            TeamMemberId memberId = TeamMemberId.generate();
            PrincipalId userId = PrincipalId.generate();
            PrincipalId agentId = PrincipalId.generate();
            AgentRuntimeSessionId sessionId = AgentRuntimeSessionId.forPersonalConversation(
                    conversationId, memberId, agentId);
            RuntimeInvocationId invocationId = RuntimeInvocationId.generate();
            PlatformExecutionContext context = new PlatformExecutionContext(
                    scope,
                    WorkspaceType.TEAM,
                    userId,
                    memberId,
                    Set.of(),
                    Set.of(),
                    agentId,
                    AgentProfileId.generate(),
                    0,
                    conversationId,
                    ConversationVisibility.PRIVATE,
                    ConversationParticipantId.forPrincipal(conversationId, userId),
                    ConversationParticipantId.forPrincipal(conversationId, agentId),
                    sessionId,
                    AgentScopeSessionKey.forPersonalConversation(
                            scope.organizationId(), memberId, agentId, conversationId, sessionId),
                    invocationId,
                    UUID.randomUUID(),
                    Set.of(),
                    Map.of());
            return new Fixture(invocationId, context, UUID.randomUUID());
        }

        private DemandPublisher completedEvents() {
            return new DemandPublisher(List.of(
                    event(1, new ExecutionEventPayload.Started(ExecutionSegmentKind.INVOKE)),
                    event(2, new ExecutionEventPayload.TextDelta("answer")),
                    event(3, new ExecutionEventPayload.Completed())));
        }

        private ReplayableExecutionSegment segment(
                DemandPublisher source,
                java.util.function.Consumer<AgentMessageCandidate> committer) {
            return new ReplayableExecutionSegment(
                    new ExecutionHandle(invocationId, source),
                    new ExecutionEventMappingContext(context, segmentId, Optional.empty()),
                    new ConversationExecutionEventMapper(
                            Validation.buildDefaultValidatorFactory().getValidator()),
                    committer,
                    candidate -> {},
                    (status, token) -> {},
                    () -> NOW);
        }

        private ExecutionEvent event(long sequence, ExecutionEventPayload payload) {
            return new ExecutionEvent(invocationId, sequence, NOW, payload);
        }
    }

    private static final class DemandPublisher implements Flow.Publisher<ExecutionEvent> {

        private final List<ExecutionEvent> events;
        private int subscriptions;
        private boolean canceled;

        private DemandPublisher(List<ExecutionEvent> events) {
            this.events = events;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super ExecutionEvent> subscriber) {
            subscriptions++;
            subscriber.onSubscribe(new Flow.Subscription() {
                private int cursor;
                private boolean complete;

                @Override
                public void request(long count) {
                    if (canceled || complete || count <= 0) {
                        return;
                    }
                    long remaining = count;
                    while (!canceled && remaining-- > 0 && cursor < events.size()) {
                        subscriber.onNext(events.get(cursor++));
                    }
                    if (!canceled && cursor == events.size() && !complete) {
                        complete = true;
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

    private static final class RecordingSubscriber
            implements Flow.Subscriber<RealtimeEventEnvelope<? extends AguiTransientPayload>> {

        private final AtomicBoolean committed;
        private final List<RealtimeEventEnvelope<? extends AguiTransientPayload>> events =
                new ArrayList<>();
        private Flow.Subscription subscription;
        private Throwable failure;
        private boolean completed;
        private boolean finishedObservedAfterCommit;

        private RecordingSubscriber(AtomicBoolean committed) {
            this.committed = committed;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
        }

        @Override
        public void onNext(RealtimeEventEnvelope<? extends AguiTransientPayload> item) {
            events.add(item);
            if ("RUN_FINISHED".equals(item.eventType().value())) {
                finishedObservedAfterCommit = committed.get();
            }
        }

        @Override
        public void onError(Throwable throwable) {
            failure = throwable;
        }

        @Override
        public void onComplete() {
            completed = true;
        }

        private List<String> eventTypes() {
            return events.stream().map(event -> event.eventType().value()).toList();
        }
    }
}
