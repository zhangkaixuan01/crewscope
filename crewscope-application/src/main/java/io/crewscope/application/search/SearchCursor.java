package io.crewscope.application.search;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Keyset position shared by all search object types. */
public record SearchCursor(Instant updatedAt, UUID objectId) {
  public SearchCursor {
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    objectId = Objects.requireNonNull(objectId, "objectId");
  }
}
