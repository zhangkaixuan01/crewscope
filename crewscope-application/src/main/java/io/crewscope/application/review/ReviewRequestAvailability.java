package io.crewscope.application.review;

import java.util.List;
import java.util.Objects;

/**
 * One Review workbench row and the Gate actions a member may take on it.
 *
 * <p>The summary carries the full adjudication, disabled entries included, so a list or drawer never
 * has to re-derive a verdict — and never has to ask a second endpoint per row to find out what the
 * row can do. The row carries the verdict; the rules stay in {@link ReviewGateAvailabilityProjector}.
 */
public record ReviewRequestAvailability(
        ReviewRequestProjection projection, List<ReviewGateAction> availableActions) {

    public ReviewRequestAvailability {
        projection = Objects.requireNonNull(projection, "projection");
        availableActions = List.copyOf(Objects.requireNonNull(availableActions, "availableActions"));
    }
}
