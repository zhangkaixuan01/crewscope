package io.crewscope.application.execution;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Trusted Worker input for atomically consuming one runtime event. */
public record TaskRuntimeEventCommitCommand(
        TaskExecutionRuntimeFacts facts,
        TaskExecutionEvent event,
        UUID correlationId,
        Optional<UUID> causationId) {

    public TaskRuntimeEventCommitCommand {
        facts = Objects.requireNonNull(facts, "facts");
        event = Objects.requireNonNull(event, "event");
        correlationId = requireId(correlationId, "correlationId");
        causationId = Objects.requireNonNull(causationId, "causationId")
                .map(value -> requireId(value, "causationId"));
        if (!event.taskExecutionId().equals(facts.execution().id())
                || event.attempt() != facts.execution().attempt()
                || !event.agentRunId().equals(facts.agentRun().id())
                || event.segmentSequence() != facts.agentRun().currentSegment().sequence()) {
            throw new IllegalArgumentException(
                    "event must belong to the closed TaskExecutionRuntimeFacts Segment");
        }
    }

    private static UUID requireId(UUID value, String field) {
        UUID required = Objects.requireNonNull(value, field);
        if (AggregateId.NIL_UUID.equals(required)) {
            throw new IllegalArgumentException(field + " must not use the nil UUID");
        }
        return required;
    }
}
