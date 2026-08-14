package io.crewscope.domain.task;

import io.crewscope.domain.conversation.Conversation;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.Message;
import io.crewscope.domain.conversation.TaskIntent;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Objects;
import java.util.Optional;

/** Single immutable origin of a Task, including the exact WorkItem and Conversation input versions. */
public record TaskSource(
        TaskSourceType type,
        WorkItemScope scope,
        WorkItemId workItemId,
        long workItemVersion,
        Optional<ConversationId> conversationId,
        Optional<TaskInputReference> inputReference) {

    public TaskSource {
        type = Objects.requireNonNull(type, "type");
        scope = Objects.requireNonNull(scope, "scope");
        workItemId = Objects.requireNonNull(workItemId, "workItemId");
        if (workItemVersion < 0) {
            throw new DomainValidationException(
                    "taskSource.workItemVersion", "must not be negative");
        }
        conversationId = Objects.requireNonNull(conversationId, "conversationId");
        inputReference = Objects.requireNonNull(inputReference, "inputReference");
        boolean conversationSource = type == TaskSourceType.CONVERSATION;
        if (conversationSource != conversationId.isPresent()
                || conversationSource != inputReference.isPresent()) {
            throw new DomainValidationException(
                    "taskSource.conversationId",
                    conversationSource
                            ? "and inputReference are required for a Conversation source"
                            : "and inputReference must be empty for a WorkItem source");
        }
    }

    /** Captures one WorkItem as the complete primary input. */
    public static TaskSource fromWorkItem(WorkItem workItem) {
        WorkItem required = requireWorkItem(workItem);
        return new TaskSource(
                TaskSourceType.WORK_ITEM,
                required.scope(),
                required.id(),
                required.version(),
                Optional.empty(),
                Optional.empty());
    }

    /** Captures one committed Message as the reproducible Conversation input. */
    public static TaskSource fromMessage(
            WorkItem workItem, Conversation conversation, Message message) {
        WorkItem requiredWorkItem = requireWorkItem(workItem);
        Conversation requiredConversation = requireConversation(requiredWorkItem, conversation);
        Message requiredMessage = Objects.requireNonNull(message, "message");
        if (!requiredMessage.scope().equals(requiredConversation.scope())
                || !requiredMessage.conversationId().equals(requiredConversation.id())) {
            throw new DomainValidationException(
                    "taskSource.inputReference", "must belong to the source Conversation");
        }
        return conversationSource(
                requiredWorkItem,
                requiredConversation,
                TaskInputReference.from(requiredMessage));
    }

    /** Captures the confirmed TaskIntent revision that created the executable work request. */
    public static TaskSource fromTaskIntent(
            WorkItem workItem, Conversation conversation, TaskIntent taskIntent) {
        WorkItem requiredWorkItem = requireWorkItem(workItem);
        Conversation requiredConversation = requireConversation(requiredWorkItem, conversation);
        TaskIntent requiredIntent = Objects.requireNonNull(taskIntent, "taskIntent");
        if (!requiredIntent.scope().equals(requiredConversation.scope())
                || !requiredIntent.conversationId().equals(requiredConversation.id())
                || !requiredIntent.proposal().targetScope().equals(requiredWorkItem.scope())) {
            throw new DomainValidationException(
                    "taskSource.inputReference",
                    "must belong to the source Conversation and target WorkItem scope");
        }
        return conversationSource(
                requiredWorkItem,
                requiredConversation,
                TaskInputReference.from(requiredIntent));
    }

    /** Ensures a reconstructed source still identifies the owning aggregate facts. */
    public void validateFor(WorkItemId expectedWorkItemId, WorkItemScope expectedScope) {
        if (!scope.equals(Objects.requireNonNull(expectedScope, "expectedScope"))) {
            throw new DomainValidationException(
                    "task.source.scope", "must match the Task scope");
        }
        if (!workItemId.equals(Objects.requireNonNull(expectedWorkItemId, "expectedWorkItemId"))) {
            throw new DomainValidationException(
                    "task.source.workItemId", "must match the Task WorkItem");
        }
    }

    private static TaskSource conversationSource(
            WorkItem workItem,
            Conversation conversation,
            TaskInputReference inputReference) {
        return new TaskSource(
                TaskSourceType.CONVERSATION,
                workItem.scope(),
                workItem.id(),
                workItem.version(),
                Optional.of(conversation.id()),
                Optional.of(inputReference));
    }

    private static WorkItem requireWorkItem(WorkItem workItem) {
        WorkItem required = Objects.requireNonNull(workItem, "workItem");
        if (!required.acceptsCollaboration()) {
            throw new DomainValidationException(
                    "taskSource.workItemId", "must reference a WorkItem that accepts execution");
        }
        return required;
    }

    private static Conversation requireConversation(
            WorkItem workItem, Conversation conversation) {
        Conversation required = Objects.requireNonNull(conversation, "conversation");
        if (!required.acceptsMessages()
                || !required.scope().organizationId().equals(workItem.scope().organizationId())
                || !required.scope().teamId().equals(workItem.scope().teamId())
                || !required.scope().workspaceId().equals(workItem.scope().workspaceId())) {
            throw new DomainValidationException(
                    "taskSource.conversationId",
                    "must reference an active Conversation in the WorkItem Workspace");
        }
        return required;
    }
}
