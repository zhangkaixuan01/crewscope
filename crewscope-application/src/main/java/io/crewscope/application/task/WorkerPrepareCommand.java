package io.crewscope.application.task;

/** Expected TaskExecution version for CLAIMED to PREPARING. */
public record WorkerPrepareCommand(long expectedExecutionVersion) {
    public WorkerPrepareCommand {
        requireVersion(expectedExecutionVersion);
    }

    private static void requireVersion(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("expectedExecutionVersion must not be negative");
        }
    }
}
