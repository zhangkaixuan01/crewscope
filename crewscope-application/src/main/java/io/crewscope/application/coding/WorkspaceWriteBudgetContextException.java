package io.crewscope.application.coding;

/** Stable rejection for absent, stale or cross-policy Workspace write authority. */
public final class WorkspaceWriteBudgetContextException extends RuntimeException {

    public WorkspaceWriteBudgetContextException(String message) {
        super(message);
    }
}
