package io.crewscope.application.task;

import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.TaskExecution;

/** Trusted Worker command boundary for Lease renewal and fenced TaskExecution writes. */
public interface TaskExecutionLeaseCoordinator {

    TaskExecution beginPreparing(LeaseExecutionCommand command);

    LeaseMutationResult beginRun(LeaseTransitionCommand command);

    ExecutionLease heartbeat(LeaseHeartbeatCommand command);

    TaskExecution updateOwned(
            LeaseExecutionCommand command, OwnedTaskExecutionMutation mutation);

    LeaseMutationResult release(LeaseReleaseCommand command);
}
