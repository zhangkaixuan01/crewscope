package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.application.conversation.ConversationListCursor;
import io.crewscope.application.conversation.ConversationMessageCursor;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.MessageSequence;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Proves canonical transport encoding and Conversation-bound Message continuation positions. */
class ConversationCursorCodecTest {

  private final ConversationCursorCodec listCodec = new ConversationCursorCodec();
  private final ConversationMessageCursorCodec messageCodec =
      new ConversationMessageCursorCodec();

  @Test
  void roundTripsConversationListTimestampAndTieBreaker() {
    ConversationListCursor cursor =
        new ConversationListCursor(
            UtcTimestamp.parse("2026-08-10T09:10:11.123456Z"),
            ConversationId.from("01989ee2-f6b0-7cda-97c4-1b337043d410"));

    String token = listCodec.encode(cursor);

    assertEquals(cursor, listCodec.decode(token));
    assertEquals(token, listCodec.encode(listCodec.decode(token)));
  }

  @Test
  void roundTripsMessageCursorAndRejectsAnotherConversationRoute() {
    ConversationId conversationId = ConversationId.generate();
    ConversationMessageCursor cursor =
        new ConversationMessageCursor(conversationId, new MessageSequence(42));
    String token = messageCodec.encode(cursor);

    assertEquals(cursor, messageCodec.decode(token, conversationId));
    assertThrows(
        ApiRequestException.class,
        () -> messageCodec.decode(token, ConversationId.generate()));
  }

  @Test
  void rejectsMalformedWrongSizedAndUnknownVersionTokens() {
    assertThrows(ApiRequestException.class, () -> listCodec.decode("not*base64"));
    assertThrows(
        ApiRequestException.class,
        () -> messageCodec.decode("AQ", ConversationId.generate()));

    ConversationListCursor cursor =
        new ConversationListCursor(UtcTimestamp.parse("2026-08-10T09:00:00Z"), ConversationId.generate());
    byte[] unknownVersion = Base64.getUrlDecoder().decode(listCodec.encode(cursor));
    unknownVersion[0] = 2;
    assertThrows(
        ApiRequestException.class,
        () ->
            listCodec.decode(
                Base64.getUrlEncoder().withoutPadding().encodeToString(unknownVersion)));
  }
}
