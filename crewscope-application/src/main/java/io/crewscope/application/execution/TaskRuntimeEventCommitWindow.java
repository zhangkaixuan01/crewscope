package io.crewscope.application.execution;

import java.util.Objects;
import java.util.Optional;

/** Row-locked replay decision for one AgentRun Segment sequence. */
public record TaskRuntimeEventCommitWindow(
        long nextSequence, Optional<TaskRuntimeEventReceipt> existingReceipt) {

    public TaskRuntimeEventCommitWindow {
        if (nextSequence < 1) {
            throw new IllegalArgumentException("nextSequence must be positive");
        }
        existingReceipt = Objects.requireNonNull(existingReceipt, "existingReceipt");
    }
}
