package io.crewscope.application.execution;

/** Whether a durable AgentRun Resume was newly committed or exactly replayed. */
public enum AgentRunResumeStatus {
    RESUMED,
    DUPLICATE
}
