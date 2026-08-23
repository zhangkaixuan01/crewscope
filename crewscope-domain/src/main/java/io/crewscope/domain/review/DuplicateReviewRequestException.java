package io.crewscope.domain.review;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainException;
import java.util.Map;

/** Rejects creation of another active request for the same exact ContextPackage. */
public final class DuplicateReviewRequestException extends DomainException {

    public DuplicateReviewRequestException() {
        super(new DomainError(
                DomainErrorCode.INVALID_VALUE,
                "An active ReviewRequest already exists for this ContextPackage",
                Map.of("field", "reviewRequest.contextPackage")));
    }
}
