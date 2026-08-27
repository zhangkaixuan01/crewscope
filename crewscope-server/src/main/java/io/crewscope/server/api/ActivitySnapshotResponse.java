package io.crewscope.server.api;

import io.crewscope.application.activity.AuthorizedActivitySnapshot;
import java.util.List;

/** Public snapshot with separate history continuation and realtime high-water cursors. */
public record ActivitySnapshotResponse(
    List<ActivityResponse> items,
    boolean hasMore,
    String nextCursor,
    String snapshotCursor) {

  static ActivitySnapshotResponse from(
      AuthorizedActivitySnapshot snapshot, TeamActivityCursorCodec cursorCodec) {
    return new ActivitySnapshotResponse(
        snapshot.events().stream().map(ActivityResponse::from).toList(),
        snapshot.hasMore(),
        snapshot.nextCursor().map(cursorCodec::encode).orElse(null),
        snapshot.snapshotCursor().map(cursorCodec::encode).orElse(null));
  }
}
