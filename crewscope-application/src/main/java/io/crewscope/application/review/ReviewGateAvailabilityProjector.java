package io.crewscope.application.review;

import io.crewscope.application.availability.ActionStrength;
import io.crewscope.application.availability.TransitionBlockReason;
import io.crewscope.application.availability.TransitionRemedy;
import io.crewscope.domain.review.ReviewRequestStatus;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The single adjudication of whether a Review Gate action may be taken right now.
 *
 * <p>Every refusal is stated in the order the owning command applies it, because the first failed
 * check is the one the command would report. The order is part of the contract, not an accident of
 * implementation: a member who holds no Reviewer responsibility is told to get one rather than told
 * that their status is wrong, on the wire and in the command alike.
 *
 * <p>The projector adds no rule of its own. Each check reads a fact {@link ReviewGateFacts} resolved
 * through the very predicates and policies the commands use, so a control can never be offered and
 * then refused.
 */
public final class ReviewGateAvailabilityProjector {

    private static final Predicate<ReviewGateFacts> EXECUTABLE_STATUS =
            facts -> facts.status() == ReviewRequestStatus.OPEN
                    || facts.status() == ReviewRequestStatus.IN_PROGRESS;

    private static final List<GateActionMetadata> CATALOG = List.of(
            new GateActionMetadata(
                    "execute",
                    ReviewRequestStatus.IN_PROGRESS,
                    "执行评审",
                    ActionStrength.PRIMARY,
                    false,
                    List.of(
                            reviewerRequired(ReviewGateFacts::reviewerAgentAssigned),
                            statusRequired(EXECUTABLE_STATUS))),
            new GateActionMetadata(
                    "decide",
                    ReviewRequestStatus.COMPLETED,
                    "记录决策",
                    ActionStrength.PRIMARY,
                    false,
                    List.of(
                            reviewerRequired(ReviewGateFacts::gateReviewerAssigned),
                            dutySeparationRequired(),
                            statusRequired(
                                    facts -> facts.status() == ReviewRequestStatus.COMPLETED))),
            new GateActionMetadata(
                    "request-changes",
                    ReviewRequestStatus.COMPLETED,
                    "请求修改",
                    ActionStrength.SECONDARY,
                    false,
                    List.of(
                            reviewerRequired(ReviewGateFacts::gateReviewerAssigned),
                            dutySeparationRequired(),
                            statusRequired(
                                    facts -> facts.status() == ReviewRequestStatus.COMPLETED))),
            // Re-review resolves the current Reviewer before it ever looks at the predecessor's
            // status, so a request with no active advisory Reviewer is refused for that, not for its
            // status. Everything it resolves before the Reviewer — the Diff, TestEvidence and
            // PolicySnapshot — lives in other aggregates and is recorded as an unobservable gap
            // instead of guessed at: this control states the refusals a member would actually get,
            // in the order they would get them.
            new GateActionMetadata(
                    "re-review",
                    ReviewRequestStatus.OPEN,
                    "重新评审",
                    ActionStrength.SECONDARY,
                    true,
                    List.of(
                            reviewerRequired(ReviewGateFacts::reviewerAgentAssigned),
                            statusRequired(
                                    facts -> facts.status() == ReviewRequestStatus.INVALIDATED))));

    /** Every Gate command, each marked enabled or carrying the reason it is refused. */
    public List<ReviewGateAction> all(ReviewGateFacts facts) {
        ReviewGateFacts required = Objects.requireNonNull(facts, "facts");
        return CATALOG.stream().map(action -> evaluate(required, action)).toList();
    }

    /**
     * Only the executable Gate commands.
     *
     * <p>Used by surfaces with no room to explain a disabled control; a surface that can explain reads
     * {@link #all} and keeps the reasons on screen.
     */
    public List<ReviewGateAction> enabled(ReviewGateFacts facts) {
        return all(facts).stream().filter(ReviewGateAction::enabled).toList();
    }

    private static ReviewGateAction evaluate(ReviewGateFacts facts, GateActionMetadata action) {
        ReviewGateAction published = ReviewGateAction.enabled(
                action.actionId(),
                action.targetStatus(),
                action.label(),
                action.strength(),
                action.reversible());
        return action.checks().stream()
                .filter(check -> !check.satisfied().test(facts))
                .findFirst()
                .map(failed -> ReviewGateAction.disabled(
                        published, failed.reason(), remedy(facts, failed)))
                .orElse(published);
    }

    /**
     * The next step a refused member can actually take.
     *
     * <p>Only a missing Reviewer responsibility has one: the member cannot assign themselves out of a
     * permission refusal, and they cannot undo the Owner/Executor overlap that makes them ineligible,
     * so those reasons carry no destination rather than a link to a page that cannot change the
     * outcome.
     */
    private static Optional<TransitionRemedy> remedy(
            ReviewGateFacts facts, GateCheck failed) {
        return failed.remediable() ? facts.reviewerRemedy() : Optional.empty();
    }

    private static GateCheck reviewerRequired(Predicate<ReviewGateFacts> satisfied) {
        return new GateCheck(TransitionBlockReason.REVIEWER_REQUIRED, satisfied, true);
    }

    private static GateCheck dutySeparationRequired() {
        return new GateCheck(
                TransitionBlockReason.DUTY_SEPARATION_CONFLICT,
                ReviewGateFacts::dutySeparated,
                false);
    }

    private static GateCheck statusRequired(Predicate<ReviewGateFacts> satisfied) {
        return new GateCheck(TransitionBlockReason.STATUS_NOT_ALLOWED, satisfied, false);
    }

    /** One precondition in the order its command applies it, and the reason a refusal carries. */
    private record GateCheck(
            TransitionBlockReason reason, Predicate<ReviewGateFacts> satisfied, boolean remediable) {}

    private record GateActionMetadata(
            String actionId,
            ReviewRequestStatus targetStatus,
            String label,
            ActionStrength strength,
            boolean reversible,
            List<GateCheck> checks) {}
}
