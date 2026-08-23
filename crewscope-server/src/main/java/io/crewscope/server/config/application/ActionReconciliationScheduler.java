package io.crewscope.server.config.application;

import io.crewscope.application.action.ActionDispatchRepository;
import io.crewscope.application.action.ActionReconciliationObserver;
import io.crewscope.application.action.ActionReconciliationWorker;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;

/** Non-overlapping UNKNOWN recovery loop backed entirely by durable Dispatch state. */
final class ActionReconciliationScheduler {

    private final ActionReconciliationWorker worker;
    private final ActionDispatchRepository dispatches;
    private final ActionReconciliationObserver observer;
    private final AtomicBoolean polling = new AtomicBoolean();

    ActionReconciliationScheduler(
            ActionReconciliationWorker worker,
            ActionDispatchRepository dispatches,
            ActionReconciliationObserver observer) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.dispatches = Objects.requireNonNull(dispatches, "dispatches");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    @Scheduled(fixedDelayString = "${crewscope.action.reconciliation.poll-interval:5s}")
    void poll() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        try {
            worker.runOnce();
            observer.queueHealth(dispatches.reconciliationHealth());
        } finally {
            polling.set(false);
        }
    }
}
