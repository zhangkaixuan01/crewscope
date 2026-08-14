package io.crewscope.domain.task;

/** Runtime Todo summary state, intentionally independent from StepExecutionStatus. */
public enum TodoStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED
}
