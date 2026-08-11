package io.crewscope.domain.conversation.event;

import io.crewscope.domain.conversation.TaskIntent;
import io.crewscope.domain.conversation.TaskIntentStatus;
import java.util.Objects;
import java.util.UUID;

/** Version 1 fact emitted after a complete replacement proposal is validated and marked READY. */
public record TaskIntentRevised(
    UUID conversationId, int previousProposalRevision, int proposalRevision, TaskIntentStatus status)
    implements ConversationAssociatedEvent {

  public TaskIntentRevised {
    conversationId = Objects.requireNonNull(conversationId, "conversationId");
    if (previousProposalRevision < 1 || proposalRevision != previousProposalRevision + 1) {
      throw new IllegalArgumentException("proposal revisions must advance exactly once");
    }
    status = Objects.requireNonNull(status, "status");
    if (status != TaskIntentStatus.READY) {
      throw new IllegalArgumentException("a revised TaskIntent must be READY");
    }
  }

  public static TaskIntentRevised from(TaskIntent before, TaskIntent after) {
    TaskIntent previous = Objects.requireNonNull(before, "before");
    TaskIntent current = Objects.requireNonNull(after, "after");
    if (!previous.id().equals(current.id())
        || !previous.scope().equals(current.scope())
        || !previous.conversationId().equals(current.conversationId())) {
      throw new IllegalArgumentException("before and after must describe the same TaskIntent");
    }
    return new TaskIntentRevised(
        current.conversationId().value(),
        previous.proposalRevision(),
        current.proposalRevision(),
        current.status());
  }
}
