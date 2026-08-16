package io.crewscope.evaluation;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** Produces the deterministic ordering used by a Task queue projection. */
public final class StableTaskOrder {

  public List<TaskRow> sort(List<TaskRow> rows) {
    return rows.stream().sorted(Comparator.comparingInt(TaskRow::priority).reversed()).toList();
  }

  public record TaskRow(String id, int priority, Instant createdAt) {}
}
