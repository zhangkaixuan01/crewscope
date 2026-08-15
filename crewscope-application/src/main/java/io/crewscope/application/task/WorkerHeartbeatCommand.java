package io.crewscope.application.task;

/** Expected ExecutionLease version for one ownership renewal. */
public record WorkerHeartbeatCommand(long expectedLeaseVersion) {
    public WorkerHeartbeatCommand {
        if (expectedLeaseVersion < 0) {
            throw new IllegalArgumentException("expectedLeaseVersion must not be negative");
        }
    }
}
