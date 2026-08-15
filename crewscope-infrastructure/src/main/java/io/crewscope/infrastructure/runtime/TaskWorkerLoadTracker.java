package io.crewscope.infrastructure.runtime;

import java.util.concurrent.atomic.AtomicInteger;

/** Shared monotonic-in-operation counter used by both Worker Heartbeats and the Claim loop. */
public final class TaskWorkerLoadTracker implements RuntimeWorkerLoadProvider {

    private final AtomicInteger active = new AtomicInteger();

    public void executionStarted() {
        active.incrementAndGet();
    }

    public void executionFinished() {
        int remaining = active.decrementAndGet();
        if (remaining < 0) {
            active.incrementAndGet();
            throw new IllegalStateException("Task Worker active execution count underflow");
        }
    }

    @Override
    public int activeExecutions() {
        return active.get();
    }
}
