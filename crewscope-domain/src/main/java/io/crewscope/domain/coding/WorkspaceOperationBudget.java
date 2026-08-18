package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;

/** Aggregate file, diff, command and repair ceilings for one Coding execution. */
public record WorkspaceOperationBudget(
        int maxCommandCalls,
        int maxChangedFiles,
        long maxSingleFileBytes,
        int maxWriteOperations,
        long maxWrittenBytes,
        long maxDiffBytes,
        int maxTestRepairRounds) {

    public WorkspaceOperationBudget {
        if (maxCommandCalls < 1
                || maxChangedFiles < 1
                || maxSingleFileBytes < 1
                || maxWriteOperations < 1
                || maxWrittenBytes < 1
                || maxDiffBytes < 1
                || maxTestRepairRounds < 0) {
            throw new DomainValidationException(
                    "workspacePolicy.operationBudget",
                    "all ceilings must be positive and repair rounds must not be negative");
        }
        if (maxSingleFileBytes > maxWrittenBytes) {
            throw new DomainValidationException(
                    "workspacePolicy.operationBudget.maxSingleFileBytes",
                    "must not exceed total written bytes");
        }
    }

    public boolean isNoBroaderThan(WorkspaceOperationBudget baseline) {
        WorkspaceOperationBudget required = Objects.requireNonNull(baseline, "baseline");
        return maxCommandCalls <= required.maxCommandCalls
                && maxChangedFiles <= required.maxChangedFiles
                && maxSingleFileBytes <= required.maxSingleFileBytes
                && maxWriteOperations <= required.maxWriteOperations
                && maxWrittenBytes <= required.maxWrittenBytes
                && maxDiffBytes <= required.maxDiffBytes
                && maxTestRepairRounds <= required.maxTestRepairRounds;
    }
}
