package io.crewscope.application.task;

import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Fully scoped query for Tasks rooted in a WorkItem or linked to a Conversation. */
public record TaskAssociationQuery(
        OrganizationId organizationId,
        TeamId teamId,
        WorkspaceId workspaceId,
        Optional<WorkProjectId> projectId,
        Optional<WorkItemId> workItemId,
        Optional<ConversationId> conversationId,
        Optional<TaskAssociationCursor> cursor,
        int limit) {

    public TaskAssociationQuery {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        projectId = Objects.requireNonNull(projectId, "projectId");
        workItemId = Objects.requireNonNull(workItemId, "workItemId");
        conversationId = Objects.requireNonNull(conversationId, "conversationId");
        cursor = Objects.requireNonNull(cursor, "cursor");
        if (workItemId.isPresent() == conversationId.isPresent()) {
            throw new IllegalArgumentException(
                    "exactly one association source must be supplied");
        }
        if (projectId.isPresent() != workItemId.isPresent()) {
            throw new IllegalArgumentException(
                    "only a WorkItem association source has one fixed WorkProject");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        TaskAssociationSourceType sourceType = sourceType(workItemId);
        UUID sourceId = sourceId(workItemId, conversationId);
        if (cursor.isPresent()) {
            cursor.orElseThrow().requireSource(
                    organizationId, teamId, sourceType, sourceId);
        }
    }

    public static TaskAssociationQuery byWorkItem(
            OrganizationId organizationId,
            TeamId teamId,
            WorkspaceId workspaceId,
            WorkProjectId projectId,
            WorkItemId workItemId,
            Optional<TaskAssociationCursor> cursor,
            int limit) {
        return new TaskAssociationQuery(
                organizationId,
                teamId,
                workspaceId,
                Optional.of(projectId),
                Optional.of(workItemId),
                Optional.empty(),
                cursor,
                limit);
    }

    public static TaskAssociationQuery byConversation(
            OrganizationId organizationId,
            TeamId teamId,
            WorkspaceId workspaceId,
            ConversationId conversationId,
            Optional<TaskAssociationCursor> cursor,
            int limit) {
        return new TaskAssociationQuery(
                organizationId,
                teamId,
                workspaceId,
                Optional.empty(),
                Optional.empty(),
                Optional.of(conversationId),
                cursor,
                limit);
    }

    public TaskAssociationSourceType sourceType() {
        return sourceType(workItemId);
    }

    public UUID sourceId() {
        return sourceId(workItemId, conversationId);
    }

    private static TaskAssociationSourceType sourceType(Optional<WorkItemId> workItemId) {
        return workItemId.isPresent()
                ? TaskAssociationSourceType.WORK_ITEM
                : TaskAssociationSourceType.CONVERSATION;
    }

    private static UUID sourceId(
            Optional<WorkItemId> workItemId, Optional<ConversationId> conversationId) {
        return workItemId.map(WorkItemId::value)
                .orElseGet(() -> conversationId.orElseThrow().value());
    }
}
