package io.crewscope.domain.review;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.task.TaskFactHash;
import java.util.List;
import java.util.Objects;

/** Strict ReviewFindingListV1 item before server-side evidence resolution and fingerprinting. */
public record ReviewFindingCandidate(
        FindingSeverity severity,
        FindingCategory category,
        String title,
        String claim,
        String suggestedFix,
        List<FindingEvidence> evidence) {

    public static final int MAX_TITLE_LENGTH = 200;
    public static final int MAX_CLAIM_LENGTH = 4_000;
    public static final int MAX_SUGGESTED_FIX_LENGTH = 4_000;
    public static final int MAX_EVIDENCE = 8;

    public ReviewFindingCandidate {
        severity = Objects.requireNonNull(severity, "severity");
        category = Objects.requireNonNull(category, "category");
        title = ReviewTextPolicy.requireText(title, "reviewFinding.title", MAX_TITLE_LENGTH);
        claim = ReviewTextPolicy.requireText(claim, "reviewFinding.claim", MAX_CLAIM_LENGTH);
        suggestedFix = ReviewTextPolicy.requireText(
                suggestedFix, "reviewFinding.suggestedFix", MAX_SUGGESTED_FIX_LENGTH);
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.isEmpty() || evidence.size() > MAX_EVIDENCE) {
            throw new DomainValidationException(
                    "reviewFinding.evidence", "must contain 1 to 8 evidence coordinates");
        }
        if (evidence.stream().anyMatch(Objects::isNull)
                || evidence.stream().distinct().count() != evidence.size()) {
            throw new DomainValidationException(
                    "reviewFinding.evidence", "must contain unique non-null coordinates");
        }
    }

    TaskFactHash candidateHash(List<FindingEvidence> resolvedEvidence) {
        StringBuilder canonical = new StringBuilder("review-finding-candidate-v1");
        ReviewSubject.append(canonical, severity.name());
        ReviewSubject.append(canonical, category.name());
        ReviewSubject.append(canonical, title);
        ReviewSubject.append(canonical, claim);
        ReviewSubject.append(canonical, suggestedFix);
        for (FindingEvidence item : resolvedEvidence) {
            ReviewSubject.append(canonical, item.location().path().value());
            ReviewSubject.append(canonical, Integer.toString(item.location().startLine()));
            ReviewSubject.append(canonical, Integer.toString(item.location().endLine()));
            ReviewSubject.append(canonical, item.diffArtifact().id().toString());
            ReviewSubject.append(canonical, item.diffArtifact().finalHash().toString());
            ReviewSubject.append(canonical, item.diffManifestHash().toString());
            ReviewSubject.append(canonical, item.testEvidenceId().toString());
            ReviewSubject.append(canonical, item.testEvidenceHash().toString());
            ReviewSubject.append(canonical, Integer.toString(item.acceptanceCriterionIndex()));
        }
        return TaskFactHash.sha256(canonical.toString());
    }
}
