package io.crewscope.application.teamobserver;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;

/** Small bounded replay publisher; disconnecting a subscriber never cancels business execution. */
final class TeamObserverEventPublisher implements Flow.Publisher<TeamObserverStreamEvent> {

    private static final int MAX_SUBSCRIBERS = 16;
    private final List<TeamObserverStreamEvent> events = new ArrayList<>();
    private final List<ReplaySubscription> subscribers = new ArrayList<>();
    private boolean complete;

    @Override
    public synchronized void subscribe(Flow.Subscriber<? super TeamObserverStreamEvent> subscriber) {
        Flow.Subscriber<? super TeamObserverStreamEvent> required =
                Objects.requireNonNull(subscriber, "subscriber");
        if (subscribers.size() >= MAX_SUBSCRIBERS) {
            required.onSubscribe(RejectedSubscription.INSTANCE);
            required.onError(new IllegalStateException("Team Observer stream capacity exhausted"));
            return;
        }
        ReplaySubscription subscription = new ReplaySubscription(required);
        subscribers.add(subscription);
        required.onSubscribe(subscription);
    }

    synchronized void append(TeamObserverStreamEvent event) {
        if (complete) {
            return;
        }
        events.add(Objects.requireNonNull(event, "event"));
        drainAll();
    }

    synchronized void complete() {
        complete = true;
        drainAll();
    }

    private void drainAll() {
        List.copyOf(subscribers).forEach(ReplaySubscription::drain);
    }

    private final class ReplaySubscription implements Flow.Subscription {

        private final Flow.Subscriber<? super TeamObserverStreamEvent> subscriber;
        private long demand;
        private int cursor;
        private boolean cancelled;
        private boolean terminalSent;

        private ReplaySubscription(Flow.Subscriber<? super TeamObserverStreamEvent> subscriber) {
            this.subscriber = subscriber;
        }

        @Override
        public void request(long amount) {
            synchronized (TeamObserverEventPublisher.this) {
                if (amount <= 0) {
                    cancelled = true;
                    subscriber.onError(new IllegalArgumentException("demand must be positive"));
                    return;
                }
                demand = demand > Long.MAX_VALUE - amount ? Long.MAX_VALUE : demand + amount;
                drain();
            }
        }

        @Override
        public void cancel() {
            synchronized (TeamObserverEventPublisher.this) {
                cancelled = true;
                subscribers.remove(this);
            }
        }

        private void drain() {
            while (!cancelled && demand > 0 && cursor < events.size()) {
                TeamObserverStreamEvent event = events.get(cursor++);
                demand--;
                subscriber.onNext(event);
            }
            if (!cancelled && complete && cursor == events.size() && !terminalSent) {
                terminalSent = true;
                subscriber.onComplete();
            }
        }
    }

    private enum RejectedSubscription implements Flow.Subscription {
        INSTANCE;

        @Override
        public void request(long ignored) {}

        @Override
        public void cancel() {}
    }
}
