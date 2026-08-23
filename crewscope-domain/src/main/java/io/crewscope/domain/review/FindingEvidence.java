package io.crewscope.domain.review;

import io.crewscope.domain.coding.DiffArtifactReference;
import io.crewscope.domain.coding.TestEvidenceId;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;

/** Model-supplied evidence coordinates that must resolve inside the current ContextPackage. */
public record FindingEvidence(
        FindingLocation location,
        DiffArtifactReference diffArtifact,
        RuntimeContentHash diffManifestHash,
        TestEvidenceId testEvidenceId,
        TaskFactHash testEvidenceHash,
        int acceptanceCriterionIndex) implements Comparable<FindingEvidence> {

    public FindingEvidence {
        location = Objects.requireNonNull(location, "location");
        diffArtifact = Objects.requireNonNull(diffArtifact, "diffArtifact");
        diffManifestHash = Objects.requireNonNull(diffManifestHash, "diffManifestHash");
        testEvidenceId = Objects.requireNonNull(testEvidenceId, "testEvidenceId");
        testEvidenceHash = Objects.requireNonNull(testEvidenceHash, "testEvidenceHash");
        if (acceptanceCriterionIndex < 1) {
            throw new DomainValidationException(
                    "reviewFinding.evidence.acceptanceCriterionIndex", "must be positive");
        }
    }

    @Override
    public int compareTo(FindingEvidence other) {
        FindingEvidence required = Objects.requireNonNull(other, "other");
        int locationOrder = location.compareTo(required.location);
        if (locationOrder != 0) {
            return locationOrder;
        }
        return Integer.compare(acceptanceCriterionIndex, required.acceptanceCriterionIndex);
    }
}
