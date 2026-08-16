package io.crewscope.application.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskStatus;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Application contract for M3-A02 visibility, keysets and bounded runtime graph reads. */
class TaskQueryServiceM3A02Test {

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final WorkProjectId projectId = WorkProjectId.generate();
    private final WorkItemScope scope = new WorkItemScope(
            organizationId, teamId, WorkspaceId.generate(), projectId);
    private final TeamAccessContext context = mock(TeamAccessContext.class);
    private final WorkItemAccessPolicy accessPolicy = mock(WorkItemAccessPolicy.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final TaskExecutionRepository executions = mock(TaskExecutionRepository.class);
    private final PlanVersionRepository plans = mock(PlanVersionRepository.class);
    private final StepExecutionRepository steps = mock(StepExecutionRepository.class);
    private final TaskAgentRuntimeSessionRepository sessions =
            mock(TaskAgentRuntimeSessionRepository.class);
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final AgentInterruptRepository interrupts = mock(AgentInterruptRepository.class);
    private final AgentStateSnapshotRepository snapshots = mock(AgentStateSnapshotRepository.class);
    private final ExecutionLeaseRepository leases = mock(ExecutionLeaseRepository.class);
    private final TransactionExecutor transactions = new TransactionExecutor() {
        @Override
        public <T> T required(Supplier<T> operation) {
            return operation.get();
        }
    };

    private TaskQueryService service;

    @BeforeEach
    void setUp() {
        service = new TaskQueryService(
                accessPolicy,
                tasks,
                executions,
                plans,
                steps,
                sessions,
                runs,
                interrupts,
                snapshots,
                leases,
                transactions);
    }

    @Test
    void listsOnlyAfterMembershipAndPreservesEveryKeysetFilter() {
        TaskListCursor cursor = new TaskListCursor(
                UtcTimestamp.parse("2026-08-15T08:00:00Z"), TaskId.generate());
        TaskListPage expected = new TaskListPage(List.of(), Optional.empty());
        when(tasks.findPage(any())).thenReturn(expected);

        TaskListPage actual = service.list(
                context,
                organizationId,
                teamId,
                Optional.of(projectId),
                Optional.of(TaskStatus.ACTIVE),
                Optional.of(PrincipalId.generate()),
                Optional.of(cursor),
                25);

        assertEquals(expected, actual);
        verify(accessPolicy).requireVisibleProject(context, organizationId, teamId, projectId);
        ArgumentCaptor<TaskListQuery> query = ArgumentCaptor.forClass(TaskListQuery.class);
        verify(tasks).findPage(query.capture());
        assertEquals(Optional.of(projectId), query.getValue().projectId());
        assertEquals(Optional.of(TaskStatus.ACTIVE), query.getValue().status());
        org.junit.jupiter.api.Assertions.assertTrue(query.getValue().ownerPrincipalId().isPresent());
        assertEquals(Optional.of(cursor), query.getValue().cursor());
        assertEquals(25, query.getValue().limit());
    }

    @Test
    void failsClosedBeforeQueryingWhenTheCallerIsNotAnActiveTeamMember() {
        when(accessPolicy.requireVisibleTeam(context, organizationId, teamId))
                .thenThrow(new PolicyDeniedException("read Team Tasks"));

        assertThrows(PolicyDeniedException.class, () -> service.list(
                context,
                organizationId,
                teamId,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                20));

        verify(tasks, never()).findPage(any());
    }

    @Test
    void hidesTasksAndAttemptsWhosePersistedScopeDoesNotMatchTheRoute() {
        Task task = mock(Task.class);
        TaskId taskId = TaskId.generate();
        when(task.id()).thenReturn(taskId);
        when(task.scope()).thenReturn(new WorkItemScope(
                organizationId, TeamId.generate(), scope.workspaceId(), projectId));
        when(tasks.findById(organizationId, taskId)).thenReturn(Optional.of(task));

        assertThrows(AggregateNotFoundException.class, () ->
                service.get(context, organizationId, teamId, taskId));
        verifyNoInteractions(executions);
    }

    @Test
    void loadsOneHistoricalAttemptWithAConstantNumberOfBulkRepositoryCalls() {
        TaskId taskId = TaskId.generate();
        TaskExecutionId executionId = TaskExecutionId.generate();
        Task task = mock(Task.class);
        TaskExecution execution = mock(TaskExecution.class);
        when(task.id()).thenReturn(taskId);
        when(task.scope()).thenReturn(scope);
        when(execution.id()).thenReturn(executionId);
        when(execution.taskId()).thenReturn(taskId);
        when(execution.scope()).thenReturn(scope);
        when(tasks.findById(organizationId, taskId)).thenReturn(Optional.of(task));
        when(executions.findById(organizationId, executionId)).thenReturn(Optional.of(execution));
        when(plans.findByExecution(organizationId, executionId)).thenReturn(List.of());
        when(steps.findByExecution(organizationId, executionId)).thenReturn(List.of());
        when(sessions.findByExecution(organizationId, executionId)).thenReturn(List.of());
        when(runs.findByExecution(organizationId, executionId)).thenReturn(List.of());
        when(interrupts.findByExecution(organizationId, executionId)).thenReturn(List.of());
        when(snapshots.findByExecution(organizationId, executionId)).thenReturn(List.of());
        when(leases.findByTaskExecution(organizationId, executionId)).thenReturn(List.of());

        TaskRuntimeFacts facts = service.runtimeFacts(
                context, organizationId, teamId, taskId, executionId);

        assertEquals(execution, facts.execution());
        verify(plans).findByExecution(organizationId, executionId);
        verify(steps).findByExecution(organizationId, executionId);
        verify(sessions).findByExecution(organizationId, executionId);
        verify(runs).findByExecution(organizationId, executionId);
        verify(interrupts).findByExecution(organizationId, executionId);
        verify(snapshots).findByExecution(organizationId, executionId);
        verify(leases).findByTaskExecution(organizationId, executionId);
    }

    @Test
    void rejectsAnExecutionThatBelongsToAnotherTaskBeforeLoadingChildren() {
        TaskId taskId = TaskId.generate();
        TaskExecutionId executionId = TaskExecutionId.generate();
        Task task = mock(Task.class);
        TaskExecution execution = mock(TaskExecution.class);
        when(task.id()).thenReturn(taskId);
        when(task.scope()).thenReturn(scope);
        when(execution.taskId()).thenReturn(TaskId.generate());
        when(execution.scope()).thenReturn(scope);
        when(tasks.findById(organizationId, taskId)).thenReturn(Optional.of(task));
        when(executions.findById(organizationId, executionId)).thenReturn(Optional.of(execution));

        assertThrows(AggregateNotFoundException.class, () -> service.runtimeFacts(
                context, organizationId, teamId, taskId, executionId));
        verifyNoInteractions(plans, steps, sessions, runs, interrupts, snapshots, leases);
    }
}
