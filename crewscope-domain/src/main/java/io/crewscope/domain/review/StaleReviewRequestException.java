package io.crewscope.domain.review;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainException;
import java.util.Map;
import java.util.Objects;

/** Fails closed when a Review command targets historical authority coordinates. */
public final class StaleReviewRequestException extends DomainException {

    private final ReviewInvalidationReason reason;

    public StaleReviewRequestException(ReviewInvalidationReason reason) {
        super(new DomainError(
                DomainErrorCode.INVALID_VALUE,
                "ReviewRequest authority coordinates are stale",
                Map.of("reason", Objects.requireNonNull(reason, "reason").name())));
        this.reason = reason;
    }

    public ReviewInvalidationReason reason() {
        return reason;
    }
}
