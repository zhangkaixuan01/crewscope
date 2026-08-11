package io.crewscope.application.conversation;

import io.crewscope.domain.conversation.TaskIntentDecision;
import io.crewscope.domain.shared.error.DomainValidationException;

/** User-visible reason for permanently rejecting an editable TaskIntent. */
public record RejectTaskIntentCommand(String reason) {

  public RejectTaskIntentCommand {
    if (reason == null || reason.isBlank()) {
      throw new DomainValidationException("taskIntentDecision.reason", "must not be blank");
    }
    reason = reason.strip();
    if (reason.length() > TaskIntentDecision.MAX_REASON_LENGTH) {
      throw new DomainValidationException(
          "taskIntentDecision.reason",
          "must contain at most " + TaskIntentDecision.MAX_REASON_LENGTH + " characters");
    }
  }
}
