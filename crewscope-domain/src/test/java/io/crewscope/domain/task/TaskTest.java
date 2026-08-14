package io.crewscope.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TaskTest {

    @Test
    void createsMultipleIndependentTasksForOneWorkItem() {
        TaskDomainFixture fixture = new TaskDomainFixture();

        Task first = fixture.task();
        Task second = fixture.task();

        assertNotEquals(first.id(), second.id());
        assertEquals(first.workItemId(), second.workItemId());
        assertEquals(TaskStatus.CREATED, first.status());
        assertEquals(0, first.version());
        assertEquals(fixture.owner.id(), first.audit().createdBy().orElseThrow());
    }

    @Test
    void rejectsStaleWorkItemSourceAtCreation() {
        TaskDomainFixture fixture = new TaskDomainFixture();
        TaskSource staleSource = new TaskSource(
                TaskSourceType.WORK_ITEM,
                fixture.scope,
                fixture.workItem.id(),
                fixture.workItem.version() - 1,
                Optional.empty(),
                Optional.empty());

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> Task.create(
                        TaskId.generate(),
                        fixture.workItem,
                        staleSource,
                        fixture.snapshot(),
                        fixture.owner,
                        TaskDomainFixture.CREATED_AT));

        assertEquals("task.source.workItemVersion", failure.error().details().get("field"));
    }

    @Test
    void bindsAndSwitchesCurrentAttemptWithReferenceAndVersionGuards() {
        TaskDomainFixture fixture = new TaskDomainFixture();
        TaskExecutionId firstId = TaskExecutionId.generate();
        TaskExecutionId secondId = TaskExecutionId.generate();

        Task started = fixture.task().switchCurrentExecution(
                Optional.empty(), firstId, 0, fixture.owner, TaskDomainFixture.LATER);
        Task retried = started.switchCurrentExecution(
                Optional.of(firstId), secondId, 1, fixture.owner, TaskDomainFixture.LATER);

        assertEquals(TaskStatus.ACTIVE, started.status());
        assertEquals(Optional.of(firstId), started.currentExecutionId());
        assertEquals(Optional.of(secondId), retried.currentExecutionId());
        assertEquals(2, retried.version());
        assertThrows(
                DomainValidationException.class,
                () -> retried.switchCurrentExecution(
                        Optional.of(firstId),
                        TaskExecutionId.generate(),
                        2,
                        fixture.owner,
                        TaskDomainFixture.LATER));
        assertThrows(
                OptimisticLockConflictException.class,
                () -> retried.switchCurrentExecution(
                        Optional.of(secondId),
                        TaskExecutionId.generate(),
                        1,
                        fixture.owner,
                        TaskDomainFixture.LATER));
    }

    @Test
    void synchronizesBusinessStatusOnlyFromCurrentAttempt() {
        TaskDomainFixture fixture = new TaskDomainFixture();
        TaskExecutionId executionId = TaskExecutionId.generate();
        Task started = fixture.task().switchCurrentExecution(
                Optional.empty(), executionId, 0, fixture.owner, TaskDomainFixture.LATER);

        Task waiting = started.synchronizeStatus(
                executionId, TaskStatus.WAITING, 1, fixture.owner, TaskDomainFixture.LATER);
        Task active = waiting.synchronizeStatus(
                executionId, TaskStatus.ACTIVE, 2, fixture.owner, TaskDomainFixture.LATER);
        Task completed = active.synchronizeStatus(
                executionId, TaskStatus.COMPLETED, 3, fixture.owner, TaskDomainFixture.LATER);

        assertEquals(TaskStatus.COMPLETED, completed.status());
        assertTrue(completed.isClosed());
        assertThrows(
                DomainValidationException.class,
                () -> started.synchronizeStatus(
                        TaskExecutionId.generate(),
                        TaskStatus.WAITING,
                        1,
                        fixture.owner,
                        TaskDomainFixture.LATER));
        assertThrows(
                InvalidStateTransitionException.class,
                () -> completed.synchronizeStatus(
                        executionId,
                        TaskStatus.ACTIVE,
                        4,
                        fixture.owner,
                        TaskDomainFixture.LATER));
    }

    @Test
    void reopensFailedTaskOnlyBySelectingANewAttempt() {
        TaskDomainFixture fixture = new TaskDomainFixture();
        TaskExecutionId failedExecutionId = TaskExecutionId.generate();
        Task failed = fixture.task()
                .switchCurrentExecution(
                        Optional.empty(),
                        failedExecutionId,
                        0,
                        fixture.owner,
                        TaskDomainFixture.LATER)
                .synchronizeStatus(
                        failedExecutionId,
                        TaskStatus.FAILED,
                        1,
                        fixture.owner,
                        TaskDomainFixture.LATER);
        TaskExecutionId retryExecutionId = TaskExecutionId.generate();

        Task retried = failed.switchCurrentExecution(
                Optional.of(failedExecutionId),
                retryExecutionId,
                2,
                fixture.owner,
                TaskDomainFixture.LATER);

        assertEquals(TaskStatus.ACTIVE, retried.status());
        assertEquals(Optional.of(retryExecutionId), retried.currentExecutionId());
    }

    @Test
    void cancelsAndPreservesImmutableCreationFacts() {
        TaskDomainFixture fixture = new TaskDomainFixture();
        Task original = fixture.task();

        Task cancelled = original.cancel(
                "  Request superseded  ", 0, fixture.owner, TaskDomainFixture.LATER);

        assertEquals(TaskStatus.CANCELLED, cancelled.status());
        assertEquals("Request superseded", cancelled.cancellation().orElseThrow().reason());
        assertEquals(original.scope(), cancelled.scope());
        assertEquals(original.source(), cancelled.source());
        assertEquals(original.responsibilitySnapshot(), cancelled.responsibilitySnapshot());
        assertEquals(original.audit().createdAt(), cancelled.audit().createdAt());
        assertEquals(1, cancelled.version());
        assertThrows(
                InvalidStateTransitionException.class,
                () -> cancelled.cancel(
                        "Again", 1, fixture.owner, TaskDomainFixture.LATER));
    }
}
