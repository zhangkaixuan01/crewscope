package io.crewscope.domain.review;

import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;

/** Exact immutable ContextPackage version and canonical Hash. */
public record ContextPackageReference(
        ContextPackageId id, long version, TaskFactHash contextHash) {

    public ContextPackageReference {
        id = Objects.requireNonNull(id, "id");
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        contextHash = Objects.requireNonNull(contextHash, "contextHash");
    }
}
