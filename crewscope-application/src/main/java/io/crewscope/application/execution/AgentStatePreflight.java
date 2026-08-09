package io.crewscope.application.execution;

import io.crewscope.domain.conversation.AgentScopeSessionKey;

/** Verifies that one trusted AgentScope state slot is safe to use before model execution. */
@FunctionalInterface
public interface AgentStatePreflight {

    /** Fails closed when the state backend or the active execution owner is unavailable. */
    void verifyReady(AgentScopeSessionKey sessionKey);
}
