package io.crewscope.application.task;

import io.crewscope.domain.task.ClaimReceipt;
import java.util.List;
import java.util.Objects;

/** Safe batch summary; only successful receipts contain one-time Claim Token plaintext. */
public record TaskClaimBatchResult(
        List<ClaimReceipt> receipts,
        int scanned,
        int waitingRuntime,
        int capabilityDeferred,
        int quotaDeferred) {

    public TaskClaimBatchResult {
        receipts = List.copyOf(Objects.requireNonNull(receipts, "receipts"));
        if (scanned < receipts.size()
                || waitingRuntime < 0
                || capabilityDeferred < 0
                || quotaDeferred < 0
                || waitingRuntime + capabilityDeferred + quotaDeferred + receipts.size() > scanned) {
            throw new IllegalArgumentException("claim batch counters must be internally consistent");
        }
    }
}
