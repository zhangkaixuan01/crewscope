package io.crewscope.application.execution;

import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Enforces the single-subscriber and event-protocol guarantees of an ExecutionHandle while
 * preserving downstream demand and transport cancellation at the source Publisher.
 */
final class ExecutionEventPublisher implements Flow.Publisher<ExecutionEvent> {

    private static final Flow.Subscription REJECTED_SUBSCRIPTION = new Flow.Subscription() {
        @Override
        public void request(long itemCount) {}

        @Override
        public void cancel() {}
    };

    private final RuntimeInvocationId invocationId;
    private final Flow.Publisher<ExecutionEvent> source;
    private final AtomicBoolean subscribed = new AtomicBoolean();

    ExecutionEventPublisher(
            RuntimeInvocationId invocationId, Flow.Publisher<ExecutionEvent> source) {
        this.invocationId = Objects.requireNonNull(invocationId, "invocationId");
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override
    public void subscribe(Flow.Subscriber<? super ExecutionEvent> subscriber) {
        Flow.Subscriber<? super ExecutionEvent> required =
                Objects.requireNonNull(subscriber, "subscriber");
        if (!subscribed.compareAndSet(false, true)) {
            required.onSubscribe(REJECTED_SUBSCRIPTION);
            required.onError(new ExecutionProtocolException(
                    "an ExecutionHandle event stream supports only one subscriber"));
            return;
        }
        source.subscribe(new ValidatingSubscriber(required, invocationId));
    }

    private static final class ValidatingSubscriber implements Flow.Subscriber<ExecutionEvent> {

        private final Flow.Subscriber<? super ExecutionEvent> downstream;
        private final ExecutionStreamValidator validator;
        private Flow.Subscription upstream;
        private final AtomicBoolean stopped = new AtomicBoolean();

        private ValidatingSubscriber(
                Flow.Subscriber<? super ExecutionEvent> downstream,
                RuntimeInvocationId invocationId) {
            this.downstream = downstream;
            this.validator = new ExecutionStreamValidator(invocationId);
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
                        cancelAndFail(
                                new IllegalArgumentException("subscriber demand must be positive"));
                        return;
                    }
                    upstream.request(itemCount);
                }

                @Override
                public void cancel() {
                    if (stopped.compareAndSet(false, true)) {
                        // This is transport cancellation only. Business cancellation remains an
                        // explicit ExecutionRuntime.cancel request.
                        upstream.cancel();
                    }
                }
            });
        }

        @Override
        public void onNext(ExecutionEvent event) {
            if (stopped.get()) {
                return;
            }
            try {
                validator.accept(event);
            } catch (ExecutionProtocolException exception) {
                cancelAndFail(exception);
                return;
            }
            try {
                downstream.onNext(event);
            } catch (RuntimeException downstreamFailure) {
                // A throwing Subscriber has abandoned the transport. Its exception must not be
                // converted into a runtime business failure or sent back into that Subscriber.
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
            } catch (ExecutionProtocolException exception) {
                fail(exception);
            }
        }

        private void fail(Throwable failure) {
            if (stopped.compareAndSet(false, true)) {
                downstream.onError(failure);
            }
        }

        private void cancelAndFail(Throwable failure) {
            if (stopped.compareAndSet(false, true)) {
                // Mark stopped before canceling because a broken source may signal synchronously
                // from cancel(); such a signal must not mask the original protocol failure.
                upstream.cancel();
                downstream.onError(failure);
            }
        }
    }
}
