package io.crewscope.domain.review;

import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;

/** Server-derived stable Finding identity; it is never accepted from model output. */
public record ReviewFindingFingerprint(TaskFactHash value) {

    public ReviewFindingFingerprint {
        value = Objects.requireNonNull(value, "value");
    }

    static ReviewFindingFingerprint calculate(
            ReviewSubjectReference subject,
            FindingCategory category,
            FindingLocation primaryLocation,
            String claim) {
        StringBuilder canonical = new StringBuilder("review-finding-fingerprint-v1");
        ReviewSubject.append(canonical, subject.subjectHash().toString());
        ReviewSubject.append(canonical, category.name());
        ReviewSubject.append(canonical, primaryLocation.path().value());
        ReviewSubject.append(canonical, Integer.toString(primaryLocation.startLine()));
        ReviewSubject.append(canonical, Integer.toString(primaryLocation.endLine()));
        ReviewSubject.append(canonical, ReviewTextPolicy.normalizeClaim(claim));
        return new ReviewFindingFingerprint(TaskFactHash.sha256(canonical.toString()));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
