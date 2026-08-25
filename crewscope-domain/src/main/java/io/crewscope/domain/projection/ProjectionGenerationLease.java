package io.crewscope.domain.projection;

import java.util.Objects;

/** Generation and fencing token captured by a Worker before one transactional projection write. */
public record ProjectionGenerationLease(
        ProjectionGenerationKey key, ProjectionFencingToken fencingToken) {

    public ProjectionGenerationLease {
        key = Objects.requireNonNull(key, "key");
        fencingToken = Objects.requireNonNull(fencingToken, "fencingToken");
    }
}
