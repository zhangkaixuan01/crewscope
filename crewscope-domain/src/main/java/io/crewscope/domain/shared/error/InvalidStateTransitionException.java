package io.crewscope.domain.shared.error;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.Map;

/** Reports a rejected aggregate state transition with stable current and target state details. */
public final class InvalidStateTransitionException extends DomainException {

    public InvalidStateTransitionException(
            String aggregateType,
            AggregateId aggregateId,
            Enum<?> currentState,
            Enum<?> targetState) {
        super(new DomainError(
                DomainErrorCode.INVALID_STATE_TRANSITION,
                "%s %s cannot transition from %s to %s"
                        .formatted(aggregateType, aggregateId, currentState, targetState),
                Map.of(
                        "aggregateType", requireText(aggregateType, "aggregateType"),
                        "aggregateId", aggregateId.toString(),
                        "currentState", currentState.name(),
                        "targetState", targetState.name())));
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }
}
