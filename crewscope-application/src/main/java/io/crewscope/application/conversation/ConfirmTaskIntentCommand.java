package io.crewscope.application.conversation;

/** Optimistic version expected by the future atomic WorkItem confirmation command. */
public record ConfirmTaskIntentCommand(long expectedVersion) {

  public ConfirmTaskIntentCommand {
    if (expectedVersion < 0) {
      throw new IllegalArgumentException("expectedVersion must not be negative");
    }
  }
}
