package io.crewscope.application.review.output;

import io.crewscope.domain.review.FindingCategory;
import io.crewscope.domain.review.FindingSeverity;
import io.crewscope.domain.review.ReviewFindingCandidate;
import java.util.List;
import java.util.Objects;

/** Strict ReviewFindingListV1 item without model-controlled effect or Gate fields. */
public record ReviewFindingItemV1(
        String severity,
        String category,
        String title,
        String claim,
        String suggestedFix,
        List<ReviewFindingEvidenceV1> evidence) {

    public ReviewFindingItemV1 {
        severity = Objects.requireNonNull(severity, "severity");
        category = Objects.requireNonNull(category, "category");
        title = Objects.requireNonNull(title, "title");
        claim = Objects.requireNonNull(claim, "claim");
        suggestedFix = Objects.requireNonNull(suggestedFix, "suggestedFix");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
    }

    public ReviewFindingCandidate toDomain() {
        return new ReviewFindingCandidate(
                FindingSeverity.valueOf(severity),
                FindingCategory.valueOf(category),
                title,
                claim,
                suggestedFix,
                evidence.stream().map(ReviewFindingEvidenceV1::toDomain).toList());
    }
}
