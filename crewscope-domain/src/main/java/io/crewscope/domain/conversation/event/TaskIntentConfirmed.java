package io.crewscope.domain.conversation.event;

import io.crewscope.domain.conversation.TaskIntent;
import io.crewscope.domain.conversation.TaskIntentStatus;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemKey;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Complete fact emitted after one TaskIntent atomically creates its native WorkItem graph. */
public record TaskIntentConfirmed(
    UUID conversationId,
    UUID workItemId,
    String workItemKey,
    UUID providerBindingId,
    UUID ownerAssignmentId,
    Optional<UUID> executorAssignmentId,
    Optional<UUID> gateReviewerAssignmentId,
    TaskIntentStatus status)
    implements ConversationAssociatedEvent {

  public TaskIntentConfirmed {
    conversationId = Objects.requireNonNull(conversationId, "conversationId");
    workItemId = Objects.requireNonNull(workItemId, "workItemId");
    workItemKey = new WorkItemKey(workItemKey).value();
    providerBindingId = Objects.requireNonNull(providerBindingId, "providerBindingId");
    ownerAssignmentId = Objects.requireNonNull(ownerAssignmentId, "ownerAssignmentId");
    executorAssignmentId =
        Objects.requireNonNull(executorAssignmentId, "executorAssignmentId");
    gateReviewerAssignmentId =
        Objects.requireNonNull(gateReviewerAssignmentId, "gateReviewerAssignmentId");
    status = Objects.requireNonNull(status, "status");
    if (status != TaskIntentStatus.CONFIRMED) {
      throw new IllegalArgumentException("a confirmed TaskIntent must be CONFIRMED");
    }
  }

  public static TaskIntentConfirmed from(
      TaskIntent intent,
      WorkItem workItem,
      ProviderBindingId providerBindingId,
      ResponsibilityAssignment owner,
      Optional<ResponsibilityAssignment> executor,
      Optional<ResponsibilityAssignment> gateReviewer) {
    TaskIntent confirmed = Objects.requireNonNull(intent, "intent");
    WorkItem item = Objects.requireNonNull(workItem, "workItem");
    return new TaskIntentConfirmed(
        confirmed.conversationId().value(),
        item.id().value(),
        item.key().value(),
        Objects.requireNonNull(providerBindingId, "providerBindingId").value(),
        Objects.requireNonNull(owner, "owner").id().value(),
        Objects.requireNonNull(executor, "executor").map(value -> value.id().value()),
        Objects.requireNonNull(gateReviewer, "gateReviewer").map(value -> value.id().value()),
        confirmed.status());
  }
}
