package io.crewscope.domain.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import org.junit.jupiter.api.Test;

class ReviewRequestTest {

    @Test
    void enforcesOpenInProgressCompletedStateMachineAndOptimisticVersion() {
        ReviewDomainFixture fixture = new ReviewDomainFixture();
        ReviewRequest open = ReviewRequest.initial(
                ReviewRequestId.generate(), fixture.context, fixture.actor, ReviewDomainFixture.CREATED_AT);

        assertEquals(ReviewRequestStatus.OPEN, open.status());
        assertEquals(0, open.version());
        assertThrows(OptimisticLockConflictException.class,
                () -> open.start(fixture.context, 1, fixture.actor, ReviewDomainFixture.LATER));

        ReviewRequest running = open.start(
                fixture.context, 0, fixture.actor, ReviewDomainFixture.LATER);
        ReviewRequest completed = running.complete(
                fixture.context, 1, fixture.actor, ReviewDomainFixture.LATER);
        assertEquals(ReviewRequestStatus.COMPLETED, completed.status());
        assertEquals(2, completed.version());
        assertEquals(open.requestHash(), completed.requestHash());
        assertThrows(DomainValidationException.class,
                () -> completed.complete(
                        fixture.context, 2, fixture.actor, ReviewDomainFixture.LATER));
    }

    @Test
    void rejectsDuplicateActiveRequestAndRequiresInvalidatedPredecessor() {
        ReviewDomainFixture fixture = new ReviewDomainFixture();
        ReviewRequest current = ReviewRequest.initial(
                ReviewRequestId.generate(), fixture.context, fixture.actor, ReviewDomainFixture.CREATED_AT);

        assertThrows(DuplicateReviewRequestException.class, () -> ReviewRequest.successor(
                ReviewRequestId.generate(), current, fixture.context, fixture.actor, ReviewDomainFixture.LATER));

        ContextPackage changed = fixture.successor(
                fixture.context,
                fixture.subject,
                fixture.diff,
                fixture.testEvidence,
                fixture.reviewer,
                "+return name == null ? \"\" : name.trim();\n");
        assertThrows(StaleReviewRequestException.class,
                () -> current.start(changed, 0, fixture.actor, ReviewDomainFixture.LATER));
        assertThrows(DomainValidationException.class, () -> ReviewRequest.successor(
                ReviewRequestId.generate(), current, changed, fixture.actor, ReviewDomainFixture.LATER));

        ReviewRequest invalidated = current.invalidate(
                changed, 0, fixture.actor, ReviewDomainFixture.LATER);
        ReviewRequest successor = ReviewRequest.successor(
                ReviewRequestId.generate(), invalidated, changed, fixture.actor, ReviewDomainFixture.LATER);
        assertEquals(2, successor.revision());
        assertEquals(current.id(), successor.predecessorRequestId().orElseThrow());
    }

    @Test
    void invalidatesAndRejectsStaleDiffAndEvidence() {
        ReviewDomainFixture fixture = new ReviewDomainFixture();
        ReviewRequest request = ReviewRequest.initial(
                ReviewRequestId.generate(), fixture.context, fixture.actor, ReviewDomainFixture.CREATED_AT);

        ReviewDiffReference changedDiff = fixture.diff("diff-2", 2);
        ReviewSubject changedSubject = fixture.subject(ReviewSubjectId.generate(), changedDiff);
        ReviewTestEvidenceReference matchingEvidence = fixture.test("test-2", changedDiff, fixture.command);
        ContextPackage diffContext = fixture.successor(
                fixture.context,
                changedSubject,
                changedDiff,
                matchingEvidence,
                fixture.reviewer,
                "+return name.strip();\n");

        assertEquals(ReviewInvalidationReason.DIFF_CHANGED, request.staleReason(diffContext).orElseThrow());
        assertThrows(StaleReviewRequestException.class, () -> request.requireCurrent(diffContext));
        ReviewRequest invalidated = request.invalidate(
                diffContext, 0, fixture.actor, ReviewDomainFixture.LATER);
        assertEquals(ReviewRequestStatus.INVALIDATED, invalidated.status());
        assertEquals(ReviewInvalidationReason.DIFF_CHANGED,
                invalidated.invalidationReason().orElseThrow());
        assertThrows(StaleReviewRequestException.class,
                () -> invalidated.requireCurrent(fixture.context));
    }

    @Test
    void classifiesEvidenceConfigurationPolicyAndContextOnlyDrift() {
        ReviewDomainFixture fixture = new ReviewDomainFixture();
        ReviewRequest request = ReviewRequest.initial(
                ReviewRequestId.generate(), fixture.context, fixture.actor, ReviewDomainFixture.CREATED_AT);

        ReviewTestEvidenceReference changedEvidence = fixture.test("test-2", fixture.diff, fixture.command);
        ContextPackage evidenceContext = fixture.successor(
                fixture.context,
                fixture.subject,
                fixture.diff,
                changedEvidence,
                fixture.reviewer,
                "+return name == null ? \"\" : name.strip();\n");
        assertEquals(ReviewInvalidationReason.TEST_EVIDENCE_CHANGED,
                request.staleReason(evidenceContext).orElseThrow());

        ReviewerExecutionReference changedConfiguration = fixture.reviewer("config-2", "policy-1", 1);
        ContextPackage configurationContext = fixture.successor(
                fixture.context,
                fixture.subject,
                fixture.diff,
                fixture.testEvidence,
                changedConfiguration,
                "+return name == null ? \"\" : name.strip();\n");
        assertEquals(ReviewInvalidationReason.REVIEWER_CONFIGURATION_CHANGED,
                request.staleReason(configurationContext).orElseThrow());

        ReviewerExecutionReference changedPolicy = fixture.reviewer("config-1", "policy-2", 2);
        ContextPackage policyContext = fixture.successor(
                fixture.context,
                fixture.subject,
                fixture.diff,
                fixture.testEvidence,
                changedPolicy,
                "+return name == null ? \"\" : name.strip();\n");
        assertEquals(ReviewInvalidationReason.POLICY_CHANGED,
                request.staleReason(policyContext).orElseThrow());

        ContextPackage hunkContext = fixture.successor(
                fixture.context,
                fixture.subject,
                fixture.diff,
                fixture.testEvidence,
                fixture.reviewer,
                "+return name == null ? \"\" : name.trim();\n");
        assertEquals(ReviewInvalidationReason.CONTEXT_CHANGED,
                request.staleReason(hunkContext).orElseThrow());
        request.requireCurrent(fixture.context);
    }
}
