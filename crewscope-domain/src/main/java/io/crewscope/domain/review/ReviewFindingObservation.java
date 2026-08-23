package io.crewscope.domain.review;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;

/** Append-only audit fact for a candidate merged into an existing Finding fingerprint. */
public record ReviewFindingObservation(
        ReviewFindingObservationId id,
        ReviewFindingReference finding,
        long observationNumber,
        TaskFactHash candidateHash,
        PrincipalId reviewerPrincipalId,
        AuditMetadata audit) {

    public ReviewFindingObservation {
        id = Objects.requireNonNull(id, "id");
        finding = Objects.requireNonNull(finding, "finding");
        if (observationNumber < 2) {
            throw new DomainValidationException(
                    "reviewFindingObservation.observationNumber",
                    "must start at two because the Finding stores the first observation");
        }
        candidateHash = Objects.requireNonNull(candidateHash, "candidateHash");
        reviewerPrincipalId = Objects.requireNonNull(
                reviewerPrincipalId, "reviewerPrincipalId");
        audit = Objects.requireNonNull(audit, "audit");
    }

    /** Verifies current authority and preserves a later duplicate without replacing first facts. */
    public static ReviewFindingObservation duplicate(
            ReviewFindingObservationId id,
            long observationNumber,
            ReviewFinding existing,
            ReviewRequest request,
            ContextPackage context,
            ReviewFindingCandidate candidate,
            long expectedRequestVersion,
            Principal reviewerAgent,
            UtcTimestamp observedAt) {
        ReviewFinding first = Objects.requireNonNull(existing, "existing");
        ResolvedFindingCandidate resolved = ReviewFinding.resolve(
                request, context, candidate, expectedRequestVersion, reviewerAgent);
        ReviewFindingFingerprint fingerprint = ReviewFindingFingerprint.calculate(
                resolved.request().subject(),
                resolved.candidate().category(),
                resolved.evidence().get(0).location(),
                resolved.candidate().claim());
        if (!first.reviewRequest().equals(ReviewRequestReference.from(resolved.request()))
                || !first.fingerprint().equals(fingerprint)) {
            throw new DomainValidationException(
                    "reviewFindingObservation.fingerprint",
                    "must identify a duplicate within the same ReviewRequest");
        }
        return new ReviewFindingObservation(
                id,
                first.reference(),
                observationNumber,
                resolved.candidate().candidateHash(resolved.evidence()),
                resolved.reviewerPrincipalId(),
                AuditMetadata.createdBy(resolved.reviewerPrincipalId(), observedAt));
    }
}
