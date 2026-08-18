package io.crewscope.domain.coding;

/** Bounded Agent working status captured for recovery, not a StepExecution verdict. */
public enum CodingTodoStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    BLOCKED
}
