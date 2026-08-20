package io.crewscope.application.coding.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Permission, current/history and bulk-query contract for M4-A04. */
class TaskCodingQueryServiceM4A04Test {

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final WorkProjectId projectId = WorkProjectId.generate();
    private final WorkItemScope scope = new WorkItemScope(
            organizationId, teamId, WorkspaceId.generate(), projectId);
    private final TeamAccessContext context = mock(TeamAccessContext.class);
    private final WorkItemAccessPolicy accessPolicy = mock(WorkItemAccessPolicy.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final TaskExecutionRepository executions = mock(TaskExecutionRepository.class);
    private final CodingAttemptQueryPort queryPort = mock(CodingAttemptQueryPort.class);
    private final TransactionExecutor transactions = new TransactionExecutor() {
        @Override
        public <T> T required(Supplier<T> operation) {
            return operation.get();
        }
    };

    private TaskCodingQueryService service;

    @BeforeEach
    void setUp() {
        service = new TaskCodingQueryService(
                accessPolicy, tasks, executions, queryPort, transactions);
    }

    @Test
    void joinsEveryHistoricalAttemptWithOneBulkProjectionCall() {
        TaskId taskId = TaskId.generate();
        TaskExecution first = execution(taskId, 1);
        TaskExecution second = execution(taskId, 2);
        TaskExecutionId firstId = first.id();
        TaskExecutionId secondId = second.id();
        Task task = task(taskId, Optional.of(secondId));
        CodingAttemptProjection secondProjection = mock(CodingAttemptProjection.class);
        when(secondProjection.executionId()).thenReturn(secondId);
        when(tasks.findById(organizationId, taskId)).thenReturn(Optional.of(task));
        when(executions.findByTask(organizationId, taskId)).thenReturn(List.of(first, second));
        when(queryPort.findByTask(organizationId, teamId, projectId, taskId))
                .thenReturn(List.of(secondProjection));

        List<TaskCodingQueryService.AttemptView> result =
                service.attempts(context, organizationId, teamId, taskId);

        assertEquals(2, result.size());
        assertFalse(result.get(0).coding());
        assertEquals(true, result.get(1).coding());
        assertEquals(true, result.get(1).current());
        verify(queryPort).findByTask(organizationId, teamId, projectId, taskId);
        verify(queryPort, never()).findByExecution(
                organizationId, teamId, projectId, taskId, firstId);
    }

    @Test
    void returnsExplicitEmptyCurrentStateBeforeTheFirstAttempt() {
        TaskId taskId = TaskId.generate();
        Task task = task(taskId, Optional.empty());
        when(tasks.findById(organizationId, taskId)).thenReturn(Optional.of(task));

        var result = service.current(context, organizationId, teamId, taskId);

        assertEquals(Optional.empty(), result.currentAttempt());
        verifyNoInteractions(executions, queryPort);
    }

    @Test
    void rejectsCrossTaskAttemptBeforeReadingCodingFacts() {
        TaskId taskId = TaskId.generate();
        TaskExecutionId executionId = TaskExecutionId.generate();
        Task task = task(taskId, Optional.of(executionId));
        TaskExecution foreign = execution(TaskId.generate(), 1);
        when(tasks.findById(organizationId, taskId)).thenReturn(Optional.of(task));
        when(executions.findById(organizationId, executionId)).thenReturn(Optional.of(foreign));

        assertThrows(AggregateNotFoundException.class, () ->
                service.attempt(context, organizationId, teamId, taskId, executionId));

        verifyNoInteractions(queryPort);
    }

    @Test
    void failsClosedBeforeTaskAndEvidenceQueriesWhenMembershipIsDenied() {
        TaskId taskId = TaskId.generate();
        when(accessPolicy.requireVisibleTeam(context, organizationId, teamId))
                .thenThrow(new PolicyDeniedException("read Coding attempts"));

        assertThrows(PolicyDeniedException.class, () -> service.attempts(
                context, organizationId, teamId, taskId));

        verifyNoInteractions(tasks, executions, queryPort);
    }

    private Task task(TaskId taskId, Optional<TaskExecutionId> current) {
        Task value = mock(Task.class);
        when(value.id()).thenReturn(taskId);
        when(value.scope()).thenReturn(scope);
        when(value.currentExecutionId()).thenReturn(current);
        return value;
    }

    private TaskExecution execution(TaskId taskId, int attempt) {
        TaskExecution value = mock(TaskExecution.class);
        when(value.id()).thenReturn(TaskExecutionId.generate());
        when(value.taskId()).thenReturn(taskId);
        when(value.scope()).thenReturn(scope);
        when(value.attempt()).thenReturn(attempt);
        when(value.status()).thenReturn(TaskExecutionStatus.READY);
        return value;
    }
}
