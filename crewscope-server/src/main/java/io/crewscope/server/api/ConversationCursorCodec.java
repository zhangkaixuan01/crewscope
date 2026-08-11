package io.crewscope.server.api;

import io.crewscope.application.conversation.ConversationListCursor;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.nio.ByteBuffer;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/** Versioned opaque codec for Conversation updated-time/ID keyset positions. */
public final class ConversationCursorCodec {

  private static final byte VERSION = 1;
  private static final int BINARY_SIZE = 1 + Long.BYTES + Integer.BYTES + 2 * Long.BYTES;
  private static final int MAX_TOKEN_LENGTH = 64;
  private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]+");

  public String encode(ConversationListCursor cursor) {
    ConversationListCursor source = Objects.requireNonNull(cursor, "cursor");
    Instant instant = source.updatedAt().value();
    UUID id = source.id().value();
    ByteBuffer buffer =
        ByteBuffer.allocate(BINARY_SIZE)
            .put(VERSION)
            .putLong(instant.getEpochSecond())
            .putInt(instant.getNano())
            .putLong(id.getMostSignificantBits())
            .putLong(id.getLeastSignificantBits());
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
  }

  public ConversationListCursor decode(String token) {
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
      Instant instant = Instant.ofEpochSecond(buffer.getLong(), buffer.getInt());
      UUID id = new UUID(buffer.getLong(), buffer.getLong());
      return new ConversationListCursor(
          UtcTimestamp.from(instant), new ConversationId(id));
    } catch (ApiRequestException exception) {
      throw exception;
    } catch (IllegalArgumentException | DateTimeException exception) {
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
