package io.crewscope.domain.task;

/** Explicit reason behind the single WAITING execution status. */
public enum TaskExecutionWaitReason {
    RUNTIME,
    COLLABORATION,
    REVIEW,
    CONFIRMATION,
    USER_INPUT,
    EXTERNAL_EXECUTION,
    EVENT,
    MANUAL
}
