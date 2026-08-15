package io.crewscope.application.task;

import io.crewscope.application.conversation.ConversationApplicationService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.conversation.Conversation;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Objects;
import java.util.Optional;

/** Serves every WorkItem/Conversation/Task association direction with current visibility checks. */
public final class TaskAssociationService {

    private final WorkItemAccessPolicy workItemAccessPolicy;
    private final ConversationApplicationService conversationService;
    private final TaskRepository taskRepository;
    private final TaskAssociationRepository associationRepository;
    private final TransactionExecutor transactionExecutor;

    public TaskAssociationService(
            WorkItemAccessPolicy workItemAccessPolicy,
            ConversationApplicationService conversationService,
            TaskRepository taskRepository,
            TaskAssociationRepository associationRepository,
            TransactionExecutor transactionExecutor) {
        this.workItemAccessPolicy = Objects.requireNonNull(
                workItemAccessPolicy, "workItemAccessPolicy");
        this.conversationService = Objects.requireNonNull(
                conversationService, "conversationService");
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
        this.associationRepository = Objects.requireNonNull(
                associationRepository, "associationRepository");
        this.transactionExecutor = Objects.requireNonNull(
                transactionExecutor, "transactionExecutor");
    }

    /** Lists every current or historical Task rooted in one currently visible WorkItem. */
    public TaskAssociationPage byWorkItem(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            WorkItemId workItemId,
            Optional<TaskAssociationCursor> cursor,
            int limit) {
        return transactionExecutor.required(() -> {
            WorkItem workItem = workItemAccessPolicy.requireVisibleWorkItem(
                    context, organizationId, teamId, projectId, workItemId);
            TaskAssociationPage page = associationRepository.findTasks(
                    TaskAssociationQuery.byWorkItem(
                            organizationId,
                            teamId,
                            workItem.scope().workspaceId(),
                            projectId,
                            workItem.id(),
                            Objects.requireNonNull(cursor, "cursor"),
                            limit));
            page.items().forEach(item -> requireWorkItemTask(item, workItem));
            return page;
        });
    }

    /** Lists linked Tasks after independently authorizing the current Conversation. */
    public TaskAssociationPage byConversation(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            ConversationId conversationId,
            Optional<TaskAssociationCursor> cursor,
            int limit) {
        return transactionExecutor.required(() -> {
            Conversation conversation = conversationService
                    .get(context, organizationId, teamId, conversationId)
                    .conversation();
            TaskAssociationPage page = associationRepository.findTasks(
                    TaskAssociationQuery.byConversation(
                            organizationId,
                            teamId,
                            conversation.scope().workspaceId(),
                            conversation.id(),
                            Objects.requireNonNull(cursor, "cursor"),
                            limit));
            page.items().forEach(item -> requireConversationTask(item, conversation));
            return page;
        });
    }

    /** Returns the Task's visible WorkItem and a paginated set of currently visible Conversations. */
    public TaskAssociationDetails byTask(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            Optional<TaskAssociationCursor> cursor,
            int limit) {
        return transactionExecutor.required(() -> {
            TeamMember viewer = workItemAccessPolicy.requireVisibleTeamMember(
                    context, organizationId, teamId);
            Task task = taskRepository.findById(organizationId, taskId)
                    .filter(value -> value.scope().teamId().equals(teamId))
                    .orElseThrow(() -> new AggregateNotFoundException("Task", taskId));
            WorkItem workItem = workItemAccessPolicy.requireVisibleWorkItem(
                    context,
                    organizationId,
                    teamId,
                    task.scope().projectId(),
                    task.workItemId());
            requireTaskScope(task, workItem.scope());
            TaskConversationAssociationPage conversations =
                    associationRepository.findVisibleConversations(
                            new TaskConversationAssociationQuery(
                                    task.scope(),
                                    task.id(),
                                    context.actor().id(),
                                    viewer.id(),
                                    Objects.requireNonNull(cursor, "cursor"),
                                    limit));
            conversations.items().forEach(value -> {
                if (!value.scope().organizationId().equals(organizationId)
                        || !value.scope().teamId().equals(teamId)
                        || !value.scope().workspaceId().equals(task.scope().workspaceId())) {
                    throw invalidRepositoryResult();
                }
            });
            return new TaskAssociationDetails(task, workItem, conversations);
        });
    }

    private static void requireWorkItemTask(TaskAssociationItem item, WorkItem workItem) {
        TaskListItem task = item.task();
        if (!task.workItemId().equals(workItem.id())
                || !task.scope().equals(workItem.scope())
                || item.conversationOrigin().isPresent()) {
            throw invalidRepositoryResult();
        }
    }

    private static void requireConversationTask(
            TaskAssociationItem item, Conversation conversation) {
        TaskListItem task = item.task();
        if (!task.scope().organizationId().equals(conversation.scope().organizationId())
                || !task.scope().teamId().equals(conversation.scope().teamId())
                || !task.scope().workspaceId().equals(conversation.scope().workspaceId())
                || item.conversationOrigin().isEmpty()) {
            throw invalidRepositoryResult();
        }
    }

    private static void requireTaskScope(Task task, WorkItemScope workItemScope) {
        if (!task.scope().equals(workItemScope)) {
            throw invalidRepositoryResult();
        }
    }

    private static DomainValidationException invalidRepositoryResult() {
        return new DomainValidationException(
                "taskAssociation.repositoryResult",
                "must remain inside the authorized source and target scope");
    }
}
