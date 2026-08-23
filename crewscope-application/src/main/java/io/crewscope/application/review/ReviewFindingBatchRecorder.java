package io.crewscope.application.review;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.review.ContextPackage;
import io.crewscope.domain.review.ReviewFinding;
import io.crewscope.domain.review.ReviewFindingCandidate;
import io.crewscope.domain.review.ReviewFindingFingerprint;
import io.crewscope.domain.review.ReviewFindingId;
import io.crewscope.domain.review.ReviewFindingObservation;
import io.crewscope.domain.review.ReviewFindingObservationId;
import io.crewscope.domain.review.ReviewRequest;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Resolves model evidence and preserves only one first Finding per server fingerprint. */
public final class ReviewFindingBatchRecorder {

    private final ReviewFindingRepository findings;
    private final ReviewFindingObservationRepository observations;

    public ReviewFindingBatchRecorder(
            ReviewFindingRepository findings,
            ReviewFindingObservationRepository observations) {
        this.findings = Objects.requireNonNull(findings, "findings");
        this.observations = Objects.requireNonNull(observations, "observations");
    }

    public ReviewFindingBatchResult record(
            ReviewRequest request,
            ContextPackage context,
            List<ReviewFindingCandidate> candidates,
            long expectedRequestVersion,
            Principal reviewerAgent,
            UtcTimestamp observedAt) {
        ReviewRequest requiredRequest = Objects.requireNonNull(request, "request");
        ContextPackage requiredContext = Objects.requireNonNull(context, "context");
        List<ReviewFindingCandidate> requiredCandidates = List.copyOf(
                Objects.requireNonNull(candidates, "candidates"));
        if (requiredCandidates.size()
                > io.crewscope.application.review.output.ReviewFindingListV1.MAX_FINDINGS) {
            throw new IllegalArgumentException("Reviewer output exceeds the Finding budget");
        }

        var organizationId = requiredContext.scope().organizationId();
        Map<ReviewFindingFingerprint, ReviewFinding> effective = new LinkedHashMap<>();
        findings.findAllByRequest(organizationId, requiredRequest.id())
                .forEach(finding -> effective.put(finding.fingerprint(), finding));
        List<ReviewFinding> inserted = new ArrayList<>();
        List<ReviewFindingObservation> duplicates = new ArrayList<>();
        Map<ReviewFindingFingerprint, Long> nextObservation = new LinkedHashMap<>();

        for (ReviewFindingCandidate candidate : requiredCandidates) {
            ReviewFinding proposed = ReviewFinding.record(
                    ReviewFindingId.generate(),
                    requiredRequest,
                    requiredContext,
                    candidate,
                    expectedRequestVersion,
                    reviewerAgent,
                    observedAt);
            ReviewFinding existing = effective.get(proposed.fingerprint());
            if (existing == null) {
                existing = findings.findByRequestAndFingerprint(
                                organizationId, requiredRequest.id(), proposed.fingerprint())
                        .orElse(null);
            }
            if (existing == null) {
                ReviewFinding winner = findings.insertOrFind(proposed);
                if (winner.id().equals(proposed.id())) {
                    inserted.add(proposed);
                    effective.put(proposed.fingerprint(), proposed);
                    continue;
                }
                existing = winner;
            }
            ReviewFinding duplicateOf = existing;
            effective.putIfAbsent(duplicateOf.fingerprint(), duplicateOf);
            long number = nextObservation.computeIfAbsent(
                    duplicateOf.fingerprint(), ignored -> Math.addExact(
                            2L, observations.findAllByFinding(
                                    organizationId, duplicateOf.id()).size()));
            ReviewFindingObservation duplicate = ReviewFindingObservation.duplicate(
                    ReviewFindingObservationId.generate(),
                    number,
                    duplicateOf,
                    requiredRequest,
                    requiredContext,
                    candidate,
                    expectedRequestVersion,
                    reviewerAgent,
                    observedAt);
            ReviewFindingObservation committed = observations.append(duplicate);
            duplicates.add(committed);
            nextObservation.put(
                    duplicateOf.fingerprint(), Math.addExact(committed.observationNumber(), 1));
        }

        List<ReviewFinding> effectiveList = List.copyOf(effective.values());
        return new ReviewFindingBatchResult(
                effectiveList,
                inserted,
                duplicates,
                ReviewRepairRequestSummary.from(requiredRequest, requiredContext, effectiveList));
    }
}
