package io.crewscope.domain.workitem;

public enum WorkItemStatus {
    BACKLOG,
    READY,
    IN_PROGRESS,
    IN_REVIEW,
    BLOCKED,
    DONE,
    CANCELLED,
    ARCHIVED;

    /**
     * DONE, CANCELLED and ARCHIVED end a WorkItem's execution: the WorkGraph "currently
     * blocked" aggregation (ADR-033) treats a BLOCKS edge from a terminal src as no longer
     * blocking. This method is the single authority the derived queries bind into SQL, so
     * the terminal set can never drift between the domain and the projections.
     */
    public boolean isTerminal() {
        return this == DONE || this == CANCELLED || this == ARCHIVED;
    }
}
