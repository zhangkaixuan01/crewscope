package io.crewscope.application.coding;

/** Stable failure raised when a durable write reservation would exceed WorkspacePolicy. */
public final class WorkspaceWriteBudgetExceededException extends RuntimeException {

    public WorkspaceWriteBudgetExceededException() {
        super("Workspace write reservation exceeds its immutable operation budget");
    }
}
