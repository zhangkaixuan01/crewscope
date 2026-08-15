package io.crewscope.application.task;

/** Strong-version precondition for retrying the current failed attempt. */
public record RetryTaskCommand(long expectedExecutionVersion) {

    public RetryTaskCommand {
        if (expectedExecutionVersion < 0) {
            throw new IllegalArgumentException("expectedExecutionVersion must not be negative");
        }
    }
}
