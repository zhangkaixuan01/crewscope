package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.List;
import java.util.Objects;

/** Ordered acceptance criterion verdict backed only by CommandEvidence references. */
public record AcceptanceResult(
        int criterionIndex,
        String criterion,
        AcceptanceStatus status,
        List<CommandEvidenceReference> evidence,
        EvidenceSummary summary) {

    public AcceptanceResult {
        if (criterionIndex < 1) {
            throw new DomainValidationException(
                    "acceptanceResult.criterionIndex", "must be positive");
        }
        if (criterion == null || criterion.isBlank() || criterion.length() > 2_000) {
            throw new DomainValidationException(
                    "acceptanceResult.criterion", "must be non-blank and bounded");
        }
        criterion = criterion.strip();
        status = Objects.requireNonNull(status, "status");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if ((status == AcceptanceStatus.NOT_EVALUATED) != evidence.isEmpty()) {
            throw new DomainValidationException(
                    "acceptanceResult.evidence",
                    "evaluated criteria require evidence and unevaluated criteria must not claim it");
        }
        if (evidence.stream().distinct().count() != evidence.size()) {
            throw new DomainValidationException(
                    "acceptanceResult.evidence", "must not contain duplicate references");
        }
        summary = Objects.requireNonNull(summary, "summary");
    }
}
