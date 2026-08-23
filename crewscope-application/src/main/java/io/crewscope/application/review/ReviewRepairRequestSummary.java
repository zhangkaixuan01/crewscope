package io.crewscope.application.review;

import io.crewscope.domain.review.ContextPackage;
import io.crewscope.domain.review.ReviewFinding;
import io.crewscope.domain.review.ReviewRequest;
import io.crewscope.domain.review.ReviewerRelationship;
import io.crewscope.domain.task.TaskFactHash;
import java.util.List;
import java.util.Objects;

/** Bounded, non-authoritative Finding summary that may be handed to a later Coding repair round. */
public record ReviewRepairRequestSummary(
        String reviewRequestId,
        long reviewRevision,
        TaskFactHash contextHash,
        ReviewerRelationship reviewerRelationship,
        boolean truncated,
        List<RepairFinding> findings) {

    public static final int MAX_FINDINGS = 20;
    private static final int MAX_CLAIM = 320;
    private static final int MAX_FIX = 320;

    public ReviewRepairRequestSummary {
        reviewRequestId = Objects.requireNonNull(reviewRequestId, "reviewRequestId");
        contextHash = Objects.requireNonNull(contextHash, "contextHash");
        reviewerRelationship = Objects.requireNonNull(
                reviewerRelationship, "reviewerRelationship");
        findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
        if (findings.size() > MAX_FINDINGS) {
            throw new IllegalArgumentException("repair summary exceeds its Finding budget");
        }
    }

    public static ReviewRepairRequestSummary from(
            ReviewRequest request, ContextPackage context, List<ReviewFinding> source) {
        ReviewRequest requiredRequest = Objects.requireNonNull(request, "request");
        ContextPackage requiredContext = Objects.requireNonNull(context, "context");
        List<ReviewFinding> ordered = List.copyOf(Objects.requireNonNull(source, "source"));
        List<RepairFinding> bounded = ordered.stream()
                .limit(MAX_FINDINGS)
                .map(finding -> new RepairFinding(
                        finding.fingerprint().toString(),
                        finding.severity().name(),
                        finding.category().name(),
                        finding.title(),
                        text(finding.claim(), MAX_CLAIM),
                        text(finding.suggestedFix(), MAX_FIX),
                        finding.evidence().get(0).location().path().value(),
                        finding.evidence().get(0).location().startLine(),
                        finding.evidence().get(0).location().endLine()))
                .toList();
        return new ReviewRepairRequestSummary(
                requiredRequest.id().toString(),
                requiredRequest.revision(),
                requiredContext.contextHash(),
                requiredContext.reviewer().relationship(),
                ordered.size() > MAX_FINDINGS,
                bounded);
    }

    private static String text(String value, int maximum) {
        String required = Objects.requireNonNull(value, "value");
        return required.length() <= maximum ? required : required.substring(0, maximum - 1) + "…";
    }

    /** One actionable location; evidence authority remains in the ReviewFinding itself. */
    public record RepairFinding(
            String fingerprint,
            String severity,
            String category,
            String title,
            String claim,
            String suggestedFix,
            String canonicalPath,
            int startLine,
            int endLine) {

        public RepairFinding {
            Objects.requireNonNull(fingerprint, "fingerprint");
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(claim, "claim");
            Objects.requireNonNull(suggestedFix, "suggestedFix");
            Objects.requireNonNull(canonicalPath, "canonicalPath");
        }
    }
}
