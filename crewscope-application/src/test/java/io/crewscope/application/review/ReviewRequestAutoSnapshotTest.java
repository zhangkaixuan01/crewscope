package io.crewscope.application.review;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.PolicySnapshotId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * M9b-Q02 review-gate regression: Review creation must work without a pre-pinned
 * reviewerPolicySnapshotId — no product path ever created a Reviewer snapshot, which left the
 * whole review chain unreachable. An omitted snapshot delegates to the automatic Reviewer source;
 * an explicit snapshot stays authoritative and never touches it.
 */
class ReviewRequestAutoSnapshotTest {

    @Test
    void omittedSnapshotDelegatesToTheAutomaticReviewerSource() {
        ReviewGateCommandTestSupport fixture = new ReviewGateCommandTestSupport();
        fixture.arrange(io.crewscope.domain.review.ReviewRequestStatus.OPEN, true, false, true);
        // The Gate fixture leaves an existing current request; a new creation must observe none.
        when(fixture.requests.findCurrentByExecution(
                fixture.organizationId, fixture.executionId, 1))
                .thenReturn(Optional.empty());
        ReviewerPolicySnapshotAutoSource auto = mock(ReviewerPolicySnapshotAutoSource.class);
        PolicySnapshot resolved = mock(PolicySnapshot.class);
        when(resolved.id()).thenReturn(PolicySnapshotId.generate());
        when(auto.resolveSnapshot(any(), any(), any(), any(), any())).thenReturn(resolved);
        fixture.autoSource.set(auto);

        // The delegated snapshot enters the exact-facts validation: it is looked up by its own id,
        // and an unknown one must refuse there rather than silently falling back to the slot.
        RuntimeException missing = assertThrows(RuntimeException.class,
                () -> fixture.requestService.create(
                        fixture.context("auto-resolve"),
                        fixture.teamId, fixture.taskId, fixture.executionId,
                        new CreateReviewRequestCommand(Optional.empty())));
        assertTrue(missing.getMessage().contains("PolicySnapshot"),
                () -> "expected the delegated snapshot to enter exact-facts validation: " + missing);

        verify(auto).resolveSnapshot(any(), any(), any(), any(), any());
    }

    @Test
    void anUnavailableAutomaticSourceRefusesInsteadOfCreatingBlindly() {
        ReviewGateCommandTestSupport fixture = new ReviewGateCommandTestSupport();
        fixture.arrange(io.crewscope.domain.review.ReviewRequestStatus.OPEN, true, false, true);
        when(fixture.requests.findCurrentByExecution(
                fixture.organizationId, fixture.executionId, 1))
                .thenReturn(Optional.empty());

        DomainValidationException refused = assertThrows(DomainValidationException.class,
                () -> fixture.requestService.create(
                        fixture.context("auto-unavailable"),
                        fixture.teamId, fixture.taskId, fixture.executionId,
                        new CreateReviewRequestCommand(Optional.empty())));
        assertTrue(refused.getMessage()
                .contains("reviewRequest.reviewerPolicySnapshotId"));
    }

    @Test
    void explicitSnapshotsNeverTouchTheAutomaticSource() {
        ReviewGateCommandTestSupport fixture = new ReviewGateCommandTestSupport();
        fixture.arrange(io.crewscope.domain.review.ReviewRequestStatus.OPEN, true, false, true);
        when(fixture.requests.findCurrentByExecution(
                fixture.organizationId, fixture.executionId, 1))
                .thenReturn(Optional.empty());
        ReviewerPolicySnapshotAutoSource auto = mock(ReviewerPolicySnapshotAutoSource.class);
        fixture.autoSource.set(auto);

        assertThrows(RuntimeException.class, () -> fixture.requestService.create(
                fixture.context("explicit"),
                fixture.teamId, fixture.taskId, fixture.executionId,
                new CreateReviewRequestCommand(fixture.policyId)));

        verifyNoInteractions(auto);
    }
}
