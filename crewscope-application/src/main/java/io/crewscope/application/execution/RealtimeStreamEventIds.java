package io.crewscope.application.execution;

import io.crewscope.domain.shared.event.StreamType;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

/** Derives a stable stream-specific Event ID from one persisted DomainEvent ID. */
public final class RealtimeStreamEventIds {

  private static final String NAMESPACE = "CREWSCOPE:REALTIME:";

  private RealtimeStreamEventIds() {}

  public static UUID forDomain(StreamType streamType, UUID domainEventId) {
    StreamType requiredStream = Objects.requireNonNull(streamType, "streamType");
    if (requiredStream == StreamType.AG_UI) {
      throw new IllegalArgumentException("AG-UI transient events need their own event IDs");
    }
    UUID requiredDomainEventId = Objects.requireNonNull(domainEventId, "domainEventId");
    try {
      byte[] digest =
          MessageDigest.getInstance("MD5")
              .digest(
                  (NAMESPACE + requiredStream.name() + ":" + requiredDomainEventId)
                      .getBytes(StandardCharsets.UTF_8));
      ByteBuffer bytes = ByteBuffer.wrap(digest);
      return new UUID(bytes.getLong(), bytes.getLong());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("JVM does not provide MD5", exception);
    }
  }
}
