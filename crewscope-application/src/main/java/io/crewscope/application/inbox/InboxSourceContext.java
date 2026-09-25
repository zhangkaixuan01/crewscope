package io.crewscope.application.inbox;

import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Objects;
import java.util.Optional;

/**
 * The readable work facts one Inbox row points at, resolved at read time (M9b-A06).
 *
 * <p>The context is joined from the live source tables when the page is read, never copied into the
 * projection, so a renamed WorkItem or Task shows its current title and objective on the next read
 * and the projection stays rebuildable from its events alone. A row whose source object is gone or
 * carries no work facts resolves to nothing, and the surface renders the row without the context.
 */
public record InboxSourceContext(
        Optional<WorkProjectId> projectId,
        Optional<WorkItemId> workItemId,
        Optional<String> workItemTitle,
        Optional<String> taskObjective,
        Optional<String> waitingOnDisplayName,
        String targetActionKind) {

    public InboxSourceContext {
        projectId = Objects.requireNonNull(projectId, "projectId");
        workItemId = Objects.requireNonNull(workItemId, "workItemId");
        workItemTitle = Objects.requireNonNull(workItemTitle, "workItemTitle");
        taskObjective = Objects.requireNonNull(taskObjective, "taskObjective");
        waitingOnDisplayName = Objects.requireNonNull(waitingOnDisplayName, "waitingOnDisplayName");
        if (targetActionKind == null || targetActionKind.isBlank()) {
            throw new IllegalArgumentException("targetActionKind must name the row's target kind");
        }
    }
}
