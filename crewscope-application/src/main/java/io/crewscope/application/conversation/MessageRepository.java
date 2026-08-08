package io.crewscope.application.conversation;

import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.Message;
import io.crewscope.domain.conversation.MessageId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Optional;

/** Persistence Port for immutable committed Messages and client retry lookup. */
public interface MessageRepository {
    Message create(Message message, Optional<String> clientMessageKey);
    Optional<Message> findById(OrganizationId organizationId, MessageId id);
    Optional<Message> findByClientMessageKey(
            OrganizationId organizationId, ConversationId conversationId, String clientMessageKey);
    MessagePage findPage(MessageHistoryQuery query);
}
