package io.crewscope.application.coding.query;

import io.crewscope.application.coding.query.CodingAttemptProjection.ArtifactSummary;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Public-safe test and acceptance proof with ordered evidence references. */
public record TestEvidenceProjection(
        UUID id,
        long sequence,
        long diffGeneration,
        String diffManifestHash,
        long total,
        long passed,
        long failed,
        long errors,
        long skipped,
        String summary,
        String failureClassification,
        String evidenceHash,
        List<UUID> commandEvidenceIds,
        List<AcceptanceProjection> acceptance,
        Optional<ArtifactSummary> testReport,
        Instant createdAt) {

    public TestEvidenceProjection {
        commandEvidenceIds = List.copyOf(Objects.requireNonNull(commandEvidenceIds, "commandEvidenceIds"));
        acceptance = List.copyOf(Objects.requireNonNull(acceptance, "acceptance"));
        testReport = Objects.requireNonNull(testReport, "testReport");
    }

    public record AcceptanceProjection(
            int criterionIndex,
            String criterion,
            String status,
            String summary,
            List<UUID> commandEvidenceIds) {

        public AcceptanceProjection {
            commandEvidenceIds = List.copyOf(Objects.requireNonNull(commandEvidenceIds, "commandEvidenceIds"));
        }
    }
}
