package io.crewscope.application.task;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskStatus;
import java.util.Objects;
import java.util.Optional;

/** Applies current member authorization before every Task history or SSE poll. */
public final class TaskEventService {

    private final WorkItemAccessPolicy accessPolicy;
    private final TaskRepository taskRepository;
    private final TaskEventRepository eventRepository;
    private final TransactionExecutor transactionExecutor;

    public TaskEventService(
            WorkItemAccessPolicy accessPolicy,
            TaskRepository taskRepository,
            TaskEventRepository eventRepository,
            TransactionExecutor transactionExecutor) {
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
        this.eventRepository = Objects.requireNonNull(eventRepository, "eventRepository");
        this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
    }

    public TaskEventPage events(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            Optional<TaskEventCursor> cursor,
            int limit) {
        return transactionExecutor.required(() -> {
            accessPolicy.requireVisibleTeam(context, organizationId, teamId);
            Task task = taskRepository.findById(organizationId, taskId)
                    .filter(value -> value.scope().teamId().equals(teamId))
                    .orElseThrow(() -> new AggregateNotFoundException("Task", taskId));
            return eventRepository.findPage(
                    new TaskEventQuery(
                            task.scope(), task.id(), Objects.requireNonNull(cursor, "cursor"), limit),
                    terminal(task.status()));
        });
    }

    private static boolean terminal(TaskStatus status) {
        return status == TaskStatus.COMPLETED
                || status == TaskStatus.FAILED
                || status == TaskStatus.CANCELLED;
    }
}
