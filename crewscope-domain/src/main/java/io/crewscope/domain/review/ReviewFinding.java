package io.crewscope.domain.review;

import io.crewscope.domain.coding.AcceptanceResult;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** First immutable Agent advisory for one server-derived Finding fingerprint. */
public final class ReviewFinding {

    private final ReviewFindingId id;
    private final WorkItemScope scope;
    private final ReviewRequestReference reviewRequest;
    private final ReviewerMode reviewerMode;
    private final ReviewerRelationship reviewerRelationship;
    private final PrincipalId reviewerPrincipalId;
    private final FindingSeverity severity;
    private final FindingCategory category;
    private final String title;
    private final String claim;
    private final String suggestedFix;
    private final List<FindingEvidence> evidence;
    private final ReviewFindingFingerprint fingerprint;
    private final TaskFactHash candidateHash;
    private final AuditMetadata audit;

    private ReviewFinding(
            ReviewFindingId id,
            WorkItemScope scope,
            ReviewRequestReference reviewRequest,
            ReviewerMode reviewerMode,
            ReviewerRelationship reviewerRelationship,
            PrincipalId reviewerPrincipalId,
            ReviewFindingCandidate candidate,
            List<FindingEvidence> resolvedEvidence,
            Optional<ReviewFindingFingerprint> expectedFingerprint,
            Optional<TaskFactHash> expectedCandidateHash,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.reviewRequest = Objects.requireNonNull(reviewRequest, "reviewRequest");
        if (!this.scope.equals(this.reviewRequest.scope())) {
            throw new DomainValidationException(
                    "reviewFinding.reviewRequest", "must belong to the Finding scope");
        }
        this.reviewerMode = Objects.requireNonNull(reviewerMode, "reviewerMode");
        if (this.reviewerMode != ReviewerMode.ADVISORY) {
            throw new DomainValidationException(
                    "reviewFinding.reviewerMode", "Agent Findings are always ADVISORY");
        }
        this.reviewerRelationship = Objects.requireNonNull(
                reviewerRelationship, "reviewerRelationship");
        this.reviewerPrincipalId = Objects.requireNonNull(
                reviewerPrincipalId, "reviewerPrincipalId");
        ReviewFindingCandidate requiredCandidate = Objects.requireNonNull(candidate, "candidate");
        this.severity = requiredCandidate.severity();
        this.category = requiredCandidate.category();
        this.title = requiredCandidate.title();
        this.claim = requiredCandidate.claim();
        this.suggestedFix = requiredCandidate.suggestedFix();
        this.evidence = List.copyOf(Objects.requireNonNull(resolvedEvidence, "resolvedEvidence"));
        if (this.evidence.isEmpty()) {
            throw new DomainValidationException(
                    "reviewFinding.evidence", "must contain resolved ContextPackage evidence");
        }
        this.fingerprint = ReviewFindingFingerprint.calculate(
                this.reviewRequest.subject(), this.category, this.evidence.get(0).location(), this.claim);
        Objects.requireNonNull(expectedFingerprint, "expectedFingerprint").ifPresent(expected -> {
            if (!expected.equals(this.fingerprint)) {
                throw new DomainValidationException(
                        "reviewFinding.fingerprint", "must match the server-derived identity");
            }
        });
        this.candidateHash = requiredCandidate.candidateHash(this.evidence);
        Objects.requireNonNull(expectedCandidateHash, "expectedCandidateHash").ifPresent(expected -> {
            if (!expected.equals(this.candidateHash)) {
                throw new DomainValidationException(
                        "reviewFinding.candidateHash", "must match the first resolved observation");
            }
        });
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** Resolves and records one model candidate while the exact ReviewRequest is in progress. */
    public static ReviewFinding record(
            ReviewFindingId id,
            ReviewRequest request,
            ContextPackage context,
            ReviewFindingCandidate candidate,
            long expectedRequestVersion,
            Principal reviewerAgent,
            UtcTimestamp observedAt) {
        ResolvedFindingCandidate resolved = resolve(
                request, context, candidate, expectedRequestVersion, reviewerAgent);
        return new ReviewFinding(
                id,
                resolved.context().scope(),
                ReviewRequestReference.from(resolved.request()),
                ReviewerMode.ADVISORY,
                resolved.context().reviewer().relationship(),
                resolved.reviewerPrincipalId(),
                resolved.candidate(),
                resolved.evidence(),
                Optional.empty(),
                Optional.empty(),
                AuditMetadata.createdBy(resolved.reviewerPrincipalId(), observedAt));
    }

    /** Reconstitutes the first observation and rejects a forged fingerprint or candidate hash. */
    public static ReviewFinding reconstitute(
            ReviewFindingId id,
            WorkItemScope scope,
            ReviewRequestReference reviewRequest,
            ReviewerMode reviewerMode,
            ReviewerRelationship reviewerRelationship,
            PrincipalId reviewerPrincipalId,
            ReviewFindingCandidate candidate,
            List<FindingEvidence> resolvedEvidence,
            ReviewFindingFingerprint fingerprint,
            TaskFactHash candidateHash,
            AuditMetadata audit) {
        List<FindingEvidence> ordered = requireOrderedEvidence(resolvedEvidence);
        return new ReviewFinding(
                id,
                scope,
                reviewRequest,
                reviewerMode,
                reviewerRelationship,
                reviewerPrincipalId,
                candidate,
                ordered,
                Optional.of(Objects.requireNonNull(fingerprint, "fingerprint")),
                Optional.of(Objects.requireNonNull(candidateHash, "candidateHash")),
                audit);
    }

    static ResolvedFindingCandidate resolve(
            ReviewRequest request,
            ContextPackage context,
            ReviewFindingCandidate candidate,
            long expectedRequestVersion,
            Principal reviewerAgent) {
        ReviewRequest requiredRequest = Objects.requireNonNull(request, "request");
        ContextPackage requiredContext = Objects.requireNonNull(context, "context");
        requiredRequest.requireVersion(expectedRequestVersion);
        requiredRequest.requireCurrent(requiredContext);
        if (requiredRequest.status() != ReviewRequestStatus.IN_PROGRESS) {
            throw new DomainValidationException(
                    "reviewFinding.reviewRequest", "must reference an IN_PROGRESS request");
        }
        Principal requiredReviewer = Objects.requireNonNull(reviewerAgent, "reviewerAgent");
        PrincipalId actorId = ReviewActorPolicy.requireActiveInScope(
                requiredReviewer, requiredContext.scope(), "reviewFinding.reviewerPrincipalId");
        if (requiredReviewer.type() != PrincipalType.SPECIALIST_AGENT
                || !actorId.equals(requiredContext.reviewer().agentPrincipalId())) {
            throw new DomainValidationException(
                    "reviewFinding.reviewerPrincipalId",
                    "must be the exact active Reviewer Specialist Agent");
        }
        ReviewFindingCandidate requiredCandidate = Objects.requireNonNull(candidate, "candidate");
        List<FindingEvidence> resolvedEvidence = resolveEvidence(requiredCandidate, requiredContext);
        return new ResolvedFindingCandidate(
                requiredRequest, requiredContext, requiredCandidate, resolvedEvidence, actorId);
    }

    private static List<FindingEvidence> resolveEvidence(
            ReviewFindingCandidate candidate, ContextPackage context) {
        Set<Integer> acceptanceIndexes = context.testEvidence().acceptanceResults().stream()
                .map(AcceptanceResult::criterionIndex)
                .collect(Collectors.toUnmodifiableSet());
        for (FindingEvidence item : candidate.evidence()) {
            boolean exactAuthority = item.diffArtifact().equals(context.diff().artifact())
                    && item.diffManifestHash().equals(context.diff().manifestHash())
                    && item.testEvidenceId().equals(context.testEvidence().id())
                    && item.testEvidenceHash().equals(context.testEvidence().evidenceHash())
                    && acceptanceIndexes.contains(item.acceptanceCriterionIndex());
            boolean insideHunk = context.hunks().stream().anyMatch(hunk ->
                    hunk.path().equals(item.location().path())
                            && hunk.startLine() <= item.location().startLine()
                            && hunk.endLine() >= item.location().endLine());
            if (!exactAuthority || !insideHunk) {
                throw new DomainValidationException(
                        "reviewFinding.evidence",
                        "must resolve to the current Diff, Hunk, TestEvidence and AcceptanceResult");
            }
        }
        return requireOrderedEvidence(candidate.evidence());
    }

    private static List<FindingEvidence> requireOrderedEvidence(List<FindingEvidence> values) {
        List<FindingEvidence> required = List.copyOf(
                Objects.requireNonNull(values, "resolvedEvidence"));
        if (required.isEmpty()
                || required.size() > ReviewFindingCandidate.MAX_EVIDENCE
                || required.stream().anyMatch(Objects::isNull)
                || required.stream().distinct().count() != required.size()) {
            throw new DomainValidationException(
                    "reviewFinding.evidence", "must contain 1 to 8 unique resolved coordinates");
        }
        return required.stream().sorted(Comparator.naturalOrder()).toList();
    }

    public ReviewFindingReference reference() {
        return new ReviewFindingReference(id, reviewRequest, fingerprint);
    }

    public ReviewFindingId id() { return id; }
    public WorkItemScope scope() { return scope; }
    public ReviewRequestReference reviewRequest() { return reviewRequest; }
    public ReviewerMode reviewerMode() { return reviewerMode; }
    public ReviewerRelationship reviewerRelationship() { return reviewerRelationship; }
    public PrincipalId reviewerPrincipalId() { return reviewerPrincipalId; }
    public FindingSeverity severity() { return severity; }
    public FindingCategory category() { return category; }
    public String title() { return title; }
    public String claim() { return claim; }
    public String suggestedFix() { return suggestedFix; }
    public List<FindingEvidence> evidence() { return evidence; }
    public ReviewFindingFingerprint fingerprint() { return fingerprint; }
    public TaskFactHash candidateHash() { return candidateHash; }
    public AuditMetadata audit() { return audit; }
}

record ResolvedFindingCandidate(
        ReviewRequest request,
        ContextPackage context,
        ReviewFindingCandidate candidate,
        List<FindingEvidence> evidence,
        PrincipalId reviewerPrincipalId) {}
