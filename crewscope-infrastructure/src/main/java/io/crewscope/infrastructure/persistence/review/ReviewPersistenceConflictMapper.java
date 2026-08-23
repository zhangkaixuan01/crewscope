package io.crewscope.infrastructure.persistence.review;

import io.crewscope.domain.review.DuplicateReviewRequestException;
import java.sql.SQLException;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;

/** Converts Review uniqueness boundaries into stable domain failures without leaking SQL details. */
final class ReviewPersistenceConflictMapper {

    private ReviewPersistenceConflictMapper() {}

    static RuntimeException reviewRequest(DataIntegrityViolationException failure) {
        if (hasConstraint(failure, "uk_review_request_revision")
                || hasConstraint(failure, "uk_review_request_reference")) {
            return new DuplicateReviewRequestException();
        }
        return failure;
    }

    private static boolean hasConstraint(Throwable failure, String constraintName) {
        String expected = constraintName.toLowerCase(Locale.ROOT);
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql
                    && "23505".equals(sql.getSQLState())
                    && current.getMessage() != null
                    && current.getMessage().toLowerCase(Locale.ROOT).contains(expected)) {
                return true;
            }
        }
        return false;
    }
}
