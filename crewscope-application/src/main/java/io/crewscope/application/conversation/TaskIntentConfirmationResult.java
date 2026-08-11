package io.crewscope.application.conversation;

import io.crewscope.domain.conversation.TaskIntent;
import io.crewscope.domain.workitem.WorkItemId;
import java.util.Objects;

/** Atomic result returned when M2-A07 confirms an Intent and creates its Native WorkItem. */
public record TaskIntentConfirmationResult(TaskIntent taskIntent, WorkItemId workItemId) {

  public TaskIntentConfirmationResult {
    taskIntent = Objects.requireNonNull(taskIntent, "taskIntent");
    workItemId = Objects.requireNonNull(workItemId, "workItemId");
  }
}
