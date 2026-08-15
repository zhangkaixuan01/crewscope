package io.crewscope.application.task;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Objects;
import java.util.Optional;

/** Scope-closed visible Conversation query for one Task and current member Principal. */
public record TaskConversationAssociationQuery(
        WorkItemScope scope,
        TaskId taskId,
        PrincipalId viewerPrincipalId,
        TeamMemberId viewerTeamMemberId,
        Optional<TaskAssociationCursor> cursor,
        int limit) {

    public TaskConversationAssociationQuery {
        scope = Objects.requireNonNull(scope, "scope");
        taskId = Objects.requireNonNull(taskId, "taskId");
        viewerPrincipalId = Objects.requireNonNull(viewerPrincipalId, "viewerPrincipalId");
        viewerTeamMemberId = Objects.requireNonNull(viewerTeamMemberId, "viewerTeamMemberId");
        cursor = Objects.requireNonNull(cursor, "cursor");
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        if (cursor.isPresent()) {
            cursor.orElseThrow().requireSource(
                    scope.organizationId(),
                    scope.teamId(),
                    TaskAssociationSourceType.TASK,
                    taskId.value());
        }
    }

    public OrganizationId organizationId() {
        return scope.organizationId();
    }
}
