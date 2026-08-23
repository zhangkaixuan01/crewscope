package io.crewscope.application.review.output;

import io.crewscope.domain.review.ReviewFindingCandidate;
import java.util.List;
import java.util.Objects;

/** Complete Reviewer model output; an empty findings list is a successful clean review. */
public record ReviewFindingListV1(String schemaVersion, List<ReviewFindingItemV1> findings) {

    public static final String SCHEMA_VERSION = "1";
    public static final int MAX_FINDINGS = 50;

    public ReviewFindingListV1 {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("schemaVersion must equal 1");
        }
        findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
        if (findings.size() > MAX_FINDINGS || findings.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("findings must contain at most 50 non-null items");
        }
    }

    public List<ReviewFindingCandidate> toCandidates() {
        return findings.stream().map(ReviewFindingItemV1::toDomain).toList();
    }
}
