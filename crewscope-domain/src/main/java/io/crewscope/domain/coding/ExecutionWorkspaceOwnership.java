package io.crewscope.domain.coding;

import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.ExecutionLeaseId;
import io.crewscope.domain.task.FencingToken;
import java.util.Objects;

/** Non-secret Runtime, Worker and Lease epoch that owns one Workspace generation. */
public record ExecutionWorkspaceOwnership(
        RuntimeEnvironment environment,
        ExecutionRuntimeId runtimeId,
        RuntimeWorkerId workerId,
        ExecutionLeaseId leaseId,
        FencingToken fencingToken) {

    public ExecutionWorkspaceOwnership {
        environment = Objects.requireNonNull(environment, "environment");
        runtimeId = Objects.requireNonNull(runtimeId, "runtimeId");
        workerId = Objects.requireNonNull(workerId, "workerId");
        leaseId = Objects.requireNonNull(leaseId, "leaseId");
        fencingToken = Objects.requireNonNull(fencingToken, "fencingToken");
    }

    public static ExecutionWorkspaceOwnership from(ExecutionLease lease) {
        ExecutionLease required = Objects.requireNonNull(lease, "lease");
        return new ExecutionWorkspaceOwnership(
                required.environment(),
                required.runtimeId(),
                required.workerId(),
                required.id(),
                required.fencingToken());
    }
}
