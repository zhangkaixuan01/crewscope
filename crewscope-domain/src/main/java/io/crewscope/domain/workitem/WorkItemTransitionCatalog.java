package io.crewscope.domain.workitem;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Static action metadata for the existing WorkItem state machine. */
public final class WorkItemTransitionCatalog {
  private static final Map<WorkItemStatus, Map<WorkItemStatus, EdgeMetadata>> METADATA = Map.of(
      WorkItemStatus.BACKLOG, Map.of(
          WorkItemStatus.READY, edge("submit-ready", "准备工作项", Strength.PRIMARY, true),
          WorkItemStatus.CANCELLED, edge("cancel", "取消工作项", Strength.DANGER, false)),
      WorkItemStatus.READY, Map.of(
          WorkItemStatus.IN_PROGRESS, edge("start", "开始执行", Strength.PRIMARY, true),
          WorkItemStatus.CANCELLED, edge("cancel", "取消工作项", Strength.DANGER, false)),
      WorkItemStatus.IN_PROGRESS, Map.of(
          WorkItemStatus.IN_REVIEW, edge("submit-review", "提交评审", Strength.PRIMARY, true),
          WorkItemStatus.BLOCKED, edge("block", "标记阻塞", Strength.SECONDARY, true),
          WorkItemStatus.CANCELLED, edge("cancel", "取消工作项", Strength.DANGER, false)),
      WorkItemStatus.IN_REVIEW, Map.of(
          WorkItemStatus.IN_PROGRESS, edge("resume-work", "继续执行", Strength.SECONDARY, true),
          WorkItemStatus.BLOCKED, edge("block", "标记阻塞", Strength.SECONDARY, true),
          WorkItemStatus.DONE, edge("complete", "完成工作项", Strength.PRIMARY, true),
          WorkItemStatus.CANCELLED, edge("cancel", "取消工作项", Strength.DANGER, false)),
      WorkItemStatus.BLOCKED, Map.of(
          WorkItemStatus.READY, edge("return-ready", "退回待开始", Strength.SECONDARY, true),
          WorkItemStatus.IN_PROGRESS, edge("resume-work", "继续执行", Strength.PRIMARY, true),
          WorkItemStatus.IN_REVIEW, edge("submit-review", "提交评审", Strength.PRIMARY, true),
          WorkItemStatus.CANCELLED, edge("cancel", "取消工作项", Strength.DANGER, false)),
      WorkItemStatus.DONE, Map.of(
          WorkItemStatus.ARCHIVED, edge("archive", "归档工作项", Strength.DANGER, false)),
      WorkItemStatus.CANCELLED, Map.of(
          WorkItemStatus.ARCHIVED, edge("archive", "归档工作项", Strength.DANGER, false)),
      WorkItemStatus.ARCHIVED, Map.of());

  private WorkItemTransitionCatalog() {}

  /** Lists only the edges already permitted by {@link WorkItem}. */
  public static List<Edge> from(WorkItemStatus status) {
    WorkItemStatus source = Objects.requireNonNull(status, "status");
    return WorkItem.allowedTransitionsFrom(source).stream()
        .sorted()
        .map(target -> new Edge(source, target, METADATA.get(source).get(target)))
        .toList();
  }

  private static EdgeMetadata edge(String actionId, String label, Strength strength, boolean reversible) {
    return new EdgeMetadata(actionId, label, strength, reversible);
  }

  public record Edge(WorkItemStatus from, WorkItemStatus to, EdgeMetadata metadata) {}

  public record EdgeMetadata(String actionId, String label, Strength strength, boolean reversible) {}

  public enum Strength { PRIMARY, SECONDARY, DANGER }
}
