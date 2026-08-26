package io.crewscope.server.api;

import io.crewscope.application.activity.ActivityCursorScope;
import java.util.Objects;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/** Preflighted single-subscriber SSE body bound to one immutable Team cursor scope. */
public record TeamActivitySseSession(
    ActivityCursorScope cursorScope,
    Flux<ServerSentEvent<TeamActivityStreamEvent>> body) {

  public TeamActivitySseSession {
    cursorScope = Objects.requireNonNull(cursorScope, "cursorScope");
    body = Objects.requireNonNull(body, "body");
  }
}
