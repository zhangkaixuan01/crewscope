package io.crewscope.infrastructure.runtime;

import io.crewscope.domain.task.ClaimReceipt;
import io.crewscope.domain.task.ExecutionLeaseId;

/** Executes one already committed Claim receipt through the complete durable Worker protocol. */
public interface TaskWorkerExecutionHandler {

    /** Blocks until the claimed execution reaches a finite runtime boundary or fails safely. */
    void execute(ClaimReceipt receipt);

    /** Requests bounded shutdown work for an in-flight execution without forging business cancel. */
    default void requestStop(ExecutionLeaseId leaseId) {
        // Handlers without an interruptible runtime may rely on Lease expiry after process exit.
    }
}
