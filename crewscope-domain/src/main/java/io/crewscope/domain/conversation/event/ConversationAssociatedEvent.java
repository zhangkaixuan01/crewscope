package io.crewscope.domain.conversation.event;

import io.crewscope.domain.shared.DomainEvent;
import java.util.UUID;

/** Domain fact whose payload explicitly associates a non-Conversation aggregate with a Conversation. */
public interface ConversationAssociatedEvent extends DomainEvent {

  UUID conversationId();
}
