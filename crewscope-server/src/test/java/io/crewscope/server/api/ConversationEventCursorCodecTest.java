package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.application.conversation.ConversationEventCursor;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Proves canonical encoding and complete Conversation route binding. */
class ConversationEventCursorCodecTest {

  private final ConversationEventCursorCodec codec = new ConversationEventCursorCodec();
  private final OrganizationId organizationId = OrganizationId.generate();
  private final TeamId teamId = TeamId.generate();
  private final ConversationId conversationId = ConversationId.generate();

  @Test
  void roundTripsCanonicalCursor() {
    ConversationEventCursor cursor =
        new ConversationEventCursor(
            organizationId, teamId, conversationId, 42, UUID.randomUUID());

    String token = codec.encode(cursor);

    assertEquals(
        cursor, codec.decode(token, organizationId, teamId, conversationId));
  }

  @Test
  void rejectsMalformedAndCrossConversationTokens() {
    ConversationEventCursor cursor =
        new ConversationEventCursor(
            organizationId, teamId, conversationId, 1, UUID.randomUUID());
    String token = codec.encode(cursor);

    assertThrows(
        ApiRequestException.class,
        () -> codec.decode(token + "=", organizationId, teamId, conversationId));
    assertThrows(
        ApiRequestException.class,
        () -> codec.decode(token, organizationId, teamId, ConversationId.generate()));
  }
}
