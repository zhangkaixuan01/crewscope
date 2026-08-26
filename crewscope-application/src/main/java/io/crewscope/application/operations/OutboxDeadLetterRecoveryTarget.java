package io.crewscope.application.operations;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Exact Outbox row and DomainEvent binding used for a fenced dead-letter replay. */
public record OutboxDeadLetterRecoveryTarget(
        UUID outboxEventId,
        UUID domainEventId,
        long expectedVersion) implements OperationsRecoveryTarget {

    public OutboxDeadLetterRecoveryTarget {
        outboxEventId = Objects.requireNonNull(outboxEventId, "outboxEventId");
        domainEventId = Objects.requireNonNull(domainEventId, "domainEventId");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
    }

    @Override
    public OperationsRecoveryAction action() {
        return OperationsRecoveryAction.REPLAY_OUTBOX_DEAD_LETTER;
    }

    @Override
    public List<String> fingerprintCoordinates() {
        return List.of(action().name(), outboxEventId.toString(), domainEventId.toString(),
                Long.toString(expectedVersion));
    }

    @Override
    public String confirmationToken() {
        return outboxEventId + ":" + expectedVersion;
    }
}
