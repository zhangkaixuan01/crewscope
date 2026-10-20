package io.crewscope.server.security.session;

import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;

/**
 * One enumerated browser Session with the server-side timestamps the owner may see. Identifiers of
 * other accounts never reach this type: enumeration is always scoped to one account.
 */
public record BrowserSessionSnapshot(
    String sessionId, Instant createdAt, Instant lastAccessedAt, String device) {

  public BrowserSessionSnapshot {
    sessionId = Objects.requireNonNull(sessionId, "sessionId");
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    lastAccessedAt = Objects.requireNonNull(lastAccessedAt, "lastAccessedAt");
    // Nullable by design: Sessions established before M13b-A02 (or from an unrecognizable
    // User-Agent) carry no summary, and callers render their unknown-device fallback.
  }

  /** Most recently accessed first, matching how an owner reviews active devices. */
  public static Comparator<BrowserSessionSnapshot> byMostRecentAccess() {
    return Comparator.comparing(BrowserSessionSnapshot::lastAccessedAt).reversed();
  }
}
