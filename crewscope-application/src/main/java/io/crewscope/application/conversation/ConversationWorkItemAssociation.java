package io.crewscope.application.conversation;

import io.crewscope.domain.conversation.Conversation;
import io.crewscope.domain.conversation.ConversationWorkItemLink;
import io.crewscope.domain.workitem.WorkItem;
import java.util.Objects;

/** Authorized counterpart snapshot for one immutable Conversation/WorkItem relation. */
public record ConversationWorkItemAssociation(
    ConversationWorkItemLink link, Conversation conversation, WorkItem workItem) {

  public ConversationWorkItemAssociation {
    link = Objects.requireNonNull(link, "link");
    conversation = Objects.requireNonNull(conversation, "conversation");
    workItem = Objects.requireNonNull(workItem, "workItem");
    if (!link.conversationId().equals(conversation.id())
        || !link.workItemId().equals(workItem.id())
        || !link.scope().equals(conversation.scope())
        || !link.scope().organizationId().equals(workItem.scope().organizationId())
        || !link.scope().teamId().equals(workItem.scope().teamId())
        || !link.scope().workspaceId().equals(workItem.scope().workspaceId())) {
      throw new IllegalArgumentException("association facts must share the linked scope");
    }
  }
}
