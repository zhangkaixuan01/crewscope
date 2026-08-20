package io.crewscope.application.coding.query;

import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Membership-authorized Task/attempt Coding read model. */
public final class TaskCodingQueryService {

    private final WorkItemAccessPolicy accessPolicy;
    private final TaskRepository taskRepository;
    private final TaskExecutionRepository executionRepository;
    private final CodingAttemptQueryPort queryPort;
    private final TransactionExecutor transactionExecutor;

    public TaskCodingQueryService(
            WorkItemAccessPolicy accessPolicy,
            TaskRepository taskRepository,
            TaskExecutionRepository executionRepository,
            CodingAttemptQueryPort queryPort,
            TransactionExecutor transactionExecutor) {
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
        this.executionRepository = Objects.requireNonNull(executionRepository, "executionRepository");
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
    }

    public CurrentCodingAttempt current(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId) {
        return transactionExecutor.required(() -> {
            Task task = requireTask(context, organizationId, teamId, taskId);
            Optional<AttemptView> current = task.currentExecutionId().map(executionId -> attemptView(
                    executionRepository.findById(organizationId, executionId)
                            .filter(value -> belongsTo(value, task))
                            .orElseThrow(() -> new AggregateNotFoundException("TaskExecution", executionId)),
                    true,
                    queryPort.findByExecution(
                            organizationId,
                            teamId,
                            task.scope().projectId(),
                            task.id(),
                            executionId)));
            return new CurrentCodingAttempt(task.id(), current);
        });
    }

    public List<AttemptView> attempts(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId) {
        return transactionExecutor.required(() -> {
            Task task = requireTask(context, organizationId, teamId, taskId);
            List<TaskExecution> executions = executionRepository.findByTask(organizationId, task.id());
            Map<TaskExecutionId, CodingAttemptProjection> projections = new HashMap<>();
            queryPort.findByTask(organizationId, teamId, task.scope().projectId(), task.id())
                    .forEach(value -> projections.put(value.executionId(), value));
            List<AttemptView> result = new ArrayList<>(executions.size());
            for (TaskExecution execution : executions) {
                if (!belongsTo(execution, task)) {
                    throw new IllegalStateException("TaskExecution query returned a cross-scope attempt");
                }
                result.add(attemptView(
                        execution,
                        task.currentExecutionId().filter(execution.id()::equals).isPresent(),
                        Optional.ofNullable(projections.get(execution.id()))));
            }
            return List.copyOf(result);
        });
    }

    public AttemptView attempt(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId) {
        return transactionExecutor.required(() -> {
            Task task = requireTask(context, organizationId, teamId, taskId);
            TaskExecution execution = requireExecution(organizationId, task, executionId);
            return attemptView(
                    execution,
                    task.currentExecutionId().filter(execution.id()::equals).isPresent(),
                    queryPort.findByExecution(
                            organizationId,
                            teamId,
                            task.scope().projectId(),
                            task.id(),
                            execution.id()));
        });
    }

    public CodingEvidencePage<CommandEvidenceProjection> commands(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId,
            Optional<CodingEvidenceCursor> cursor,
            int limit) {
        return evidence(context, organizationId, teamId, taskId, executionId,
                task -> queryPort.findCommands(organizationId, teamId, task.scope().projectId(),
                        task.id(), executionId, cursor, limit));
    }

    public CodingEvidencePage<TestEvidenceProjection> testEvidence(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId,
            Optional<CodingEvidenceCursor> cursor,
            int limit) {
        return evidence(context, organizationId, teamId, taskId, executionId,
                task -> queryPort.findTestEvidence(organizationId, teamId, task.scope().projectId(),
                        task.id(), executionId, cursor, limit));
    }

    private <T> T evidence(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId,
            java.util.function.Function<Task, T> query) {
        return transactionExecutor.required(() -> {
            Task task = requireTask(context, organizationId, teamId, taskId);
            requireExecution(organizationId, task, executionId);
            return query.apply(task);
        });
    }

    private Task requireTask(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId) {
        accessPolicy.requireVisibleTeam(context, organizationId, teamId);
        return taskRepository.findById(organizationId, taskId)
                .filter(value -> value.scope().teamId().equals(teamId))
                .orElseThrow(() -> new AggregateNotFoundException("Task", taskId));
    }

    private TaskExecution requireExecution(
            OrganizationId organizationId, Task task, TaskExecutionId executionId) {
        return executionRepository.findById(organizationId, executionId)
                .filter(value -> belongsTo(value, task))
                .orElseThrow(() -> new AggregateNotFoundException("TaskExecution", executionId));
    }

    private static boolean belongsTo(TaskExecution execution, Task task) {
        return execution.taskId().equals(task.id()) && execution.scope().equals(task.scope());
    }

    private static AttemptView attemptView(
            TaskExecution execution,
            boolean current,
            Optional<CodingAttemptProjection> projection) {
        return new AttemptView(
                execution.id(),
                execution.attempt(),
                execution.status().name(),
                current,
                projection.isPresent(),
                projection);
    }

    public record CurrentCodingAttempt(TaskId taskId, Optional<AttemptView> currentAttempt) {
        public CurrentCodingAttempt {
            currentAttempt = Objects.requireNonNull(currentAttempt, "currentAttempt");
        }
    }

    public record AttemptView(
            TaskExecutionId executionId,
            int attempt,
            String executionStatus,
            boolean current,
            boolean coding,
            Optional<CodingAttemptProjection> details) {
        public AttemptView {
            details = Objects.requireNonNull(details, "details");
            if (coding != details.isPresent()) {
                throw new IllegalArgumentException("coding must match details presence");
            }
        }
    }
}
