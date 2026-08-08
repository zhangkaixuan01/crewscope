package io.crewscope.application.conversation;

import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationWorkItemLink;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.workitem.WorkItemId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for immutable Conversation and WorkItem relations. */
public interface ConversationWorkItemLinkRepository {
    ConversationWorkItemLink create(ConversationWorkItemLink link);
    Optional<ConversationWorkItemLink> find(
            OrganizationId organizationId, ConversationId conversationId, WorkItemId workItemId);
    List<ConversationWorkItemLink> findLinksByConversation(
            OrganizationId organizationId, ConversationId conversationId);
    List<ConversationWorkItemLink> findLinksByWorkItem(
            OrganizationId organizationId, WorkItemId workItemId);
}
