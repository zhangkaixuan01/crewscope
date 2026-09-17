package io.crewscope.application.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.availability.TransitionBlockReason;
import io.crewscope.domain.responsibility.ReviewerPolicyViolationException;
import io.crewscope.domain.review.ReviewDecisionType;
import io.crewscope.domain.review.ReviewRequestStatus;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

/**
 * M9-A05: the Review Gate projection and the Review Gate commands answer one question, not two.
 *
 * <p>Every cell of the matrix — status x Reviewer Agent assignment x Gate Reviewer assignment x duty
 * separation — is judged twice on identical facts: once by {@link ReviewGateAvailabilityProjector}
 * and once by handing the same facts to the real command service. The projection may only promise an
 * action the command accepts, and may only refuse one with the reason the command would give. This is
 * the Review half of anti-pattern #9; a control that is offered and then refused fails here.
 *
 * <p>Two facts about the fixture bound what this test claims, both recorded in
 * {@code docs/api/M9-状态流转可用性API契约.md} rather than left implicit:
 *
 * <ul>
 *   <li>A mocked {@code ReviewRequest} cannot complete its own transition, so
 *       {@code ReviewRequestTest} owns that; availability is about the guards, and every guard here
 *       is real.
 *   <li>Re-review resolves a predecessor aggregate this fixture cannot build, so its enabled
 *       direction is asserted as "not refused for a projected reason".
 * </ul>
 */
class ReviewGateAvailabilityReconciliationTest {

    /**
     * The matrix. The first four cells vary one dimension at a time off a separated, fully assigned
     * COMPLETED request, so a rule that reads the wrong fact shows up as a disagreement rather than
     * as a coincidence.
     */
    private static final List<Cell> CELLS = List.of(
            new Cell(ReviewRequestStatus.OPEN, true, true, true, "open, assigned"),
            new Cell(ReviewRequestStatus.OPEN, false, true, true, "open, no Reviewer Agent"),
            new Cell(ReviewRequestStatus.OPEN, true, false, true, "open, no Gate Reviewer"),
            new Cell(ReviewRequestStatus.OPEN, true, true, false, "open, duty conflict"),
            new Cell(ReviewRequestStatus.IN_PROGRESS, true, true, true, "in progress, assigned"),
            new Cell(ReviewRequestStatus.IN_PROGRESS, false, true, true, "in progress, no agent"),
            new Cell(ReviewRequestStatus.COMPLETED, true, true, true, "completed, separated"),
            new Cell(ReviewRequestStatus.COMPLETED, false, true, true, "completed, no agent"),
            new Cell(ReviewRequestStatus.COMPLETED, true, false, true, "completed, no gate reviewer"),
            new Cell(ReviewRequestStatus.COMPLETED, true, true, false, "completed, duty conflict"),
            new Cell(ReviewRequestStatus.COMPLETED, false, false, false, "completed, nothing held"),
            new Cell(ReviewRequestStatus.INVALIDATED, true, true, true, "invalidated, assigned"),
            new Cell(ReviewRequestStatus.INVALIDATED, false, true, true, "invalidated, no agent"));

    @Test
    void executeIsOfferedExactlyWhenTheExecutionCommandAccepts() {
        assertAgrees("execute", false, (fixture, cell) -> fixture.reviewer.execute(
                        fixture.context("execute-reconcile"), fixture.teamId, fixture.taskId,
                        fixture.executionId, fixture.requestId, 1)
                .toCompletableFuture().get());
    }

    @Test
    void decideIsOfferedExactlyWhenTheDecisionCommandAccepts() {
        assertAgrees("decide", false, (fixture, cell) -> fixture.gate.record(
                fixture.context("decide-reconcile"), fixture.teamId, fixture.taskId,
                fixture.executionId, fixture.requestId, 1,
                new RecordReviewDecisionCommand(ReviewDecisionType.APPROVED, "Looks correct")));
    }

    /**
     * Request-changes is the same route with a different conclusion, so it must publish the same
     * verdicts. It is asserted separately because a projection that keyed its reason off the decision
     * type would still pass the Decide test.
     */
    @Test
    void requestChangesIsOfferedExactlyWhenTheDecisionCommandAccepts() {
        assertAgrees("request-changes", false, (fixture, cell) -> fixture.gate.record(
                fixture.context("request-changes-reconcile"), fixture.teamId, fixture.taskId,
                fixture.executionId, fixture.requestId, 1,
                new RecordReviewDecisionCommand(
                        ReviewDecisionType.CHANGES_REQUESTED, "Please cover the null branch")));
    }

    @Test
    void reReviewIsRefusedWithTheStatusReasonTheCommandGives() {
        assertAgrees("re-review", true, (fixture, cell) -> fixture.requestService.reReview(
                fixture.context("re-review-reconcile"), fixture.teamId, fixture.taskId,
                fixture.executionId, fixture.requestId,
                new CreateReviewRequestCommand(fixture.policyId)));
    }

    /**
     * Each cell, then each action: the projection's verdict is compared with the command's.
     *
     * @param tolerateFixtureBoundary when true, the projection may offer an action whose command
     *     cannot run to completion in this fixture — the assertion then narrows to "the command did
     *     not refuse it for a reason the projection named", which is still falsified by any
     *     disagreement about the reason itself.
     */
    private static void assertAgrees(
            String actionId, boolean tolerateFixtureBoundary, Invocation invocation) {
        ReviewGateCommandTestSupport fixture = new ReviewGateCommandTestSupport();
        for (Cell cell : CELLS) {
            fixture.arrange(
                    cell.status(), cell.reviewerAgentAssigned(),
                    cell.gateReviewerAssigned(), cell.dutySeparated());
            ReviewGateAction action = fixture.projected(
                    fixture.facts(
                            cell.status(), cell.reviewerAgentAssigned(),
                            cell.gateReviewerAssigned(), cell.dutySeparated()),
                    actionId);
            Verdict verdict = verdict(
                    () -> invocation.run(fixture, cell), tolerateFixtureBoundary);
            String where = actionId + " @ " + cell.label();

            if (action.enabled()) {
                if (!tolerateFixtureBoundary) {
                    assertTrue(verdict.accepted(), where + ": offered but refused");
                } else {
                    assertTrue(
                            verdict.reason().isEmpty(),
                            where + ": offered but refused for a projected reason");
                }
                continue;
            }

            assertFalse(verdict.accepted(), where + ": refused but accepted");
            assertEquals(
                    action.reason(), verdict.reason(),
                    where + ": the control and the command name different reasons");
        }
    }

    private static Verdict verdict(Attempt attempt, boolean tolerateFixtureBoundary) {
        try {
            attempt.run();
            return new Verdict(true, Optional.empty());
        } catch (Throwable failure) {
            Throwable cause = unwrap(failure);
            if (tolerateFixtureBoundary && reachedSuccessorTransition(cause)) {
                return new Verdict(false, Optional.empty());
            }
            return new Verdict(false, refusalReason(cause));
        }
    }

    /**
     * Whether the command reached the successor transition, which is past every status it can
     * refuse.
     *
     * <p>Re-review is the one Gate action whose last step builds a new ReviewRequest from its
     * predecessor. That needs a whole aggregate chain this fixture deliberately does not build, so
     * the run stops there — after the status gate, which is the only rule Re-review publishes.
     * Anything that fails before it is a real refusal and is still required to carry the reason.
     */
    private static boolean reachedSuccessorTransition(Throwable failure) {
        if (!(failure instanceof NullPointerException)) {
            return false;
        }
        return java.util.Arrays.stream(failure.getStackTrace())
                .anyMatch(frame -> "io.crewscope.domain.review.ReviewRequest".equals(
                        frame.getClassName()))
                && java.util.Arrays.stream(failure.getStackTrace())
                        .anyMatch(frame -> "successor".equals(frame.getMethodName()));
    }

    /** Reads the availability reason a real command refusal corresponds to. */
    private static Optional<TransitionBlockReason> refusalReason(Throwable failure) {
        if (failure instanceof ReviewerPolicyViolationException) {
            return Optional.of(TransitionBlockReason.DUTY_SEPARATION_CONFLICT);
        }
        if (failure instanceof DomainValidationException validation) {
            String field = validation.error().details().get("field");
            String reason = validation.error().details().get("reason");
            if ("reviewDecision.reviewerMemberId".equals(field)
                    || "reviewRequest.reviewerAgent".equals(field)) {
                return Optional.of(TransitionBlockReason.REVIEWER_REQUIRED);
            }
            if ("reviewRequest.status".equals(field)
                    || ("reviewDecision.reviewRequest".equals(field)
                            && reason.contains("COMPLETED"))
                    || ("reviewRequest".equals(field)
                            && reason.contains("invalidated predecessor"))) {
                return Optional.of(TransitionBlockReason.STATUS_NOT_ALLOWED);
            }
            throw new AssertionError(
                    "A Gate command refused for a reason the projection cannot publish: " + field
                            + " / " + reason,
                    validation);
        }
        if (failure instanceof AggregateNotFoundException) {
            // A missing aggregate is not a Gate verdict. Re-review resolves the predecessor and the
            // creation evidence before it can refuse anything, and this fixture carries a mocked
            // predecessor rather than a whole aggregate chain.
            return Optional.empty();
        }
        throw new AssertionError("A Gate command failed outside its availability rules", failure);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof ExecutionException || current instanceof CompletionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /** One matrix cell: the facts both the projection and the commands read. */
    private record Cell(
            ReviewRequestStatus status,
            boolean reviewerAgentAssigned,
            boolean gateReviewerAssigned,
            boolean dutySeparated,
            String label) {}

    /** What the command did with the same facts. */
    private record Verdict(boolean accepted, Optional<TransitionBlockReason> reason) {}

    @FunctionalInterface
    private interface Invocation {
        void run(ReviewGateCommandTestSupport fixture, Cell cell) throws Exception;
    }

    /** One command invocation, with no arguments of its own. */
    @FunctionalInterface
    private interface Attempt {
        void run() throws Exception;
    }
}
