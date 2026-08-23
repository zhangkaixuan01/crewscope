package io.crewscope.application.review.output;

import io.crewscope.domain.coding.DiffArtifactId;
import io.crewscope.domain.coding.DiffArtifactReference;
import io.crewscope.domain.coding.DiffPath;
import io.crewscope.domain.coding.TestEvidenceId;
import io.crewscope.domain.review.FindingEvidence;
import io.crewscope.domain.review.FindingLocation;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;

/** Untrusted ReviewFindingListV1 evidence DTO converted into domain value objects after decoding. */
public record ReviewFindingEvidenceV1(
        String canonicalPath,
        int startLine,
        int endLine,
        String diffArtifactId,
        String diffArtifactHash,
        String diffManifestHash,
        String testEvidenceId,
        String testEvidenceHash,
        int acceptanceCriterionIndex) {

    public ReviewFindingEvidenceV1 {
        canonicalPath = Objects.requireNonNull(canonicalPath, "canonicalPath");
        diffArtifactId = Objects.requireNonNull(diffArtifactId, "diffArtifactId");
        diffArtifactHash = Objects.requireNonNull(diffArtifactHash, "diffArtifactHash");
        diffManifestHash = Objects.requireNonNull(diffManifestHash, "diffManifestHash");
        testEvidenceId = Objects.requireNonNull(testEvidenceId, "testEvidenceId");
        testEvidenceHash = Objects.requireNonNull(testEvidenceHash, "testEvidenceHash");
    }

    public FindingEvidence toDomain() {
        return new FindingEvidence(
                new FindingLocation(new DiffPath(canonicalPath), startLine, endLine),
                new DiffArtifactReference(
                        DiffArtifactId.from(diffArtifactId), new TaskFactHash(diffArtifactHash)),
                new RuntimeContentHash(diffManifestHash),
                TestEvidenceId.from(testEvidenceId),
                new TaskFactHash(testEvidenceHash),
                acceptanceCriterionIndex);
    }
}
