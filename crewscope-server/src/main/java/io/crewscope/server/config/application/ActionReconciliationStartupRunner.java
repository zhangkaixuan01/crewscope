package io.crewscope.server.config.application;

import io.crewscope.application.action.ActionDispatchRepository;
import io.crewscope.application.action.ActionReconciliationObserver;
import io.crewscope.application.action.ActionReconciliationWorker;
import java.util.Objects;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/** Performs one bounded cold-start takeover pass before periodic polling continues recovery. */
final class ActionReconciliationStartupRunner implements ApplicationRunner {

    private final ActionReconciliationWorker worker;
    private final ActionDispatchRepository dispatches;
    private final ActionReconciliationObserver observer;

    ActionReconciliationStartupRunner(
            ActionReconciliationWorker worker,
            ActionDispatchRepository dispatches,
            ActionReconciliationObserver observer) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.dispatches = Objects.requireNonNull(dispatches, "dispatches");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    @Override
    public void run(ApplicationArguments args) {
        worker.runOnce();
        observer.queueHealth(dispatches.reconciliationHealth());
    }
}
