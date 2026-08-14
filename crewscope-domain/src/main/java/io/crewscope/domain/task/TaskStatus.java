package io.crewscope.domain.task;

/** Business lifecycle of a durable Task; execution-attempt state belongs to TaskExecution. */
public enum TaskStatus {
    CREATED,
    ACTIVE,
    WAITING,
    COMPLETED,
    FAILED,
    CANCELLED
}
