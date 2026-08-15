package io.crewscope.application.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.crewscope.application.conversation.ConversationApplicationService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ConversationTaskLinkOrigin;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskBrief;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskStatus;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Application evidence for M3-A06 visibility, history retention and bounded repository use. */
class TaskAssociationServiceM3A06Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-15T10:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final WorkspaceId workspaceId = WorkspaceId.generate();
    private final WorkProjectId projectId = WorkProjectId.generate();
    private final WorkItemId workItemId = WorkItemId.generate();
    private final WorkItemScope scope = new WorkItemScope(
            organizationId, teamId, workspaceId, projectId);
    private final TeamAccessContext context = mock(TeamAccessContext.class);
    private final WorkItemAccessPolicy accessPolicy = mock(WorkItemAccessPolicy.class);
    private final ConversationApplicationService conversations =
            mock(ConversationApplicationService.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final TaskAssociationRepository associations = mock(TaskAssociationRepository.class);
    private final TransactionExecutor transactions = new TransactionExecutor() {
        @Override
        public <T> T required(Supplier<T> operation) {
            return operation.get();
        }
    };

    private TaskAssociationService service;

    @BeforeEach
    void setUp() {
        service = new TaskAssociationService(
                accessPolicy, conversations, tasks, associations, transactions);
    }

    @Test
    void keepsMultipleCurrentCancelledAndHistoricalTasksOnAVisibleWorkItem() {
        WorkItem workItem = workItem();
        TaskAssociationItem active = item(TaskStatus.ACTIVE, Optional.empty());
        TaskAssociationItem cancelled = item(TaskStatus.CANCELLED, Optional.empty());
        when(accessPolicy.requireVisibleWorkItem(
                        context, organizationId, teamId, projectId, workItemId))
                .thenReturn(workItem);
        when(associations.findTasks(any())).thenReturn(
                new TaskAssociationPage(List.of(active, cancelled), Optional.empty()));

        TaskAssociationPage page = service.byWorkItem(
                context,
                organizationId,
                teamId,
                projectId,
                workItemId,
                Optional.empty(),
                20);

        assertEquals(List.of(TaskStatus.ACTIVE, TaskStatus.CANCELLED), page.items().stream()
                .map(value -> value.task().status())
                .toList());
        verify(associations).findTasks(any(TaskAssociationQuery.class));
    }

    @Test
    void hiddenPrivateConversationFailsBeforeAnyAssociationQuery() {
        ConversationId conversationId = ConversationId.generate();
        when(conversations.get(context, organizationId, teamId, conversationId))
                .thenThrow(new AggregateNotFoundException("Conversation", conversationId));

        assertThrows(AggregateNotFoundException.class, () -> service.byConversation(
                context,
                organizationId,
                teamId,
                conversationId,
                Optional.empty(),
                20));

        verifyNoInteractions(associations);
    }

    @Test
    void rejectsCrossTeamRowsReturnedByTheAssociationPort() {
        WorkItem workItem = workItem();
        WorkItemScope anotherTeam = new WorkItemScope(
                organizationId, TeamId.generate(), workspaceId, projectId);
        TaskAssociationItem leaked = new TaskAssociationItem(
                taskItem(anotherTeam, TaskStatus.ACTIVE), Optional.empty(), NOW);
        when(accessPolicy.requireVisibleWorkItem(
                        context, organizationId, teamId, projectId, workItemId))
                .thenReturn(workItem);
        when(associations.findTasks(any())).thenReturn(
                new TaskAssociationPage(List.of(leaked), Optional.empty()));

        assertThrows(DomainValidationException.class, () -> service.byWorkItem(
                context,
                organizationId,
                teamId,
                projectId,
                workItemId,
                Optional.empty(),
                20));
    }

    @Test
    void taskReverseLookupUsesOneBulkConversationQueryAfterIndependentWorkItemAccess() {
        Task task = mock(Task.class);
        TaskId taskId = TaskId.generate();
        WorkItem workItem = workItem();
        PrincipalId viewer = PrincipalId.generate();
        when(context.actor()).thenReturn(mock(io.crewscope.domain.identity.Principal.class));
        when(context.actor().id()).thenReturn(viewer);
        when(task.id()).thenReturn(taskId);
        when(task.scope()).thenReturn(scope);
        when(task.workItemId()).thenReturn(workItemId);
        when(tasks.findById(organizationId, taskId)).thenReturn(Optional.of(task));
        TeamMember viewerMember = mock(TeamMember.class);
        when(viewerMember.id()).thenReturn(TeamMemberId.generate());
        when(accessPolicy.requireVisibleTeamMember(context, organizationId, teamId))
                .thenReturn(viewerMember);
        when(accessPolicy.requireVisibleWorkItem(
                        context, organizationId, teamId, projectId, workItemId))
                .thenReturn(workItem);
        when(associations.findVisibleConversations(any())).thenReturn(
                new TaskConversationAssociationPage(List.of(), Optional.empty()));

        TaskAssociationDetails result = service.byTask(
                context, organizationId, teamId, taskId, Optional.empty(), 20);

        assertEquals(workItem, result.workItem());
        verify(associations).findVisibleConversations(
                any(TaskConversationAssociationQuery.class));
        verify(associations, never()).findTasks(any());
    }

    private WorkItem workItem() {
        WorkItem value = mock(WorkItem.class);
        when(value.id()).thenReturn(workItemId);
        when(value.scope()).thenReturn(scope);
        return value;
    }

    private TaskAssociationItem item(
            TaskStatus status, Optional<ConversationTaskLinkOrigin> origin) {
        return new TaskAssociationItem(taskItem(scope, status), origin, NOW);
    }

    private TaskListItem taskItem(WorkItemScope itemScope, TaskStatus status) {
        return new TaskListItem(
                TaskId.generate(),
                itemScope,
                workItemId,
                new TaskBrief("Execute association test", List.of("Remain visible")),
                status,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                AuditMetadata.createdBy(PrincipalId.generate(), NOW));
    }
}
