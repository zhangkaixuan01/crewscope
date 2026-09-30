package io.crewscope.application.review;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.workitem.WorkItem;
import java.util.List;

/**
 * Resolves or reuses the per-attempt Reviewer {@link PolicySnapshot} for an exact TaskExecution.
 *
 * <p>M5 shipped Review creation around an explicit {@code reviewerPolicySnapshotId}, but no
 * product path ever created a Reviewer snapshot — the M9b-Q02 review-gate walkthrough found the
 * whole review chain unreachable. This source closes that gap: callers may omit the explicit
 * snapshot and let the platform resolve the advisory Reviewer Agent that already holds the active
 * REVIEWER responsibility on the WorkItem.
 */
public interface ReviewerPolicySnapshotAutoSource {

    /** Must be idempotent per TaskExecution attempt: a reusable snapshot is never rebuilt. */
    PolicySnapshot resolveSnapshot(
            TeamAccessContext context,
            Task task,
            WorkItem workItem,
            TaskExecution execution,
            List<ResponsibilityAssignment> assignments);

    /** Convenience for implementations and tests that never resolve automatically. */
    static ReviewerPolicySnapshotAutoSource unavailable() {
        return (context, task, workItem, execution, assignments) -> {
            throw new DomainValidationException(
                    "reviewRequest.reviewerPolicySnapshotId",
                    "must be provided when automatic Reviewer resolution is unavailable");
        };
    }
}
