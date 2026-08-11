package io.crewscope.domain.conversation.event;

import io.crewscope.domain.conversation.TaskIntent;
import io.crewscope.domain.conversation.TaskIntentStatus;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Version 1 fact emitted after a validated Agent proposal becomes READY for human review. */
public record TaskIntentProposed(
    UUID conversationId,
    int proposalRevision,
    UUID workProjectId,
    UUID ownerPrincipalId,
    Optional<UUID> executorPrincipalId,
    Optional<UUID> gateReviewerPrincipalId,
    TaskIntentStatus status)
    implements ConversationAssociatedEvent {

  public TaskIntentProposed {
    conversationId = Objects.requireNonNull(conversationId, "conversationId");
    if (proposalRevision < 1) {
      throw new IllegalArgumentException("proposalRevision must be positive");
    }
    workProjectId = Objects.requireNonNull(workProjectId, "workProjectId");
    ownerPrincipalId = Objects.requireNonNull(ownerPrincipalId, "ownerPrincipalId");
    executorPrincipalId = Objects.requireNonNull(executorPrincipalId, "executorPrincipalId");
    gateReviewerPrincipalId =
        Objects.requireNonNull(gateReviewerPrincipalId, "gateReviewerPrincipalId");
    status = Objects.requireNonNull(status, "status");
    if (status != TaskIntentStatus.READY) {
      throw new IllegalArgumentException("a proposed TaskIntent must be READY");
    }
  }

  public static TaskIntentProposed from(TaskIntent intent) {
    TaskIntent source = Objects.requireNonNull(intent, "intent");
    return new TaskIntentProposed(
        source.conversationId().value(),
        source.proposalRevision(),
        source.proposal().targetScope().projectId().value(),
        source.proposal().owner().principalId().value(),
        source.proposal().executor().map(value -> value.principalId().value()),
        source.proposal().gateReviewer().map(value -> value.principalId().value()),
        source.status());
  }
}
