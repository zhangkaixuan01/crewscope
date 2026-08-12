package io.crewscope.application.execution;

import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.RealtimeEventEnvelope;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.time.TimeProvider;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

/**
 * Consumes one finite runtime stream once while replaying its safe AG-UI projection to HTTP clients.
 *
 * <p>HTTP cancellation removes only that subscriber. The runtime continues to its terminal event so
 * Agent state and the final Conversation Message do not depend on a network connection lifetime.
 */
final class ReplayableExecutionSegment
        implements Flow.Publisher<RealtimeEventEnvelope<? extends AguiTransientPayload>>,
                Flow.Subscriber<ExecutionEvent> {

    static final int DEFAULT_EVENT_LIMIT = 10_000;
    static final int DEFAULT_SUBSCRIBER_LIMIT = 32;

    private final ExecutionEventMappingContext mappingContext;
    private final ConversationExecutionEventMapper.Session mapper;
    private final Consumer<AgentMessageCandidate> messageCommitter;
    private final Consumer<TaskIntentOutputCandidate> taskIntentCommitter;
    private final SegmentTerminalListener terminalListener;
    private final TimeProvider timeProvider;
    private final int eventLimit;
    private final int subscriberLimit;
    private final List<RealtimeEventEnvelope<? extends AguiTransientPayload>> events =
            new ArrayList<>();
    private final List<SegmentSubscription> subscribers = new ArrayList<>();
    private Flow.Subscription upstream;
    private boolean subscribedUpstream;
    private boolean done;

    ReplayableExecutionSegment(
            ExecutionHandle handle,
            ExecutionEventMappingContext mappingContext,
            ConversationExecutionEventMapper eventMapper,
            Consumer<AgentMessageCandidate> messageCommitter,
            Consumer<TaskIntentOutputCandidate> taskIntentCommitter,
            SegmentTerminalListener terminalListener,
            TimeProvider timeProvider) {
        this(
                handle,
                mappingContext,
                eventMapper,
                messageCommitter,
                taskIntentCommitter,
                terminalListener,
                timeProvider,
                DEFAULT_EVENT_LIMIT,
                DEFAULT_SUBSCRIBER_LIMIT);
    }

    ReplayableExecutionSegment(
            ExecutionHandle handle,
            ExecutionEventMappingContext mappingContext,
            ConversationExecutionEventMapper eventMapper,
            Consumer<AgentMessageCandidate> messageCommitter,
            Consumer<TaskIntentOutputCandidate> taskIntentCommitter,
            SegmentTerminalListener terminalListener,
            TimeProvider timeProvider,
            int eventLimit) {
        this(
                handle,
                mappingContext,
                eventMapper,
                messageCommitter,
                taskIntentCommitter,
                terminalListener,
                timeProvider,
                eventLimit,
                DEFAULT_SUBSCRIBER_LIMIT);
    }

    ReplayableExecutionSegment(
            ExecutionHandle handle,
            ExecutionEventMappingContext mappingContext,
            ConversationExecutionEventMapper eventMapper,
            Consumer<AgentMessageCandidate> messageCommitter,
            Consumer<TaskIntentOutputCandidate> taskIntentCommitter,
            SegmentTerminalListener terminalListener,
            TimeProvider timeProvider,
            int eventLimit,
            int subscriberLimit) {
        ExecutionHandle source = Objects.requireNonNull(handle, "handle");
        this.mappingContext = Objects.requireNonNull(mappingContext, "mappingContext");
        if (!source.invocationId().equals(mappingContext.platformContext().invocationId())) {
            throw new IllegalArgumentException("ExecutionHandle must match the mapping context");
        }
        this.mapper = Objects.requireNonNull(eventMapper, "eventMapper").open(mappingContext);
        this.messageCommitter = Objects.requireNonNull(messageCommitter, "messageCommitter");
        this.taskIntentCommitter =
                Objects.requireNonNull(taskIntentCommitter, "taskIntentCommitter");
        this.terminalListener = Objects.requireNonNull(terminalListener, "terminalListener");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        if (eventLimit < 1) {
            throw new IllegalArgumentException("eventLimit must be positive");
        }
        this.eventLimit = eventLimit;
        if (subscriberLimit < 1 || subscriberLimit > 1_000) {
            throw new IllegalArgumentException("subscriberLimit must be between 1 and 1000");
        }
        this.subscriberLimit = subscriberLimit;
        // Keep the single-subscriber runtime publisher private; this object is its sole consumer.
        source.events().subscribe(this);
    }

    @Override
    public synchronized void subscribe(
            Flow.Subscriber<? super RealtimeEventEnvelope<? extends AguiTransientPayload>>
                    subscriber) {
        Flow.Subscriber<? super RealtimeEventEnvelope<? extends AguiTransientPayload>> required =
                Objects.requireNonNull(subscriber, "subscriber");
        if (subscribers.size() >= subscriberLimit) {
            required.onSubscribe(RejectedSubscription.INSTANCE);
            required.onError(new IllegalStateException("AG-UI subscriber capacity was exhausted"));
            return;
        }
        SegmentSubscription subscription = new SegmentSubscription(required);
        subscribers.add(subscription);
        required.onSubscribe(subscription);
        subscription.drain();
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        Objects.requireNonNull(subscription, "subscription");
        synchronized (this) {
            if (subscribedUpstream) {
                subscription.cancel();
                return;
            }
            subscribedUpstream = true;
            upstream = subscription;
        }
        subscription.request(1);
    }

    @Override
    public void onNext(ExecutionEvent event) {
        Flow.Subscription requestNext;
        synchronized (this) {
            if (done) {
                return;
            }
            try {
                ExecutionEventMappingResult mapped = mapper.accept(event);
                // A completed reply becomes a durable business fact before RUN_FINISHED is visible.
                mapped.messageCandidate().ifPresent(messageCommitter);
                mapped.taskIntentCandidate().ifPresent(taskIntentCommitter);
                mapped.transientEvent().ifPresent(this::append);
                if (event.payload() instanceof ExecutionEventPayload.Interrupted interrupted) {
                    terminalListener.terminal(
                            ExecutionTerminalStatus.INTERRUPTED, Optional.of(interrupted.token()));
                } else {
                    event.payload()
                            .terminalStatus()
                            .ifPresent(status -> terminalListener.terminal(status, Optional.empty()));
                }
                drainAll();
                requestNext = upstream;
            } catch (RuntimeException failure) {
                failSafely();
                requestNext = null;
            }
        }
        if (requestNext != null) {
            requestNext.request(1);
        }
    }

    @Override
    public synchronized void onError(Throwable throwable) {
        Objects.requireNonNull(throwable, "throwable");
        if (!done) {
            failSafely();
        }
    }

    @Override
    public synchronized void onComplete() {
        if (done) {
            return;
        }
        try {
            mapper.complete();
            done = true;
            drainAll();
        } catch (RuntimeException failure) {
            failSafely();
        }
    }

    private void append(RealtimeEventEnvelope<? extends AguiTransientPayload> event) {
        // Keep one slot available for a stable terminal signal. This avoids retracting an event
        // already observed by an active subscriber when the replay budget is exhausted.
        int limit = isTerminal(event) ? eventLimit : eventLimit - 1;
        if (events.size() >= limit) {
            throw new ExecutionProtocolException("AG-UI segment exceeds the replay limit");
        }
        events.add(event);
    }

    private static boolean isTerminal(
            RealtimeEventEnvelope<? extends AguiTransientPayload> event) {
        return event.payload() instanceof AguiTransientPayload.RunFinished
                || event.payload() instanceof AguiTransientPayload.RunInterrupted
                || event.payload() instanceof AguiTransientPayload.RunError;
    }

    private void failSafely() {
        if (done) {
            return;
        }
        done = true;
        if (upstream != null) {
            upstream.cancel();
        }
        terminalListener.terminal(ExecutionTerminalStatus.FAILED, Optional.empty());
        PlatformExecutionContext platform = mappingContext.platformContext();
        UUID eventId = UUID.nameUUIDFromBytes(
                ("crewscope:agui:application-error:v1:" + mappingContext.segmentId())
                        .getBytes(StandardCharsets.UTF_8));
        append(RealtimeEventEnvelope.transientAgUi(
                eventId,
                EventType.from("RUN_ERROR"),
                SchemaVersion.V1,
                platform.correlationId(),
                mappingContext.causationDomainEventId(),
                timeProvider.now(),
                new AguiTransientPayload.RunError(
                        platform.conversationId().toString(),
                        platform.invocationId().toString(),
                        mappingContext.segmentId(),
                        "The Personal Agent response could not be completed",
                        Optional.of("APPLICATION_STREAM_FAILED"),
                        true)));
        drainAll();
    }

    private void drainAll() {
        // Copying permits a subscriber to cancel itself reentrantly from onNext.
        for (SegmentSubscription subscriber : List.copyOf(subscribers)) {
            subscriber.drain();
        }
    }

    @FunctionalInterface
    interface SegmentTerminalListener {
        void terminal(
                ExecutionTerminalStatus status, Optional<ExecutionInterruptToken> interruptToken);
    }

    private enum RejectedSubscription implements Flow.Subscription {
        INSTANCE;

        @Override
        public void request(long requested) {}

        @Override
        public void cancel() {}
    }

    private final class SegmentSubscription implements Flow.Subscription {

        private final Flow.Subscriber<
                        ? super RealtimeEventEnvelope<? extends AguiTransientPayload>>
                downstream;
        private long demand;
        private int cursor;
        private boolean canceled;
        private boolean completed;

        private SegmentSubscription(
                Flow.Subscriber<
                                ? super RealtimeEventEnvelope<? extends AguiTransientPayload>>
                        downstream) {
            this.downstream = downstream;
        }

        @Override
        public void request(long requested) {
            synchronized (ReplayableExecutionSegment.this) {
                if (canceled || completed) {
                    return;
                }
                if (requested <= 0) {
                    canceled = true;
                    subscribers.remove(this);
                    downstream.onError(
                            new IllegalArgumentException("reactive demand must be positive"));
                    return;
                }
                demand = addCap(demand, requested);
                drain();
            }
        }

        @Override
        public void cancel() {
            synchronized (ReplayableExecutionSegment.this) {
                canceled = true;
                subscribers.remove(this);
            }
        }

        private void drain() {
            while (!canceled && demand > 0 && cursor < events.size()) {
                RealtimeEventEnvelope<? extends AguiTransientPayload> event = events.get(cursor++);
                demand--;
                downstream.onNext(event);
            }
            if (!canceled && !completed && done && cursor == events.size()) {
                completed = true;
                subscribers.remove(this);
                downstream.onComplete();
            }
        }

        private static long addCap(long current, long requested) {
            long sum = current + requested;
            return sum < 0 ? Long.MAX_VALUE : sum;
        }
    }
}
