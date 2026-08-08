package io.crewscope.application.conversation;

import io.crewscope.domain.conversation.AgentRuntimeSession;
import io.crewscope.domain.conversation.AgentRuntimeSessionId;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Optional;

/** Atomic persistence Port for one active Personal Agent session per Conversation binding. */
public interface AgentRuntimeSessionRepository {

    /**
     * Persists the deterministic candidate when absent, otherwise returns the committed binding.
     * Implementations serialize by Conversation, owner member and Personal Agent and resolve all
     * concurrent initializers to one result.
     */
    AgentRuntimeSession initializeIfAbsent(AgentRuntimeSession candidate);

    default AgentRuntimeSession update(AgentRuntimeSession session) {
        throw new UnsupportedOperationException("AgentRuntimeSession update is not supported");
    }

    default Optional<AgentRuntimeSession> findById(
            OrganizationId organizationId, AgentRuntimeSessionId id) {
        throw new UnsupportedOperationException("AgentRuntimeSession lookup is not supported");
    }

    default Optional<AgentRuntimeSession> findActiveByConversation(
            OrganizationId organizationId, ConversationId conversationId) {
        throw new UnsupportedOperationException("AgentRuntimeSession lookup is not supported");
    }
}
