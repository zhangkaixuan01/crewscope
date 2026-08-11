package io.crewscope.application.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.shared.event.StreamType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Locks down stable single-stream IDs and cross-stream DomainEvent correlation. */
class RealtimeStreamEventIdsTest {

  @Test
  void derivesStableDistinctIdsForConversationAndTeamStreams() {
    UUID domainEventId = UUID.fromString("0198f024-8bf8-7f3d-bc92-8db6d25d4250");

    UUID conversation =
        RealtimeStreamEventIds.forDomain(StreamType.CONVERSATION, domainEventId);

    assertEquals(
        conversation,
        RealtimeStreamEventIds.forDomain(StreamType.CONVERSATION, domainEventId));
    assertNotEquals(
        conversation, RealtimeStreamEventIds.forDomain(StreamType.TEAM, domainEventId));
    assertNotEquals(domainEventId, conversation);
    assertThrows(
        IllegalArgumentException.class,
        () -> RealtimeStreamEventIds.forDomain(StreamType.AG_UI, domainEventId));
  }
}
