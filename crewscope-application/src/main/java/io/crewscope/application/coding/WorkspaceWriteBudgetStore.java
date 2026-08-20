package io.crewscope.application.coding;

import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.WorkspacePolicy;
import java.util.Set;

/**
 * Persists write reservations before filesystem effects so another Worker restores exact usage.
 */
public interface WorkspaceWriteBudgetStore {

    /** Creates or conservatively reconciles the durable counter with current Git lower bounds. */
    WorkspaceWriteBudgetSnapshot initialize(
            ExecutionWorkspace workspace,
            WorkspacePolicy policy,
            Set<String> changedPathsLowerBound,
            long writtenBytesLowerBound);

    /** Atomically reserves one mutation; failed mutations intentionally do not refund usage. */
    WorkspaceWriteBudgetSnapshot reserve(
            ExecutionWorkspace workspace,
            WorkspacePolicy policy,
            Set<String> changedPaths,
            long writtenBytes);
}
