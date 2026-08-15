package io.crewscope.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import org.junit.jupiter.api.Test;

/** M3-A03 progress version fencing on the TaskExecution aggregate. */
class TaskExecutionProgressTest {

    @Test
    void recordsProgressOnlyWhileRunningAndAdvancesAuditVersion() {
        TaskDomainFixture fixture = new TaskDomainFixture();
        Principal actor = fixture.executor;
        UtcTimestamp now = UtcTimestamp.parse("2026-08-15T08:00:00Z");
        TaskExecution running = TaskExecution.firstAttempt(
                        TaskExecutionId.generate(),
                        fixture.task(),
                        3,
                        TaskExecutionPriority.NORMAL,
                        TaskDomainFixture.CREATED_AT,
                        fixture.owner,
                        TaskDomainFixture.CREATED_AT)
                .markReady(0, fixture.owner, UtcTimestamp.parse("2026-08-13T08:05:00Z"))
                .claim(1, actor, UtcTimestamp.parse("2026-08-13T08:10:00Z"))
                .beginPreparing(2, actor, UtcTimestamp.parse("2026-08-13T08:15:00Z"))
                .beginRunning(3, actor, UtcTimestamp.parse("2026-08-13T08:20:00Z"));

        TaskExecution progressed = running.recordProgress(running.version(), actor, now);

        assertEquals(TaskExecutionStatus.RUNNING, progressed.status());
        assertEquals(running.version() + 1, progressed.version());
        assertEquals(actor.id(), progressed.audit().updatedBy().orElseThrow());
        assertThrows(
                OptimisticLockConflictException.class,
                () -> running.recordProgress(running.version() + 1, actor, now));
        TaskExecution completed = running.complete(running.version(), actor, now);
        assertThrows(
                InvalidStateTransitionException.class,
                () -> completed.recordProgress(completed.version(), actor, now));
    }
}
