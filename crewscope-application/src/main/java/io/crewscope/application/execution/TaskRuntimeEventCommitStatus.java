package io.crewscope.application.execution;

/** Whether a runtime event produced new durable facts or matched an exact committed replay. */
public enum TaskRuntimeEventCommitStatus {
    COMMITTED,
    DUPLICATE
}
