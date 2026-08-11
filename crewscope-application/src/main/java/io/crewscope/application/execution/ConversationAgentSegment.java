package io.crewscope.application.execution;

import io.crewscope.domain.shared.event.RealtimeEventEnvelope;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Flow;

/** One replayable invoke or resume segment exposed through the controlled SSE boundary. */
public record ConversationAgentSegment(
        RuntimeInvocationId invocationId,
        UUID segmentId,
        Flow.Publisher<RealtimeEventEnvelope<? extends AguiTransientPayload>> events,
        boolean replayed) {

    public ConversationAgentSegment {
        invocationId = Objects.requireNonNull(invocationId, "invocationId");
        segmentId = Objects.requireNonNull(segmentId, "segmentId");
        events = Objects.requireNonNull(events, "events");
    }
}
