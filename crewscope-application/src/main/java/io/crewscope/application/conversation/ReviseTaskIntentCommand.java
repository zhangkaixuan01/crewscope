package io.crewscope.application.conversation;

import java.util.Objects;

/** Complete replacement of one editable TaskIntent proposal. */
public record ReviseTaskIntentCommand(TaskIntentV1 proposal) {

  public ReviseTaskIntentCommand {
    proposal = Objects.requireNonNull(proposal, "proposal");
  }
}
