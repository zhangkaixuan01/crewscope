package io.crewscope.application.execution;

import io.crewscope.domain.conversation.AgentScopeSessionKey;

/** Explicit lifecycle operations for AgentScope state slots owned by durable runtime sessions. */
@FunctionalInterface
public interface AgentStateLifecycle {

    /** Deletes the complete external state slot after its durable Session is archived or expired. */
    void delete(AgentScopeSessionKey sessionKey);
}
