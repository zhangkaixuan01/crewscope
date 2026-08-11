package io.crewscope.application.conversation;

import io.crewscope.domain.conversation.TaskIntent;
import io.crewscope.domain.conversation.TaskIntentProposal;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Objects;

/** Current-fact confirmation evidence without creating a WorkItem or changing TaskIntent state. */
public record TaskIntentConfirmationPreview(
    TaskIntent taskIntent,
    TaskIntentProposal validatedProposal,
    PrincipalId confirmingPrincipalId) {

  public TaskIntentConfirmationPreview {
    taskIntent = Objects.requireNonNull(taskIntent, "taskIntent");
    validatedProposal = Objects.requireNonNull(validatedProposal, "validatedProposal");
    confirmingPrincipalId = Objects.requireNonNull(confirmingPrincipalId, "confirmingPrincipalId");
  }
}
