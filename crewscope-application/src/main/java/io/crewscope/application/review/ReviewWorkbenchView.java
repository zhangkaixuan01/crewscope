package io.crewscope.application.review;

import io.crewscope.domain.review.ContextPackage;
import io.crewscope.domain.review.ReviewDecision;
import io.crewscope.domain.review.ReviewFinding;
import io.crewscope.domain.review.ReviewModificationRound;
import io.crewscope.domain.review.ReviewRequest;
import java.util.List;
import java.util.Objects;

/** Authorized Review workbench facts; transport adapters still apply a strict public DTO. */
public record ReviewWorkbenchView(
        ReviewRequest request,
        ContextPackage contextPackage,
        List<ReviewFinding> findings,
        List<ReviewDecision> decisions,
        List<ReviewModificationRound> modificationRounds) {

    public ReviewWorkbenchView {
        request = Objects.requireNonNull(request, "request");
        contextPackage = Objects.requireNonNull(contextPackage, "contextPackage");
        findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
        decisions = List.copyOf(Objects.requireNonNull(decisions, "decisions"));
        modificationRounds = List.copyOf(Objects.requireNonNull(
                modificationRounds, "modificationRounds"));
        if (!request.contextPackage().equals(contextPackage.reference())) {
            throw new IllegalArgumentException(
                    "Review workbench ContextPackage must match the request authority");
        }
    }
}
