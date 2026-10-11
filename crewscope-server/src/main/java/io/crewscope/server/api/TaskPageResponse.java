package io.crewscope.server.api;

import io.crewscope.application.task.TaskListPage;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;

/** One stable page of Task summaries plus the opaque continuation cursor. */
public record TaskPageResponse(List<TaskListItemResponse> items, String nextCursor) {
    static TaskPageResponse from(
            TaskListPage page,
            TaskListCursorCodec codec,
            OrganizationId organizationId,
            TeamId teamId,
            Optional<WorkProjectId> projectId,
            Optional<TaskStatus> status,
            Optional<PrincipalId> ownerPrincipalId) {
        return new TaskPageResponse(
                page.items().stream().map(TaskListItemResponse::from).toList(),
                page.nextCursor()
                        .map(value -> codec.encode(
                                value,
                                organizationId,
                                teamId,
                                projectId,
                                status,
                                ownerPrincipalId))
                        .orElse(null));
    }
}
