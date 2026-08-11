package io.crewscope.server.api;

import io.crewscope.application.conversation.ConversationEventCursor;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/** Canonical opaque codec binding one durable position to its complete Conversation route. */
public final class ConversationEventCursorCodec {

  private static final byte VERSION = 1;
  private static final int BINARY_SIZE = 1 + (4 * 2 * Long.BYTES) + Long.BYTES;
  private static final int MAX_TOKEN_LENGTH = 128;
  private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]+");

  public String encode(ConversationEventCursor cursor) {
    ConversationEventCursor source = Objects.requireNonNull(cursor, "cursor");
    ByteBuffer buffer = ByteBuffer.allocate(BINARY_SIZE).put(VERSION);
    putUuid(buffer, source.organizationId().value());
    putUuid(buffer, source.teamId().value());
    putUuid(buffer, source.conversationId().value());
    buffer.putLong(source.position());
    putUuid(buffer, source.eventId());
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
  }

  public ConversationEventCursor decode(
      String token,
      OrganizationId expectedOrganizationId,
      TeamId expectedTeamId,
      ConversationId expectedConversationId) {
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
      ConversationEventCursor cursor =
          new ConversationEventCursor(
              new OrganizationId(readUuid(buffer)),
              new TeamId(readUuid(buffer)),
              new ConversationId(readUuid(buffer)),
              buffer.getLong(),
              readUuid(buffer));
      return cursor.requireStream(
          expectedOrganizationId, expectedTeamId, expectedConversationId);
    } catch (ApiRequestException exception) {
      throw exception;
    } catch (IllegalArgumentException exception) {
      throw invalidCursor();
    }
  }

  private static void putUuid(ByteBuffer buffer, UUID value) {
    buffer.putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits());
  }

  private static UUID readUuid(ByteBuffer buffer) {
    return new UUID(buffer.getLong(), buffer.getLong());
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
        "Cursor is invalid or belongs to another Conversation stream",
        Map.of("parameter", "after"));
  }
}
