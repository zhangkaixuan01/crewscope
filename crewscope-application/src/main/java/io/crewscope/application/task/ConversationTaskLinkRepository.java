package io.crewscope.application.task;

import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.ConversationTaskLink;
import io.crewscope.domain.task.TaskId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for immutable Conversation and Task relations. */
public interface ConversationTaskLinkRepository {

    ConversationTaskLink create(ConversationTaskLink link);

    Optional<ConversationTaskLink> find(
            OrganizationId organizationId, ConversationId conversationId, TaskId taskId);

    List<ConversationTaskLink> findLinksByConversation(
            OrganizationId organizationId, ConversationId conversationId);

    List<ConversationTaskLink> findLinksByTask(
            OrganizationId organizationId, TaskId taskId);
}
