package io.crewscope.domain.projection;

import java.util.Objects;

/** Hashed partition identity and bounded error code; raw partition keys and payloads stay private. */
public record ProjectionFailedPartition(
        ProjectionCanonicalHash partitionHash, ProjectionFailureCode failureCode) {

    public ProjectionFailedPartition {
        partitionHash = Objects.requireNonNull(partitionHash, "partitionHash");
        failureCode = Objects.requireNonNull(failureCode, "failureCode");
    }
}
