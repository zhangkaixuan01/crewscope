package io.crewscope.server.api;

import io.crewscope.application.activity.TeamActivitySnapshot;
import java.util.Objects;
import java.util.Optional;

/** Snapshot plus its opaque signed high-water position. */
public record TeamActivitySnapshotEnvelope(
    TeamActivitySnapshot snapshot, Optional<String> snapshotCursor) {

  public TeamActivitySnapshotEnvelope {
    snapshot = Objects.requireNonNull(snapshot, "snapshot");
    snapshotCursor = Objects.requireNonNull(snapshotCursor, "snapshotCursor");
    if (snapshot.snapshotCursor().isPresent() != snapshotCursor.isPresent()) {
      throw new IllegalArgumentException("Encoded snapshot cursor presence must match the snapshot");
    }
  }
}
