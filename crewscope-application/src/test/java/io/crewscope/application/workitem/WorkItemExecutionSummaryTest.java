package io.crewscope.application.workitem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.task.TaskExecutionWaitReason;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemStatus;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The summary is a frozen §4.1 contract shared by the list, board and Today surfaces; its
 * constructors are where the invariants must fail, not in a rendering layer (M9b-A06).
 */
class WorkItemExecutionSummaryTest {

  private static final UtcTimestamp OBSERVED_AT = UtcTimestamp.parse("2026-09-01T08:00:00Z");
  private static final WorkItemId ITEM = WorkItemId.generate();

  @Test
  void noneReportsAZeroTaskItemWithNoRuntimeFacts() {
    WorkItemExecutionSummary summary = WorkItemExecutionSummary.none(ITEM, 7L, WorkItemStatus.BACKLOG, OBSERVED_AT);

    assertEquals(ITEM, summary.workItemId());
    assertEquals(7L, summary.workItemVersion());
    assertEquals(WorkItemStatus.BACKLOG, summary.workStatus());
    assertEquals(0, summary.taskCount());
    assertEquals(0, summary.activeTaskCount());
    assertEquals(0, summary.pendingReviewCount());
    assertTrue(summary.currentTaskId().isEmpty());
    assertTrue(summary.currentExecutionId().isEmpty());
    assertTrue(summary.executionStatus().isEmpty());
    assertFalse(summary.selectionRequired());
    assertTrue(summary.blockedReasons().isEmpty());
    assertTrue(summary.resultSummary().isEmpty());
    assertTrue(summary.resultSourceReference().isEmpty());
    assertEquals(7L, summary.projectionVersion());
    assertEquals(OBSERVED_AT, summary.observedAt());
  }

  @Test
  void aSingleTaskItemCarriesItsCurrentAttempt() {
    TaskId taskId = TaskId.generate();
    TaskExecutionId executionId = TaskExecutionId.generate();

    WorkItemExecutionSummary summary =
        new WorkItemExecutionSummary(
            ITEM,
            3L,
            WorkItemStatus.IN_PROGRESS,
            1,
            1,
            0,
            Optional.of(taskId),
            Optional.of(executionId),
            Optional.of(TaskExecutionStatus.WAITING),
            false,
            List.of(
                WorkItemBlockedReason.waiting(
                    TaskExecutionWaitReason.REVIEW,
                    taskId,
                    executionId,
                    Optional.of(OBSERVED_AT),
                    Optional.of(PrincipalId.generate()))),
            Optional.empty(),
            Optional.empty(),
            3L,
            OBSERVED_AT);

    assertEquals(Optional.of(taskId), summary.currentTaskId());
    assertEquals(Optional.of(executionId), summary.currentExecutionId());
    assertEquals(Optional.of(TaskExecutionStatus.WAITING), summary.executionStatus());
    assertEquals(1, summary.blockedReasons().size());
    assertEquals(WorkItemBlockedReason.Code.REVIEW, summary.blockedReasons().get(0).code());
    assertTrue(summary.blockedReasons().get(0).waitingOnPrincipalId().isPresent());
  }

  @Test
  void aMultiTaskItemRequiresSelectionAndNeverGuessesACurrentAttempt() {
    WorkItemExecutionSummary summary =
        new WorkItemExecutionSummary(
            ITEM, 5L, WorkItemStatus.IN_PROGRESS, 3, 2, 1,
            Optional.empty(), Optional.empty(), Optional.empty(),
            true,
            List.of(WorkItemBlockedReason.reviewPending()),
            Optional.empty(), Optional.empty(),
            5L, OBSERVED_AT);

    assertTrue(summary.selectionRequired());
    assertTrue(summary.currentTaskId().isEmpty());
    assertEquals(WorkItemBlockedReason.Code.REVIEW_PENDING, summary.blockedReasons().get(0).code());
    assertTrue(summary.blockedReasons().get(0).taskId().isEmpty());
  }

  @Test
  void rejectsEveryDocumentedInvariantViolation() {
    // selectionRequired must equal (taskCount > 1).
    assertThrows(
        IllegalArgumentException.class,
        () -> summary(2, 2, 0, false),
        "two tasks must require selection");
    assertThrows(
        IllegalArgumentException.class,
        () -> summary(1, 1, 0, true),
        "one task must not require selection");
    // activeTaskCount is capped by taskCount.
    assertThrows(IllegalArgumentException.class, () -> summary(1, 2, 0, false));
    // Negative counters are meaningless.
    assertThrows(IllegalArgumentException.class, () -> summary(0, 0, -1, false));
    // The current-attempt fields appear together or not at all.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WorkItemExecutionSummary(
                ITEM, 1L, WorkItemStatus.READY, 1, 1, 0,
                Optional.of(TaskId.generate()),
                Optional.empty(),
                Optional.of(TaskExecutionStatus.RUNNING),
                false, List.of(), Optional.empty(), Optional.empty(), 1L, OBSERVED_AT));
    // And only a single-task item may report one.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WorkItemExecutionSummary(
                ITEM, 1L, WorkItemStatus.READY, 2, 1, 0,
                Optional.of(TaskId.generate()),
                Optional.of(TaskExecutionId.generate()),
                Optional.of(TaskExecutionStatus.RUNNING),
                true, List.of(), Optional.empty(), Optional.empty(), 1L, OBSERVED_AT));
  }

  private static WorkItemExecutionSummary summary(
      int taskCount, int activeTaskCount, int pendingReviewCount, boolean selectionRequired) {
    return new WorkItemExecutionSummary(
        ITEM, 1L, WorkItemStatus.READY, taskCount, activeTaskCount, pendingReviewCount,
        Optional.empty(), Optional.empty(), Optional.empty(),
        selectionRequired, List.of(), Optional.empty(), Optional.empty(), 1L, OBSERVED_AT);
  }
}
