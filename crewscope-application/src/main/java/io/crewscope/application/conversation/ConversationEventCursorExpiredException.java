package io.crewscope.application.conversation;

/** Indicates that a once-valid durable stream position is no longer retained. */
public final class ConversationEventCursorExpiredException extends RuntimeException {

  public ConversationEventCursorExpiredException() {
    super("Conversation Event cursor is no longer retained");
  }
}
