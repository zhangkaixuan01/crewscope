package io.crewscope.domain.projection;

import java.util.Objects;

/** Safe Dead Letter reference without raw DomainEvent payload or exception text. */
public record ProjectionDeadLetterReference(
        ProjectionDeadLetterId id,
        ProjectionGenerationKey generationKey,
        ProjectionCanonicalHash partitionHash,
        ProjectionFailureCode failureCode) {

    public ProjectionDeadLetterReference {
        id = Objects.requireNonNull(id, "id");
        generationKey = Objects.requireNonNull(generationKey, "generationKey");
        partitionHash = Objects.requireNonNull(partitionHash, "partitionHash");
        failureCode = Objects.requireNonNull(failureCode, "failureCode");
    }
}
