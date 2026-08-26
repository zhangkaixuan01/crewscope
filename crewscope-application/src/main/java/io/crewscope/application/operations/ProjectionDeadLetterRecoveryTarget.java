package io.crewscope.application.operations;

import io.crewscope.domain.projection.ProjectionDeadLetterId;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionName;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Full Projection Generation and DeadLetter coordinates for one bounded historical replay. */
public record ProjectionDeadLetterRecoveryTarget(
        ProjectionName projectionName,
        ProjectionGeneration generation,
        ProjectionDeadLetterId deadLetterId,
        UUID domainEventId,
        long expectedGenerationVersion) implements OperationsRecoveryTarget {

    public ProjectionDeadLetterRecoveryTarget {
        projectionName = Objects.requireNonNull(projectionName, "projectionName");
        generation = Objects.requireNonNull(generation, "generation");
        deadLetterId = Objects.requireNonNull(deadLetterId, "deadLetterId");
        domainEventId = Objects.requireNonNull(domainEventId, "domainEventId");
        if (expectedGenerationVersion < 0) {
            throw new IllegalArgumentException("expectedGenerationVersion must not be negative");
        }
    }

    @Override
    public OperationsRecoveryAction action() {
        return OperationsRecoveryAction.REPLAY_PROJECTION_DEAD_LETTER;
    }

    @Override
    public List<String> fingerprintCoordinates() {
        return List.of(
                action().name(),
                projectionName.value(),
                Long.toString(generation.value()),
                deadLetterId.value().toString(),
                domainEventId.toString(),
                Long.toString(expectedGenerationVersion));
    }

    @Override
    public String confirmationToken() {
        return projectionName.value() + ":" + generation.value() + ":"
                + deadLetterId.value() + ":" + expectedGenerationVersion;
    }
}
