package io.crewscope.application.conversation;

import io.crewscope.domain.conversation.TaskIntent;
import io.crewscope.domain.conversation.TaskIntentId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.workitem.WorkItemId;
import java.util.Optional;

/** Persistence Port for TaskIntent proposal and single terminal decision lifecycle. */
public interface TaskIntentRepository {
    TaskIntent create(TaskIntent taskIntent);
    TaskIntent update(TaskIntent taskIntent);
    TaskIntent confirm(TaskIntent taskIntent, WorkItemId confirmedWorkItemId);
    Optional<TaskIntent> findById(OrganizationId organizationId, TaskIntentId id);
    Optional<TaskIntent> lockById(OrganizationId organizationId, TaskIntentId id);
    Optional<WorkItemId> findConfirmedWorkItemId(
            OrganizationId organizationId, TaskIntentId id);
}
