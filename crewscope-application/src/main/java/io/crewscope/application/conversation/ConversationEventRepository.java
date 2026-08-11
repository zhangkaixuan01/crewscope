package io.crewscope.application.conversation;

import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.event.DomainEventEnvelope;

/** Durable Conversation Event projection Port. */
public interface ConversationEventRepository {

  /** Appends a stream index in the caller's existing business transaction. */
  void append(
      ConversationId conversationId,
      DomainEventEnvelope<? extends DomainEvent> domainEvent);

  /** Reads the next ascending page and validates any supplied durable position. */
  ConversationEventPage findPage(ConversationEventQuery query);
}
