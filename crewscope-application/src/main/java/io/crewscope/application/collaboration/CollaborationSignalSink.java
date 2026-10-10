package io.crewscope.application.collaboration;

import io.crewscope.domain.collaboration.CollaborationResourceChanged;

/**
 * Outbound port for real-time collaboration signals (M11-A01, ADR-032). The application
 * layer maps domain events onto {@link CollaborationResourceChanged} facts and pushes them
 * here; the transport (the WebSocket fan-out in the server module) implements the port.
 *
 * <p>Implementations are invoked from inside the outbox receipt transaction on the outbox
 * dispatcher's fixed pool, so a conforming implementation must be non-blocking, must never
 * throw (a throw would roll back the receipt of every consumer of that event) and may drop
 * signals freely — signals are lossy by contract and the authoritative read stays on
 * REST/SSE.
 */
public interface CollaborationSignalSink {

    /** Fans one resource-change signal out to the audiences it names; never throws. */
    void resourceChanged(CollaborationResourceChanged change);
}
