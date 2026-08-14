package io.crewscope.domain.task;

import io.crewscope.domain.conversation.Conversation;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationScope;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Objects;

/** Immutable many-to-many relation between one Conversation and one durable Task. */
public final class ConversationTaskLink {

    private final ConversationTaskLinkId id;
    private final ConversationScope scope;
    private final ConversationId conversationId;
    private final WorkProjectId workProjectId;
    private final WorkItemId workItemId;
    private final TaskId taskId;
    private final ConversationTaskLinkOrigin origin;
    private final PrincipalId createdByPrincipalId;
    private final AuditMetadata audit;

    private ConversationTaskLink(
            ConversationTaskLinkId id,
            ConversationScope scope,
            ConversationId conversationId,
            WorkProjectId workProjectId,
            WorkItemId workItemId,
            TaskId taskId,
            ConversationTaskLinkOrigin origin,
            PrincipalId createdByPrincipalId,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        this.workProjectId = Objects.requireNonNull(workProjectId, "workProjectId");
        this.workItemId = Objects.requireNonNull(workItemId, "workItemId");
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        if (!this.id.equals(ConversationTaskLinkId.forPair(this.conversationId, this.taskId))) {
            throw new DomainValidationException(
                    "conversationTaskLink.id",
                    "must be the stable identity of the Conversation and Task pair");
        }
        this.origin = Objects.requireNonNull(origin, "origin");
        this.createdByPrincipalId = Objects.requireNonNull(
                createdByPrincipalId, "createdByPrincipalId");
        this.audit = Objects.requireNonNull(audit, "audit");
        if (this.audit.createdBy().filter(this.createdByPrincipalId::equals).isEmpty()) {
            throw new DomainValidationException(
                    "conversationTaskLink.audit.createdBy",
                    "must preserve the Principal that created the relation");
        }
    }

    /** Creates an idempotently identifiable relation after validating complete Workspace scope. */
    public static ConversationTaskLink link(
            Conversation conversation,
            Task task,
            ConversationTaskLinkOrigin origin,
            Principal creator,
            UtcTimestamp occurredAt) {
        Conversation requiredConversation = Objects.requireNonNull(conversation, "conversation");
        Task requiredTask = Objects.requireNonNull(task, "task");
        if (!requiredConversation.acceptsMessages()) {
            throw new DomainValidationException(
                    "conversationTaskLink.conversationId",
                    "must reference an active Conversation");
        }
        if (!requiredConversation.scope().organizationId().equals(requiredTask.scope().organizationId())
                || !requiredConversation.scope().teamId().equals(requiredTask.scope().teamId())
                || !requiredConversation.scope().workspaceId().equals(requiredTask.scope().workspaceId())) {
            throw new DomainValidationException(
                    "conversationTaskLink.taskId",
                    "must reference a Task in the Conversation Workspace");
        }
        ConversationTaskLinkOrigin requiredOrigin = Objects.requireNonNull(origin, "origin");
        if (requiredOrigin == ConversationTaskLinkOrigin.SOURCE
                && (requiredTask.source().type() != TaskSourceType.CONVERSATION
                        || requiredTask.source().conversationId()
                                .filter(requiredConversation.id()::equals)
                                .isEmpty())) {
            throw new DomainValidationException(
                    "conversationTaskLink.origin",
                    "SOURCE must identify the Task source Conversation");
        }
        PrincipalId creatorId = TaskActorPolicy.requireActiveInScope(
                creator, requiredTask.scope(), "conversationTaskLink.createdByPrincipalId");
        return new ConversationTaskLink(
                ConversationTaskLinkId.forPair(requiredConversation.id(), requiredTask.id()),
                requiredConversation.scope(),
                requiredConversation.id(),
                requiredTask.scope().projectId(),
                requiredTask.workItemId(),
                requiredTask.id(),
                requiredOrigin,
                creatorId,
                AuditMetadata.createdBy(creatorId, occurredAt));
    }

    /** Reconstitutes an immutable committed relation. */
    public static ConversationTaskLink reconstitute(
            ConversationTaskLinkId id,
            ConversationScope scope,
            ConversationId conversationId,
            WorkProjectId workProjectId,
            WorkItemId workItemId,
            TaskId taskId,
            ConversationTaskLinkOrigin origin,
            PrincipalId createdByPrincipalId,
            AuditMetadata audit) {
        return new ConversationTaskLink(
                id,
                scope,
                conversationId,
                workProjectId,
                workItemId,
                taskId,
                origin,
                createdByPrincipalId,
                audit);
    }

    public ConversationTaskLinkId id() {
        return id;
    }

    public ConversationScope scope() {
        return scope;
    }

    public ConversationId conversationId() {
        return conversationId;
    }

    public WorkProjectId workProjectId() {
        return workProjectId;
    }

    public WorkItemId workItemId() {
        return workItemId;
    }

    public TaskId taskId() {
        return taskId;
    }

    public ConversationTaskLinkOrigin origin() {
        return origin;
    }

    public PrincipalId createdByPrincipalId() {
        return createdByPrincipalId;
    }

    public AuditMetadata audit() {
        return audit;
    }
}
