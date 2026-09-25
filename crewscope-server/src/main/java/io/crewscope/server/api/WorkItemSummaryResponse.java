package io.crewscope.server.api;

import io.crewscope.application.workitem.WorkItemBlockedReason;
import io.crewscope.application.workitem.WorkItemExecutionSummary;
import java.util.List;

/**
 * HTTP shape of the M9b-A06 execution/todo summary embedded in every WorkItem list row and detail
 * snapshot. Optional facts are {@code null} rather than absent fields, matching the module's other
 * nullable response columns.
 */
public record WorkItemSummaryResponse(
    String workItemId,
    long workItemVersion,
    String workStatus,
    int taskCount,
    int activeTaskCount,
    int pendingReviewCount,
    String currentTaskId,
    String currentExecutionId,
    String executionStatus,
    boolean selectionRequired,
    List<BlockedReasonResponse> blockedReasons,
    String resultSummary,
    String resultSourceReference,
    long projectionVersion,
    String observedAt) {

  static WorkItemSummaryResponse from(WorkItemExecutionSummary summary) {
    return new WorkItemSummaryResponse(
        summary.workItemId().toString(),
        summary.workItemVersion(),
        summary.workStatus().name(),
        summary.taskCount(),
        summary.activeTaskCount(),
        summary.pendingReviewCount(),
        summary.currentTaskId().map(Object::toString).orElse(null),
        summary.currentExecutionId().map(Object::toString).orElse(null),
        summary.executionStatus().map(Enum::name).orElse(null),
        summary.selectionRequired(),
        summary.blockedReasons().stream().map(BlockedReasonResponse::from).toList(),
        summary.resultSummary().orElse(null),
        summary.resultSourceReference().orElse(null),
        summary.projectionVersion(),
        summary.observedAt().toString());
  }

  /** One explainable wait; {@code waitingOnPrincipalId} names the member to act, when there is exactly one. */
  public record BlockedReasonResponse(
      String code,
      String taskId,
      String executionId,
      String since,
      String waitingOnPrincipalId) {

    static BlockedReasonResponse from(WorkItemBlockedReason reason) {
      return new BlockedReasonResponse(
          reason.code().name(),
          reason.taskId().map(Object::toString).orElse(null),
          reason.executionId().map(Object::toString).orElse(null),
          reason.since().map(Object::toString).orElse(null),
          reason.waitingOnPrincipalId().map(Object::toString).orElse(null));
    }
  }
}
