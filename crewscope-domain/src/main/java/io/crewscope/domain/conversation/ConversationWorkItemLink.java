package io.crewscope.domain.conversation;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Objects;

/** Immutable relation connecting one Conversation to one WorkItem in the same Workspace. */
public final class ConversationWorkItemLink {

    private final ConversationWorkItemLinkId id;
    private final ConversationScope scope;
    private final ConversationId conversationId;
    private final WorkProjectId workProjectId;
    private final WorkItemId workItemId;
    private final ConversationWorkItemLinkOrigin origin;
    private final PrincipalId createdByPrincipalId;
    private final AuditMetadata audit;

    private ConversationWorkItemLink(
            ConversationWorkItemLinkId id,
            ConversationScope scope,
            ConversationId conversationId,
            WorkProjectId workProjectId,
            WorkItemId workItemId,
            ConversationWorkItemLinkOrigin origin,
            PrincipalId createdByPrincipalId,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        this.workProjectId = Objects.requireNonNull(workProjectId, "workProjectId");
        this.workItemId = Objects.requireNonNull(workItemId, "workItemId");
        requireStableId(this.id, this.conversationId, this.workItemId);
        this.origin = Objects.requireNonNull(origin, "origin");
        this.createdByPrincipalId = Objects.requireNonNull(
                createdByPrincipalId, "createdByPrincipalId");
        this.audit = Objects.requireNonNull(audit, "audit");
        if (this.audit.createdBy().filter(this.createdByPrincipalId::equals).isEmpty()) {
            throw new DomainValidationException(
                    "conversationWorkItemLink.audit.createdBy",
                    "must preserve the Principal that created the relation");
        }
    }

    /** Links active facts and derives a stable pair identity for idempotent command retries. */
    public static ConversationWorkItemLink link(
            Conversation conversation,
            WorkItem workItem,
            ConversationWorkItemLinkOrigin origin,
            Principal creator,
            UtcTimestamp occurredAt) {
        Conversation requiredConversation = Objects.requireNonNull(conversation, "conversation");
        WorkItem requiredWorkItem = Objects.requireNonNull(workItem, "workItem");
        if (!requiredConversation.acceptsMessages()) {
            throw new DomainValidationException(
                    "conversationWorkItemLink.conversationId",
                    "must reference an active Conversation");
        }
        if (!requiredWorkItem.acceptsCollaboration()) {
            throw new DomainValidationException(
                    "conversationWorkItemLink.workItemId",
                    "must reference a WorkItem that accepts collaboration");
        }
        requireSameScope(requiredConversation.scope(), requiredWorkItem);
        PrincipalId creatorId = ConversationActorPolicy.requireActiveInScope(
                creator,
                requiredConversation.scope(),
                "conversationWorkItemLink.createdByPrincipalId");
        return new ConversationWorkItemLink(
                ConversationWorkItemLinkId.forPair(
                        requiredConversation.id(), requiredWorkItem.id()),
                requiredConversation.scope(),
                requiredConversation.id(),
                requiredWorkItem.scope().projectId(),
                requiredWorkItem.id(),
                origin,
                creatorId,
                AuditMetadata.createdBy(creatorId, occurredAt));
    }

    /** Reconstitutes an immutable committed relation. */
    public static ConversationWorkItemLink reconstitute(
            ConversationWorkItemLinkId id,
            ConversationScope scope,
            ConversationId conversationId,
            WorkProjectId workProjectId,
            WorkItemId workItemId,
            ConversationWorkItemLinkOrigin origin,
            PrincipalId createdByPrincipalId,
            AuditMetadata audit) {
        return new ConversationWorkItemLink(
                id,
                scope,
                conversationId,
                workProjectId,
                workItemId,
                origin,
                createdByPrincipalId,
                audit);
    }

    public ConversationWorkItemLinkId id() {
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

    public ConversationWorkItemLinkOrigin origin() {
        return origin;
    }

    public PrincipalId createdByPrincipalId() {
        return createdByPrincipalId;
    }

    public AuditMetadata audit() {
        return audit;
    }

    private static void requireSameScope(ConversationScope scope, WorkItem workItem) {
        if (!scope.organizationId().equals(workItem.scope().organizationId())
                || !scope.teamId().equals(workItem.scope().teamId())
                || !scope.workspaceId().equals(workItem.scope().workspaceId())) {
            throw new DomainValidationException(
                    "conversationWorkItemLink.workItemId",
                    "must reference a WorkItem in the Conversation Workspace");
        }
    }

    private static void requireStableId(
            ConversationWorkItemLinkId id,
            ConversationId conversationId,
            WorkItemId workItemId) {
        if (!id.equals(ConversationWorkItemLinkId.forPair(conversationId, workItemId))) {
            throw new DomainValidationException(
                    "conversationWorkItemLink.id",
                    "must be the stable identity of the Conversation and WorkItem pair");
        }
    }
}
