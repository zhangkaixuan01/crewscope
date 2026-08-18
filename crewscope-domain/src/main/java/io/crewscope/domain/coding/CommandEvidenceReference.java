package io.crewscope.domain.coding;

import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;
import java.util.Optional;

/** Exact immutable command evidence identity, order and integrity Hash. */
public record CommandEvidenceReference(
        CommandEvidenceId id,
        EvidenceSequence sequence,
        TaskFactHash evidenceHash,
        Optional<EvidenceFailureClassification> failureClassification) {

    public CommandEvidenceReference {
        id = Objects.requireNonNull(id, "id");
        sequence = Objects.requireNonNull(sequence, "sequence");
        evidenceHash = Objects.requireNonNull(evidenceHash, "evidenceHash");
        failureClassification = Objects.requireNonNull(
                failureClassification, "failureClassification");
    }

    public boolean succeeded() {
        return failureClassification.isEmpty();
    }
}
