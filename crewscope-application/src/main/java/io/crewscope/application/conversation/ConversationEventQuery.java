package io.crewscope.application.conversation;

import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationScope;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Visibility-bounded keyset query for one Conversation Event stream. */
public record ConversationEventQuery(
    ConversationScope scope,
    ConversationId conversationId,
    Optional<UtcTimestamp> visibleThrough,
    Optional<ConversationEventCursor> cursor,
    int limit) {

  public ConversationEventQuery {
    scope = Objects.requireNonNull(scope, "scope");
    conversationId = Objects.requireNonNull(conversationId, "conversationId");
    visibleThrough = Objects.requireNonNull(visibleThrough, "visibleThrough");
    cursor = Objects.requireNonNull(cursor, "cursor");
    ConversationScope requiredScope = scope;
    ConversationId requiredConversationId = conversationId;
    cursor.ifPresent(
        value ->
            value.requireStream(
                requiredScope.organizationId(),
                requiredScope.teamId(),
                requiredConversationId));
    if (limit < 1 || limit > 100) {
      throw new DomainValidationException(
          "conversationEventQuery.limit", "must be between 1 and 100");
    }
  }
}
