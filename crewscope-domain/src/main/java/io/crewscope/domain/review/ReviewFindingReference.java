package io.crewscope.domain.review;

import java.util.Objects;

/** Stable Finding identity and fingerprint exposed to duplicate observations. */
public record ReviewFindingReference(
        ReviewFindingId id,
        ReviewRequestReference reviewRequest,
        ReviewFindingFingerprint fingerprint) {

    public ReviewFindingReference {
        id = Objects.requireNonNull(id, "id");
        reviewRequest = Objects.requireNonNull(reviewRequest, "reviewRequest");
        fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
    }
}
