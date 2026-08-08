package io.crewscope.application.workitem;

import io.crewscope.domain.workitem.WorkItemStatus;
import java.util.Objects;

/** One expected-version WorkItem state transition. */
public record TransitionWorkItemCommand(WorkItemStatus targetStatus, long expectedVersion) {

  public TransitionWorkItemCommand {
    targetStatus = Objects.requireNonNull(targetStatus, "targetStatus");
    if (expectedVersion < 0) {
      throw new IllegalArgumentException("expectedVersion must not be negative");
    }
  }
}
