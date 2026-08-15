package io.crewscope.application.task;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Serves membership-authorized Task collections, attempts and safe runtime facts. */
public final class TaskQueryService {

    private final WorkItemAccessPolicy accessPolicy;
    private final TaskRepository taskRepository;
    private final TaskExecutionRepository executionRepository;
    private final PlanVersionRepository planRepository;
    private final StepExecutionRepository stepRepository;
    private final TaskAgentRuntimeSessionRepository sessionRepository;
    private final AgentRunRepository runRepository;
    private final AgentInterruptRepository interruptRepository;
    private final AgentStateSnapshotRepository snapshotRepository;
    private final ExecutionLeaseRepository leaseRepository;
    private final TransactionExecutor transactionExecutor;

    public TaskQueryService(
            WorkItemAccessPolicy accessPolicy,
            TaskRepository taskRepository,
            TaskExecutionRepository executionRepository,
            PlanVersionRepository planRepository,
            StepExecutionRepository stepRepository,
            TaskAgentRuntimeSessionRepository sessionRepository,
            AgentRunRepository runRepository,
            AgentInterruptRepository interruptRepository,
            AgentStateSnapshotRepository snapshotRepository,
            ExecutionLeaseRepository leaseRepository,
            TransactionExecutor transactionExecutor) {
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
        this.executionRepository = Objects.requireNonNull(executionRepository, "executionRepository");
        this.planRepository = Objects.requireNonNull(planRepository, "planRepository");
        this.stepRepository = Objects.requireNonNull(stepRepository, "stepRepository");
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.interruptRepository = Objects.requireNonNull(interruptRepository, "interruptRepository");
        this.snapshotRepository = Objects.requireNonNull(snapshotRepository, "snapshotRepository");
        this.leaseRepository = Objects.requireNonNull(leaseRepository, "leaseRepository");
        this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
    }

    public TaskListPage list(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            Optional<WorkProjectId> projectId,
            Optional<TaskStatus> status,
            Optional<TaskListCursor> cursor,
            int limit) {
        return transactionExecutor.required(() -> {
            Optional<WorkProjectId> project = Objects.requireNonNull(projectId, "projectId");
            project.ifPresentOrElse(
                    value -> accessPolicy.requireVisibleProject(
                            context, organizationId, teamId, value),
                    () -> accessPolicy.requireVisibleTeam(context, organizationId, teamId));
            return taskRepository.findPage(new TaskListQuery(
                    organizationId,
                    teamId,
                    project,
                    Objects.requireNonNull(status, "status"),
                    Objects.requireNonNull(cursor, "cursor"),
                    limit));
        });
    }

    public TaskDetails get(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId) {
        return transactionExecutor.required(() -> {
            accessPolicy.requireVisibleTeam(context, organizationId, teamId);
            Task task = requireTask(organizationId, teamId, taskId);
            return new TaskDetails(
                    task, executionRepository.findByTask(organizationId, task.id()));
        });
    }

    public List<TaskExecution> attempts(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId) {
        return get(context, organizationId, teamId, taskId).attempts();
    }

    public TaskRuntimeFacts runtimeFacts(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId) {
        return transactionExecutor.required(() -> {
            accessPolicy.requireVisibleTeam(context, organizationId, teamId);
            Task task = requireTask(organizationId, teamId, taskId);
            TaskExecution execution = executionRepository.findById(organizationId, executionId)
                    .filter(value -> value.taskId().equals(task.id()))
                    .filter(value -> value.scope().equals(task.scope()))
                    .orElseThrow(() -> new AggregateNotFoundException(
                            "TaskExecution", executionId));
            return new TaskRuntimeFacts(
                    task,
                    execution,
                    planRepository.findByExecution(organizationId, executionId),
                    stepRepository.findByExecution(organizationId, executionId),
                    sessionRepository.findByExecution(organizationId, executionId),
                    runRepository.findByExecution(organizationId, executionId),
                    interruptRepository.findByExecution(organizationId, executionId),
                    snapshotRepository.findByExecution(organizationId, executionId),
                    leaseRepository.findByTaskExecution(organizationId, executionId));
        });
    }

    private Task requireTask(
            OrganizationId organizationId, TeamId teamId, TaskId taskId) {
        return taskRepository.findById(organizationId, taskId)
                .filter(value -> value.scope().teamId().equals(teamId))
                .orElseThrow(() -> new AggregateNotFoundException("Task", taskId));
    }
}
