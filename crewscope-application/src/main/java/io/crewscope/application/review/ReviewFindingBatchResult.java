package io.crewscope.application.review;

import io.crewscope.domain.review.ReviewFinding;
import io.crewscope.domain.review.ReviewFindingObservation;
import java.util.List;
import java.util.Objects;

/** Result of resolving and deduplicating one complete Reviewer model output. */
public record ReviewFindingBatchResult(
        List<ReviewFinding> effectiveFindings,
        List<ReviewFinding> insertedFindings,
        List<ReviewFindingObservation> duplicateObservations,
        ReviewRepairRequestSummary repairSummary) {

    public ReviewFindingBatchResult {
        effectiveFindings = List.copyOf(Objects.requireNonNull(
                effectiveFindings, "effectiveFindings"));
        insertedFindings = List.copyOf(Objects.requireNonNull(
                insertedFindings, "insertedFindings"));
        duplicateObservations = List.copyOf(Objects.requireNonNull(
                duplicateObservations, "duplicateObservations"));
        repairSummary = Objects.requireNonNull(repairSummary, "repairSummary");
    }
}
