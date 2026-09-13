package io.crewscope.application.workdesk;

import java.util.List;
import java.util.Objects;

/** One prioritized WorkDesk section with an explicit truncation signal. */
public record WorkDeskSection(
    String key, String title, int priority, int total, boolean truncated, List<WorkDeskItem> items) {

  public WorkDeskSection {
    if (key == null || key.isBlank() || title == null || title.isBlank()) {
      throw new IllegalArgumentException("WorkDesk section labels must not be blank");
    }
    if (priority < 1 || total < 0) throw new IllegalArgumentException("invalid WorkDesk section counters");
    items = List.copyOf(Objects.requireNonNull(items, "items"));
    if (items.size() > total) throw new IllegalArgumentException("section items exceed total");
  }
}
