package io.crewscope.application.teamobserver;

import java.util.Objects;
import java.util.concurrent.Flow;

/** Replayable finite event stream for an initial Team Observer call or SSE resume. */
public record TeamObserverInvocationSegment(
        TeamObserverSessionId sessionId,
        TeamObserverInvocationId invocationId,
        Flow.Publisher<TeamObserverStreamEvent> events,
        boolean resumed) {

    public TeamObserverInvocationSegment {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        invocationId = Objects.requireNonNull(invocationId, "invocationId");
        events = Objects.requireNonNull(events, "events");
    }
}
