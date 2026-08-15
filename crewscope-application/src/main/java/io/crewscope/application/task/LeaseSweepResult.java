package io.crewscope.application.task;

import io.crewscope.domain.task.ExecutionLeaseId;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.List;
import java.util.Objects;

/** Committed expired-Lease recovery batch without ownership secrets. */
public record LeaseSweepResult(List<RecoveredLease> recovered) {

    public LeaseSweepResult {
        recovered = List.copyOf(Objects.requireNonNull(recovered, "recovered"));
    }

    public record RecoveredLease(ExecutionLeaseId leaseId, TaskExecutionId taskExecutionId) {
        public RecoveredLease {
            Objects.requireNonNull(leaseId, "leaseId");
            Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        }
    }
}
