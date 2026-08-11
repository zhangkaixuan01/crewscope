package io.crewscope.server.api;

import io.crewscope.application.conversation.ConversationMessageCursor;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.MessageSequence;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/** Versioned opaque codec whose decoded Message position remains bound to its Conversation. */
public final class ConversationMessageCursorCodec {

  private static final byte VERSION = 1;
  private static final int BINARY_SIZE = 1 + 2 * Long.BYTES + Long.BYTES;
  private static final int MAX_TOKEN_LENGTH = 64;
  private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]+");

  public String encode(ConversationMessageCursor cursor) {
    ConversationMessageCursor source = Objects.requireNonNull(cursor, "cursor");
    UUID conversationId = source.conversationId().value();
    ByteBuffer buffer =
        ByteBuffer.allocate(BINARY_SIZE)
            .put(VERSION)
            .putLong(conversationId.getMostSignificantBits())
            .putLong(conversationId.getLeastSignificantBits())
            .putLong(source.sequence().value());
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
  }

  public ConversationMessageCursor decode(String token, ConversationId expectedConversationId) {
    if (!validToken(token)) {
      throw invalidCursor();
    }
    try {
      byte[] bytes = Base64.getUrlDecoder().decode(token);
      if (bytes.length != BINARY_SIZE || !canonical(bytes).equals(token)) {
        throw invalidCursor();
      }
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      if (buffer.get() != VERSION) {
        throw invalidCursor();
      }
      ConversationMessageCursor cursor =
          new ConversationMessageCursor(
              new ConversationId(new UUID(buffer.getLong(), buffer.getLong())),
              new MessageSequence(buffer.getLong()));
      return cursor.requireConversation(
          Objects.requireNonNull(expectedConversationId, "expectedConversationId"));
    } catch (ApiRequestException exception) {
      throw exception;
    } catch (IllegalArgumentException exception) {
      throw invalidCursor();
    }
  }

  private static boolean validToken(String token) {
    return token != null
        && !token.isBlank()
        && token.length() <= MAX_TOKEN_LENGTH
        && TOKEN_FORMAT.matcher(token).matches();
  }

  private static String canonical(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static ApiRequestException invalidCursor() {
    return new ApiRequestException(
        HttpStatus.BAD_REQUEST,
        "invalid_cursor",
        "Cursor is invalid or unsupported",
        Map.of("parameter", "after"));
  }
}
