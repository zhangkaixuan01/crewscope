package io.crewscope.domain.conversation;

/** Business reason that created a Conversation and WorkItem relation. */
public enum ConversationWorkItemLinkOrigin {
    TASK_INTENT_CONFIRMATION,
    MANUAL,
    WORK_ITEM_DISCUSSION
}
