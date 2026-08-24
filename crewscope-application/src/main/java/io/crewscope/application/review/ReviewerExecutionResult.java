package io.crewscope.application.review;

import io.crewscope.application.command.CommandReceipt;
import io.crewscope.domain.review.ReviewRequest;
import java.util.Objects;
import java.util.Optional;

/** Completed or replayed Reviewer command result. */
public record ReviewerExecutionResult(
        ReviewRequest request,
        Optional<ReviewFindingBatchResult> findings,
        CommandReceipt receipt,
        boolean replayed) {

    public ReviewerExecutionResult {
        request = Objects.requireNonNull(request, "request");
        findings = Objects.requireNonNull(findings, "findings");
        receipt = Objects.requireNonNull(receipt, "receipt");
    }
}
