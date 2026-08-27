package io.crewscope.server.api;

import io.crewscope.application.activity.AuthorizedActivityPage;
import java.util.List;

/** Public keyset page whose continuation token represents the durable scanned position. */
public record ActivityPageResponse(
    List<ActivityResponse> items, boolean hasMore, String nextCursor) {

  static ActivityPageResponse from(
      AuthorizedActivityPage page, TeamActivityCursorCodec cursorCodec) {
    return new ActivityPageResponse(
        page.events().stream().map(ActivityResponse::from).toList(),
        page.hasMore(),
        page.nextCursor().map(cursorCodec::encode).orElse(null));
  }
}
