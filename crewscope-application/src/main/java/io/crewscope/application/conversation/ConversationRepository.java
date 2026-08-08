package io.crewscope.application.conversation;

import io.crewscope.domain.conversation.Conversation;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Optional;

/** Persistence Port for Conversation aggregate lifecycle and append serialization. */
public interface ConversationRepository {
    Conversation create(Conversation conversation);
    Conversation update(Conversation conversation);
    Optional<Conversation> findById(OrganizationId organizationId, ConversationId id);
    Optional<Conversation> lockById(OrganizationId organizationId, ConversationId id);
    ConversationPage findPage(ConversationQuery query);
}
