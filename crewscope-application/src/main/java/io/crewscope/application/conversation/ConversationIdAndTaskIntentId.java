package io.crewscope.application.conversation;

import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.TaskIntentId;
import java.util.Objects;

/** Explicit nested-resource identity shared by TaskIntent application commands. */
public record ConversationIdAndTaskIntentId(
    ConversationId conversationId, TaskIntentId taskIntentId) {

  public ConversationIdAndTaskIntentId {
    conversationId = Objects.requireNonNull(conversationId, "conversationId");
    taskIntentId = Objects.requireNonNull(taskIntentId, "taskIntentId");
  }
}
