package io.crewscope.infrastructure.runtime;

/** Repairs expired ownership and orphan runtime facts before a restarted Worker may claim work. */
@FunctionalInterface
public interface TaskWorkerStartupReconciler {

    /** Returns the number of TaskExecution attempts moved back to a claimable state. */
    int reconcile();
}
