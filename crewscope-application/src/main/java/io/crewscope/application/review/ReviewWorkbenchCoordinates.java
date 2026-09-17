package io.crewscope.application.review;

import io.crewscope.application.availability.TransitionRemedy;
import io.crewscope.domain.workitem.WorkItem;
import java.util.Objects;

/**
 * The stable in-product coordinates a disabled Review action can send a member to.
 *
 * <p>A remedy is only ever an internal path to the surface that owns the missing fact; the server
 * never publishes an external URL, a Provider endpoint or a filesystem path. The shape mirrors the
 * coordinates the Task and Inbox responses already publish for the same WorkItem surface, so a
 * member who follows a remedy lands on the WorkItem that owns the responsibility, not on a page that
 * merely resembles it.
 */
public final class ReviewWorkbenchCoordinates {

    private ReviewWorkbenchCoordinates() {}

    /** Where a member grants the Reviewer responsibility the refused Gate action asked for. */
    public static TransitionRemedy assignReviewer(WorkItem workItem) {
        WorkItem required = Objects.requireNonNull(workItem, "workItem");
        return new TransitionRemedy(
                "指派 Reviewer",
                "/work?team=" + required.scope().teamId()
                        + "&project=" + required.scope().projectId()
                        + "&workItem=" + required.id()
                        + "&focus=" + required.key().value());
    }
}
