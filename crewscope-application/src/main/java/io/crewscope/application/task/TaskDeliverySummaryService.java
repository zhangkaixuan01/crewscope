package io.crewscope.application.task;

import io.crewscope.application.action.ActionBundleView;
import io.crewscope.application.action.ActionDeliveryApplicationService;
import io.crewscope.application.review.ReviewRequestApplicationService;
import io.crewscope.application.review.ReviewRequestProjection;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.agent.ResolvedModelSelection;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Builds Task and Conversation delivery cards after rechecking current Task visibility. */
public final class TaskDeliverySummaryService {

    private final WorkItemAccessPolicy accessPolicy;
    private final TaskRepository tasks;
    private final TaskExecutionRepository executions;
    private final PolicySnapshotRepository policies;
    private final ReviewRequestApplicationService reviews;
    private final ActionDeliveryApplicationService actions;
    private final TransactionExecutor transactions;

    public TaskDeliverySummaryService(
            WorkItemAccessPolicy accessPolicy,
            TaskRepository tasks,
            TaskExecutionRepository executions,
            PolicySnapshotRepository policies,
            ReviewRequestApplicationService reviews,
            ActionDeliveryApplicationService actions,
            TransactionExecutor transactions) {
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.reviews = Objects.requireNonNull(reviews, "reviews");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    /** Reauthorizes every read so a revoked member cannot keep polling a previously visible card. */
    public TaskDeliverySummary get(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId) {
        return transactions.required(() -> summarize(
                Objects.requireNonNull(context, "context"),
                organizationId,
                teamId,
                requireTask(organizationId, teamId, taskId)));
    }

    /** Enriches one bounded association page in one read transaction without reloading each Task. */
    public List<TaskDeliverySummary> summarizePage(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TaskAssociationPage page) {
        TeamAccessContext trusted = Objects.requireNonNull(context, "context");
        TaskAssociationPage requiredPage = Objects.requireNonNull(page, "page");
        return transactions.required(() -> requiredPage.items().stream()
                .map(item -> summarize(
                        trusted, organizationId, teamId, item.task()))
                .toList());
    }

    private TaskDeliverySummary summarize(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            Task task) {
        return summarize(
                context,
                organizationId,
                teamId,
                task.id(),
                task.scope(),
                task.workItemId(),
                task.status().name(),
                task.currentExecutionId());
    }

    private TaskDeliverySummary summarize(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TaskListItem task) {
        return summarize(
                context,
                organizationId,
                teamId,
                task.id(),
                task.scope(),
                task.workItemId(),
                task.status().name(),
                task.currentExecutionId());
    }

    private TaskDeliverySummary summarize(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            WorkItemScope scope,
            WorkItemId workItemId,
            String taskStatus,
            Optional<TaskExecutionId> currentExecutionId) {
        requireScope(organizationId, teamId, scope);
        accessPolicy.requireVisibleWorkItem(
                context,
                organizationId,
                teamId,
                scope.projectId(),
                workItemId);
        if (currentExecutionId.isEmpty()) {
            return new TaskDeliverySummary(
                    taskId.toString(), taskStatus, null, null, null, null, null);
        }
        TaskExecution execution = executions.findById(
                        organizationId, currentExecutionId.orElseThrow())
                .filter(value -> value.taskId().equals(taskId)
                        && value.scope().equals(scope))
                .orElseThrow(() -> new AggregateNotFoundException(
                        "TaskExecution", currentExecutionId.orElseThrow()));
        return new TaskDeliverySummary(
                taskId.toString(),
                taskStatus,
                execution.id().toString(),
                execution.attempt(),
                agent(organizationId, execution).orElse(null),
                review(context, organizationId, teamId, taskId, execution).orElse(null),
                action(context, organizationId, teamId, taskId, execution).orElse(null));
    }

    private Optional<TaskDeliverySummary.AgentSummary> agent(
            OrganizationId organizationId, TaskExecution execution) {
        Optional<PolicySnapshot> policy = execution.planningContext()
                .flatMap(context -> policies.findById(organizationId, context.policySnapshotId()));
        return policy.flatMap(PolicySnapshot::agentExecutionConfiguration)
                .map(this::agentSummary);
    }

    private TaskDeliverySummary.AgentSummary agentSummary(
            ResolvedAgentExecutionConfiguration value) {
        return new TaskDeliverySummary.AgentSummary(
                value.agentProfileId().toString(),
                value.templateVersion().key().toString(),
                value.templateVersion().version(),
                value.configurationRevision().value(),
                value.executionScope().name(),
                value.bindingSource().name(),
                model(value.primary()),
                value.fallback().map(this::model).orElse(null));
    }

    private TaskDeliverySummary.ModelSummary model(ResolvedModelSelection value) {
        return new TaskDeliverySummary.ModelSummary(
                value.providerKey().value(),
                value.catalogCoordinate().modelId().value(),
                value.catalogCoordinate().catalogRevision().value());
    }

    private Optional<TaskDeliverySummary.ReviewSummary> review(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecution execution) {
        return reviews.list(context, organizationId, teamId, taskId, execution.id()).stream()
                .max(Comparator.comparingLong(ReviewRequestProjection::requestRevision))
                .map(value -> new TaskDeliverySummary.ReviewSummary(
                        value.reviewRequestId().toString(),
                        value.requestRevision(),
                        value.status().name(),
                        value.findingCount(),
                        value.blockerCount(),
                        value.highCount(),
                        value.latestDecisionType().map(Enum::name).orElse(null),
                        value.modificationRound()));
    }

    private Optional<TaskDeliverySummary.ActionSummary> action(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecution execution) {
        return actions.list(context, organizationId, teamId, taskId, execution.id()).stream()
                .findFirst()
                .map(value -> new TaskDeliverySummary.ActionSummary(
                        value.id(),
                        value.version(),
                        value.digest(),
                        value.validity(),
                        value.confirmation() == null ? null : value.confirmation().status(),
                        value.repositoryKey(),
                        value.actions().stream().map(this::stage).toList()));
    }

    private TaskDeliverySummary.ActionStageSummary stage(ActionBundleView.PlannedActionView value) {
        return new TaskDeliverySummary.ActionStageSummary(
                value.kind(),
                value.dispatch() == null ? null : value.dispatch().status(),
                value.receipt() == null ? null : value.receipt().result(),
                value.externalResult() == null ? null : value.externalResult().status(),
                value.externalResult() == null ? null : value.externalResult().externalObjectType(),
                value.externalResult() == null ? null : value.externalResult().externalIdentityHash());
    }

    private Task requireTask(
            OrganizationId organizationId, TeamId teamId, TaskId taskId) {
        Task task = tasks.findById(organizationId, Objects.requireNonNull(taskId, "taskId"))
                .filter(value -> value.scope().teamId().equals(teamId))
                .orElseThrow(() -> new AggregateNotFoundException("Task", taskId));
        if (!task.scope().organizationId().equals(organizationId)) {
            throw new DomainValidationException(
                    "taskDeliverySummary.scope", "must remain inside the requested route");
        }
        return task;
    }

    private static void requireScope(
            OrganizationId organizationId, TeamId teamId, WorkItemScope scope) {
        if (!scope.organizationId().equals(organizationId)
                || !scope.teamId().equals(teamId)) {
            throw new DomainValidationException(
                    "taskDeliverySummary.scope", "must remain inside the requested route");
        }
    }
}
