package io.crewscope.application.execution;

import io.crewscope.domain.shared.event.RealtimeEventEnvelope;
import java.util.Objects;
import java.util.Optional;

/** Output produced by accepting one ordered runtime event at the Conversation boundary. */
public record ExecutionEventMappingResult(
        Optional<RealtimeEventEnvelope<? extends AguiTransientPayload>> transientEvent,
        Optional<AgentMessageCandidate> messageCandidate,
        Optional<TaskIntentOutputCandidate> taskIntentCandidate,
        boolean duplicate) {

    public ExecutionEventMappingResult {
        transientEvent = Objects.requireNonNull(transientEvent, "transientEvent");
        messageCandidate = Objects.requireNonNull(messageCandidate, "messageCandidate");
        taskIntentCandidate = Objects.requireNonNull(taskIntentCandidate, "taskIntentCandidate");
        if (duplicate
                && (transientEvent.isPresent()
                        || messageCandidate.isPresent()
                        || taskIntentCandidate.isPresent())) {
            throw new IllegalArgumentException("duplicate mappings cannot repeat side effects");
        }
    }

    public static ExecutionEventMappingResult duplicateEvent() {
        return new ExecutionEventMappingResult(
                Optional.empty(), Optional.empty(), Optional.empty(), true);
    }
}
