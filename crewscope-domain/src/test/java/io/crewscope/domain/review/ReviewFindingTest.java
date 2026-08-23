package io.crewscope.domain.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.review.event.ReviewFindingDuplicateObserved;
import io.crewscope.domain.review.event.ReviewFindingRecorded;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.task.TaskFactHash;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReviewFindingTest {

    @Test
    void recordsOnlyResolvedAgentAdvisoryEvidenceAndPublishesSafeEvent() {
        ReviewDomainFixture fixture = new ReviewDomainFixture();
        ReviewRequest running = runningRequest(fixture, fixture.context);
        ReviewFinding finding = ReviewFinding.record(
                ReviewFindingId.generate(),
                running,
                fixture.context,
                candidate(fixture, fixture.context, "Null input causes a failure"),
                1,
                fixture.reviewerAgent,
                ReviewDomainFixture.LATER);

        assertEquals(ReviewerMode.ADVISORY, finding.reviewerMode());
        assertEquals(ReviewerRelationship.INDEPENDENT, finding.reviewerRelationship());
        assertEquals(fixture.reviewerPrincipalId, finding.reviewerPrincipalId());
        assertEquals(1, finding.evidence().size());
        assertEquals(14, finding.evidence().get(0).location().startLine());

        ReviewFindingRecorded event = ReviewFindingRecorded.from(finding);
        assertEquals(finding.id().value(), event.findingId());
        assertEquals(fixture.taskId.value(), event.taskId());
        assertEquals(finding.fingerprint().toString(), event.fingerprint());

        assertThrows(DomainValidationException.class, () -> ReviewFinding.reconstitute(
                finding.id(),
                finding.scope(),
                finding.reviewRequest(),
                ReviewerMode.GATE,
                finding.reviewerRelationship(),
                finding.reviewerPrincipalId(),
                candidate(fixture, fixture.context, finding.claim()),
                finding.evidence(),
                finding.fingerprint(),
                finding.candidateHash(),
                finding.audit()));
    }

    @Test
    void rejectsForgedHashUnknownAcceptanceOutOfHunkAndWrongActor() {
        ReviewDomainFixture fixture = new ReviewDomainFixture();
        ReviewRequest running = runningRequest(fixture, fixture.context);
        FindingEvidence valid = evidence(fixture.context, 14, 14, 1);
        FindingEvidence wrongHash = new FindingEvidence(
                valid.location(),
                valid.diffArtifact(),
                valid.diffManifestHash(),
                valid.testEvidenceId(),
                TaskFactHash.sha256("forged-test"),
                1);
        FindingEvidence unknownAcceptance = new FindingEvidence(
                valid.location(),
                valid.diffArtifact(),
                valid.diffManifestHash(),
                valid.testEvidenceId(),
                valid.testEvidenceHash(),
                99);
        FindingEvidence outsideHunk = evidence(fixture.context, 15, 15, 1);

        for (FindingEvidence invalid : List.of(wrongHash, unknownAcceptance, outsideHunk)) {
            assertThrows(DomainValidationException.class, () -> ReviewFinding.record(
                    ReviewFindingId.generate(),
                    running,
                    fixture.context,
                    candidate("Invalid evidence", List.of(invalid)),
                    1,
                    fixture.reviewerAgent,
                    ReviewDomainFixture.LATER));
        }

        assertThrows(DomainValidationException.class, () -> ReviewFinding.record(
                ReviewFindingId.generate(),
                running,
                fixture.context,
                candidate(fixture, fixture.context, "Wrong actor"),
                1,
                fixture.actor,
                ReviewDomainFixture.LATER));
    }

    @Test
    void normalizesFingerprintAndPreservesLaterDuplicateAsObservation() {
        ReviewDomainFixture fixture = new ReviewDomainFixture();
        ReviewRequest running = runningRequest(fixture, fixture.context);
        ReviewFinding first = ReviewFinding.record(
                ReviewFindingId.generate(),
                running,
                fixture.context,
                candidate(fixture, fixture.context, "  NULL   input causes a FAILURE  "),
                1,
                fixture.reviewerAgent,
                ReviewDomainFixture.LATER);
        ReviewFindingCandidate duplicate = new ReviewFindingCandidate(
                FindingSeverity.BLOCKER,
                FindingCategory.CORRECTNESS,
                "Different title",
                "null input causes a failure",
                "Use an early guard instead",
                List.of(evidence(fixture.context, 14, 14, 1)));
        ReviewFindingObservation observation = ReviewFindingObservation.duplicate(
                ReviewFindingObservationId.generate(),
                2,
                first,
                running,
                fixture.context,
                duplicate,
                1,
                fixture.reviewerAgent,
                ReviewDomainFixture.LATER);

        assertEquals(first.fingerprint(), observation.finding().fingerprint());
        assertNotEquals(first.candidateHash(), observation.candidateHash());
        assertEquals(2, observation.observationNumber());
        assertEquals(
                observation.id().value(),
                ReviewFindingDuplicateObserved.from(observation).observationId());

        ReviewFindingCandidate differentCategory = new ReviewFindingCandidate(
                FindingSeverity.HIGH,
                FindingCategory.SECURITY,
                "Different identity",
                "null input causes a failure",
                "Fix it",
                List.of(evidence(fixture.context, 14, 14, 1)));
        assertThrows(DomainValidationException.class, () -> ReviewFindingObservation.duplicate(
                ReviewFindingObservationId.generate(),
                3,
                first,
                running,
                fixture.context,
                differentCategory,
                1,
                fixture.reviewerAgent,
                ReviewDomainFixture.LATER));
    }

    @Test
    void selfReviewRemainsAdvisoryAndCompletedOrStaleRequestsRejectFindings() {
        ReviewDomainFixture fixture = new ReviewDomainFixture();
        ReviewerExecutionReference selfReviewer = new ReviewerExecutionReference(
                fixture.reviewer.scope(),
                fixture.reviewer.taskId(),
                fixture.reviewer.taskExecutionId(),
                fixture.reviewer.agentProfileId(),
                fixture.reviewer.agentProfileVersion(),
                fixture.reviewer.agentPrincipalId(),
                Optional.of(fixture.subjectOwner),
                Optional.of(fixture.subjectOwner),
                ReviewerRelationship.SELF_REVIEW,
                fixture.reviewer.templateVersion(),
                fixture.reviewer.templateHash(),
                fixture.reviewer.configurationRevision(),
                fixture.reviewer.configurationHash(),
                fixture.reviewer.policySnapshotId(),
                fixture.reviewer.policySnapshotRevision(),
                fixture.reviewer.policySnapshotHash());
        ContextPackage selfContext = fixture.context(
                ContextPackageId.generate(),
                fixture.subject,
                fixture.diff,
                fixture.testEvidence,
                selfReviewer);
        ReviewRequest running = runningRequest(fixture, selfContext);
        ReviewFinding selfFinding = ReviewFinding.record(
                ReviewFindingId.generate(),
                running,
                selfContext,
                candidate(fixture, selfContext, "Self review issue"),
                1,
                fixture.reviewerAgent,
                ReviewDomainFixture.LATER);
        assertEquals(ReviewerRelationship.SELF_REVIEW, selfFinding.reviewerRelationship());
        assertEquals(ReviewerMode.ADVISORY, selfFinding.reviewerMode());

        ReviewRequest completed = running.complete(
                selfContext, 1, fixture.actor, ReviewDomainFixture.LATER);
        assertThrows(DomainValidationException.class, () -> ReviewFinding.record(
                ReviewFindingId.generate(),
                completed,
                selfContext,
                candidate(fixture, selfContext, "Late finding"),
                2,
                fixture.reviewerAgent,
                ReviewDomainFixture.LATER));

        ContextPackage changed = fixture.successor(
                selfContext,
                fixture.subject,
                fixture.diff,
                fixture.testEvidence,
                selfReviewer,
                "+return name.strip();\n");
        assertThrows(StaleReviewRequestException.class, () -> ReviewFinding.record(
                ReviewFindingId.generate(),
                running,
                changed,
                candidate(fixture, changed, "Stale finding"),
                1,
                fixture.reviewerAgent,
                ReviewDomainFixture.LATER));
    }

    private static ReviewRequest runningRequest(
            ReviewDomainFixture fixture, ContextPackage context) {
        return ReviewRequest.initial(
                        ReviewRequestId.generate(),
                        context,
                        fixture.actor,
                        ReviewDomainFixture.CREATED_AT)
                .start(context, 0, fixture.actor, ReviewDomainFixture.LATER);
    }

    private static ReviewFindingCandidate candidate(
            ReviewDomainFixture fixture, ContextPackage context, String claim) {
        return candidate(claim, List.of(evidence(context, 14, 14, 1)));
    }

    private static ReviewFindingCandidate candidate(
            String claim, List<FindingEvidence> evidence) {
        return new ReviewFindingCandidate(
                FindingSeverity.HIGH,
                FindingCategory.CORRECTNESS,
                "Null handling can fail",
                claim,
                "Guard the input before stripping it",
                evidence);
    }

    private static FindingEvidence evidence(
            ContextPackage context, int startLine, int endLine, int acceptanceIndex) {
        return new FindingEvidence(
                new FindingLocation(context.hunks().get(0).path(), startLine, endLine),
                context.diff().artifact(),
                context.diff().manifestHash(),
                context.testEvidence().id(),
                context.testEvidence().evidenceHash(),
                acceptanceIndex);
    }
}
