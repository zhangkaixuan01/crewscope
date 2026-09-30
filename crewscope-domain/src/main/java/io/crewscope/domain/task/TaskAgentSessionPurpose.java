package io.crewscope.domain.task;

/** Durable execution scope of a Task-side AgentRuntimeSession. */
public enum TaskAgentSessionPurpose {
    TASK,
    STEP,
    SPECIALIST,
    /** Advisory reviewer call: attempt-scoped and step-less — the review reads the delivered
     * diff of a finished attempt, it does not execute any plan step. */
    REVIEW
}
