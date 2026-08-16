package io.crewscope.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.crewscope.evaluation.StableTaskOrder.TaskRow;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class StableTaskOrderJudgeTest {

  @Test
  void usesPriorityThenCreationTimeThenCodePointId() {
    Instant now = Instant.parse("2026-08-16T00:00:00Z");
    List<TaskRow> rows =
        List.of(
            new TaskRow("task-z", 50, now),
            new TaskRow("task-b", 80, now.plusSeconds(1)),
            new TaskRow("task-a", 80, now.plusSeconds(1)),
            new TaskRow("task-old", 80, now));

    assertEquals(
        List.of("task-old", "task-a", "task-b", "task-z"),
        new StableTaskOrder().sort(rows).stream().map(TaskRow::id).toList());
  }

  @Test
  void doesNotMutateTheCallerList() {
    List<TaskRow> rows =
        new java.util.ArrayList<>(List.of(new TaskRow("task-1", 1, Instant.EPOCH)));
    new StableTaskOrder().sort(rows);
    assertEquals(1, rows.size());
  }
}
