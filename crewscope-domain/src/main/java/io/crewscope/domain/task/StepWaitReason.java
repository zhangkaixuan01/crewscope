package io.crewscope.domain.task;

/** Explicit reason behind the single WAITING Step state. */
public enum StepWaitReason {
    AGENT_INTERRUPT,
    COLLABORATION,
    REVIEW,
    HANDOFF,
    TAKEOVER,
    CONFIRMATION,
    EXTERNAL_EXECUTION,
    EVENT,
    USER_INPUT,
    MANUAL
}
