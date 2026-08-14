package io.crewscope.application.task;

import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for the Task aggregate, scoped at every tenant-sensitive operation. */
public interface TaskRepository {

    /** Inserts one Task at version zero. */
    Task create(Task task);

    /** Commits a Task mutation using the aggregate's previous version as lock predicate. */
    Task update(Task task);

    Optional<Task> findById(OrganizationId organizationId, TaskId taskId);

    /** Returns all Tasks rooted in one WorkItem; one WorkItem may own multiple Tasks. */
    List<Task> findByWorkItem(OrganizationId organizationId, WorkItemId workItemId);

    /** Returns Tasks associated through immutable ConversationTaskLink facts. */
    List<Task> findByConversation(
            OrganizationId organizationId, ConversationId conversationId);
}
