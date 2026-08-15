package io.crewscope.application.execution;

import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.AgentRunSegmentKind;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single-subscriber, demand-preserving protocol boundary for a Task AgentRun Segment. */
final class TaskExecutionEventPublisher implements Flow.Publisher<TaskExecutionEvent> {

    private static final Flow.Subscription REJECTED_SUBSCRIPTION = new Flow.Subscription() {
        @Override
        public void request(long itemCount) {}

        @Override
        public void cancel() {}
    };

    private final TaskExecutionId taskExecutionId;
    private final int attempt;
    private final AgentRunId agentRunId;
    private final long segmentSequence;
    private final AgentRunSegmentKind segmentKind;
    private final Flow.Publisher<TaskExecutionEvent> source;
    private final AtomicBoolean subscribed = new AtomicBoolean();

    TaskExecutionEventPublisher(
            TaskExecutionId taskExecutionId,
            int attempt,
            AgentRunId agentRunId,
            long segmentSequence,
            AgentRunSegmentKind segmentKind,
            Flow.Publisher<TaskExecutionEvent> source) {
        this.taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        this.attempt = attempt;
        this.agentRunId = Objects.requireNonNull(agentRunId, "agentRunId");
        this.segmentSequence = segmentSequence;
        this.segmentKind = Objects.requireNonNull(segmentKind, "segmentKind");
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override
    public void subscribe(Flow.Subscriber<? super TaskExecutionEvent> subscriber) {
        Flow.Subscriber<? super TaskExecutionEvent> required =
                Objects.requireNonNull(subscriber, "subscriber");
        if (!subscribed.compareAndSet(false, true)) {
            required.onSubscribe(REJECTED_SUBSCRIPTION);
            required.onError(new ExecutionProtocolException(
                    "a TaskExecutionHandle event stream supports only one subscriber"));
            return;
        }
        source.subscribe(new ValidatingSubscriber(
                required,
                new TaskExecutionStreamValidator(
                        taskExecutionId,
                        attempt,
                        agentRunId,
                        segmentSequence,
                        segmentKind)));
    }

    private static final class ValidatingSubscriber
            implements Flow.Subscriber<TaskExecutionEvent> {

        private final Flow.Subscriber<? super TaskExecutionEvent> downstream;
        private final TaskExecutionStreamValidator validator;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private Flow.Subscription upstream;

        private ValidatingSubscriber(
                Flow.Subscriber<? super TaskExecutionEvent> downstream,
                TaskExecutionStreamValidator validator) {
            this.downstream = downstream;
            this.validator = validator;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            Flow.Subscription required = Objects.requireNonNull(subscription, "subscription");
            if (upstream != null) {
                required.cancel();
                cancelAndFail(new ExecutionProtocolException(
                        "source Publisher sent more than one Subscription"));
                return;
            }
            upstream = required;
            downstream.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long itemCount) {
                    if (stopped.get()) {
                        return;
                    }
                    if (itemCount <= 0) {
                        cancelAndFail(new IllegalArgumentException(
                                "subscriber demand must be positive"));
                        return;
                    }
                    upstream.request(itemCount);
                }

                @Override
                public void cancel() {
                    if (stopped.compareAndSet(false, true)) {
                        // Transport cancellation never becomes a Task Pause or Cancel command.
                        upstream.cancel();
                    }
                }
            });
        }

        @Override
        public void onNext(TaskExecutionEvent event) {
            if (stopped.get()) {
                return;
            }
            try {
                validator.accept(event);
            } catch (ExecutionProtocolException failure) {
                cancelAndFail(failure);
                return;
            }
            try {
                downstream.onNext(event);
            } catch (RuntimeException abandonedTransport) {
                if (stopped.compareAndSet(false, true)) {
                    upstream.cancel();
                }
            }
        }

        @Override
        public void onError(Throwable throwable) {
            Objects.requireNonNull(throwable, "throwable");
            if (stopped.compareAndSet(false, true)) {
                downstream.onError(throwable);
            }
        }

        @Override
        public void onComplete() {
            if (stopped.get()) {
                return;
            }
            try {
                validator.complete();
                if (stopped.compareAndSet(false, true)) {
                    downstream.onComplete();
                }
            } catch (ExecutionProtocolException failure) {
                fail(failure);
            }
        }

        private void fail(Throwable failure) {
            if (stopped.compareAndSet(false, true)) {
                downstream.onError(failure);
            }
        }

        private void cancelAndFail(Throwable failure) {
            if (stopped.compareAndSet(false, true)) {
                upstream.cancel();
                downstream.onError(failure);
            }
        }
    }
}
