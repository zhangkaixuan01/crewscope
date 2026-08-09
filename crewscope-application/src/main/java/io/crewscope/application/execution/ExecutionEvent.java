package io.crewscope.application.execution;

import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** One ordered event in a finite invoke or resume stream. */
public record ExecutionEvent(
        RuntimeInvocationId invocationId,
        long sequence,
        UtcTimestamp occurredAt,
        ExecutionEventPayload payload) {

    public ExecutionEvent {
        invocationId = Objects.requireNonNull(invocationId, "invocationId");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        payload = Objects.requireNonNull(payload, "payload");
    }

    public boolean terminal() {
        return payload.terminalStatus().isPresent();
    }
}
