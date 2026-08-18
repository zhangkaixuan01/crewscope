package io.crewscope.domain.coding;

import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;

/** Exact immutable identity and final Hash of one DiffArtifact. */
public record DiffArtifactReference(DiffArtifactId id, TaskFactHash finalHash) {

    public DiffArtifactReference {
        id = Objects.requireNonNull(id, "id");
        finalHash = Objects.requireNonNull(finalHash, "finalHash");
    }
}
