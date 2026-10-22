package io.crewscope.application.conversation;

import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationParticipant;
import io.crewscope.domain.conversation.ConversationParticipantId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Persistence Port for durable Conversation participation facts. */
public interface ConversationParticipantRepository {
    ConversationParticipant create(ConversationParticipant participant);
    ConversationParticipant update(ConversationParticipant participant);
    Optional<ConversationParticipant> findById(OrganizationId organizationId, ConversationParticipantId id);
    List<ConversationParticipant> findByConversation(OrganizationId organizationId, ConversationId conversationId);

    /**
     * Batch read of one principal's participation rows across Conversations, feeding the
     * viewer-scoped unread projection without per-row queries.
     */
    List<ConversationParticipant> findByPrincipalAndConversations(
            OrganizationId organizationId,
            PrincipalId principalId,
            Collection<ConversationId> conversationIds);
}
