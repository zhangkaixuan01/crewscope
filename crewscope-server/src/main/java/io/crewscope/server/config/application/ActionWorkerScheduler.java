package io.crewscope.server.config.application;

import io.crewscope.application.action.ActionWorker;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;

/** Non-overlapping wake-up loop; durable Dispatch rows remain the scheduling source of truth. */
final class ActionWorkerScheduler {

    private final ActionWorker worker;
    private final AtomicBoolean polling = new AtomicBoolean();

    ActionWorkerScheduler(ActionWorker worker) {
        this.worker = Objects.requireNonNull(worker, "worker");
    }

    @Scheduled(fixedDelayString = "${crewscope.action.worker.poll-interval:1s}")
    void poll() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        try {
            worker.runOnce();
        } finally {
            polling.set(false);
        }
    }
}
