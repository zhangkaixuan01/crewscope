package io.crewscope.domain.conversation;

/** Lifecycle of a durable Conversation-to-AgentScope state binding. */
public enum AgentRuntimeSessionStatus {
    ACTIVE,
    DISABLED,
    ARCHIVED
}
