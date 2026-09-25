package io.crewscope.infrastructure.persistence.workitem;

import io.crewscope.application.workitem.WorkItemBlockedReason;
import io.crewscope.application.workitem.WorkItemExecutionSummary;
import io.crewscope.application.workitem.WorkItemSummaryRepository;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.task.TaskExecutionWaitReason;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Batch assembler for the M9b-A06 execution/todo summary.
 *
 * <p>One page costs a fixed number of set-based queries keyed by the page's WorkItem ID set — the
 * counts anchor, the current executions, the pending review counts and the active reviewers — never
 * one query per row. The facts are read live at assembly time; nothing is written back to the
 * aggregate, so a renamed source object or a moved review is reflected on the next read.
 */
@Repository
public class JdbcWorkItemSummaryRepositoryAdapter implements WorkItemSummaryRepository {

  private final JdbcTemplate jdbc;

  public JdbcWorkItemSummaryRepositoryAdapter(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  @Transactional(readOnly = true)
  public Map<WorkItemId, WorkItemExecutionSummary> summarize(
      OrganizationId organizationId,
      TeamId teamId,
      Optional<WorkProjectId> projectId,
      Collection<WorkItemId> workItemIds,
      UtcTimestamp observedAt) {
    Objects.requireNonNull(organizationId, "organizationId");
    Objects.requireNonNull(teamId, "teamId");
    Objects.requireNonNull(projectId, "projectId");
    Objects.requireNonNull(observedAt, "observedAt");
    Set<WorkItemId> requested = new HashSet<>(Objects.requireNonNull(workItemIds, "workItemIds"));
    requested.remove(null);
    if (requested.isEmpty()) {
      return Map.of();
    }
    if (requested.size() > MAX_PAGE_IDS) {
      throw new IllegalArgumentException(
          "WorkItem summary batches are limited to " + MAX_PAGE_IDS + " IDs");
    }
    String idPlaceholders = placeholders(requested.size());
    // An empty project is the WorkDesk's cross-project page: the ID set scopes it, the project
    // predicate simply drops out of every query.
    List<Object> scope = new ArrayList<>();
    scope.add(organizationId.value());
    scope.add(teamId.value());
    projectId.ifPresent(project -> scope.add(project.value()));
    String projectFilter = projectId.isPresent() ? " AND %s.project_id = ?" : "";

    // SQL 1 — counts anchored on the work_item row, so zero-task items are present too.
    Map<WorkItemId, Counts> counts = new HashMap<>();
    jdbc.query(
        """
        SELECT wi.id, wi.version, wi.status,
               COUNT(task.id) AS task_count,
               COUNT(task.id) FILTER (
                   WHERE task.status IN ('CREATED', 'ACTIVE', 'WAITING')) AS active_count
        FROM crewscope.work_item wi
        LEFT JOIN crewscope.task task
          ON task.organization_id = wi.organization_id
         AND task.team_id = wi.team_id
         AND task.project_id = wi.project_id
         AND task.work_item_id = wi.id
        WHERE wi.organization_id = ? AND wi.team_id = ?%s
          AND wi.id IN (%s)
        GROUP BY wi.id, wi.version, wi.status
        """.formatted(projectFilter.formatted("wi"), idPlaceholders),
        row -> {
          counts.put(
              new WorkItemId(row.getObject("id", java.util.UUID.class)),
              new Counts(
                  row.getLong("version"),
                  WorkItemStatus.valueOf(row.getString("status")),
                  row.getInt("task_count"),
                  row.getInt("active_count")));
        },

        withArgs(scope, requested));

    // SQL 2 — every task's current execution: feeds the single-task current attempt and the
    // WAITING blocked reasons in one pass.
    Map<WorkItemId, List<CurrentExecution>> currentExecutions = new HashMap<>();
    jdbc.query(
        """
        SELECT task.work_item_id, task.id AS task_id, task.current_execution_id,
               execution.status AS execution_status,
               execution.waiting_reason, execution.waiting_since
        FROM crewscope.task task
        LEFT JOIN crewscope.task_execution execution
          ON execution.organization_id = task.organization_id
         AND execution.team_id = task.team_id
         AND execution.id = task.current_execution_id
        WHERE task.organization_id = ? AND task.team_id = ?%s
          AND task.work_item_id IN (%s)
        """.formatted(projectFilter.formatted("task"), idPlaceholders),
        row -> {
          currentExecutions
              .computeIfAbsent(
                  new WorkItemId(row.getObject("work_item_id", java.util.UUID.class)),
                  ignored -> new ArrayList<>())
              .add(new CurrentExecution(
                  new TaskId(row.getObject("task_id", java.util.UUID.class)),
                  Optional.ofNullable(row.getObject("current_execution_id", java.util.UUID.class))
                      .map(TaskExecutionId::new),
                  Optional.ofNullable(row.getString("execution_status"))
                      .map(TaskExecutionStatus::valueOf),
                  Optional.ofNullable(row.getString("waiting_reason"))
                      .map(TaskExecutionWaitReason::valueOf),
                  optionalTimestamp(row.getTimestamp("waiting_since"))));
        },
        withArgs(scope, requested));

    // SQL 3 — the WorkDesk review filter's own counting rule, aggregated per WorkItem.
    Map<WorkItemId, Integer> pendingReviews = new HashMap<>();
    jdbc.query(
        """
        SELECT task.work_item_id, COUNT(*) AS pending_count
        FROM crewscope.review_request_projection projection
        JOIN crewscope.task task
          ON task.organization_id = projection.organization_id
         AND task.team_id = projection.team_id
         AND task.id = projection.task_id
        WHERE projection.organization_id = ? AND projection.team_id = ?%s
          AND projection.request_status IN ('OPEN', 'IN_PROGRESS')
          AND (projection.latest_decision_type IS NULL
               OR projection.latest_decision_type = 'COMMENTED')
          AND task.work_item_id IN (%s)
        GROUP BY task.work_item_id
        """.formatted(projectFilter.formatted("projection"), idPlaceholders),
        row -> {
          pendingReviews.put(
              new WorkItemId(row.getObject("work_item_id", java.util.UUID.class)),
              row.getInt("pending_count"));
        },
        withArgs(scope, requested));

    // SQL 4 — active reviewers, resolving "waiting on whom" for review-shaped waits. A unique
    // reviewer names a person; multiple reviewers are a group wait, which reports no single name.
    Map<WorkItemId, List<PrincipalId>> reviewers = new HashMap<>();
    jdbc.query(
        """
        SELECT assignment.work_item_id, assignment.actor_principal_id
        FROM crewscope.responsibility_assignment assignment
        WHERE assignment.organization_id = ? AND assignment.team_id = ?%s
          AND assignment.status = 'ACTIVE'
          AND assignment.role = 'REVIEWER'
          AND assignment.work_item_id IN (%s)
        """.formatted(projectFilter.formatted("assignment"), idPlaceholders),
        row -> {
          reviewers
              .computeIfAbsent(
                  new WorkItemId(row.getObject("work_item_id", java.util.UUID.class)),
                  ignored -> new ArrayList<>())
              .add(new PrincipalId(row.getObject("actor_principal_id", java.util.UUID.class)));
        },
        withArgs(scope, requested));

    Map<WorkItemId, WorkItemExecutionSummary> summaries = new HashMap<>();
    for (Map.Entry<WorkItemId, Counts> entry : counts.entrySet()) {
      WorkItemId workItemId = entry.getKey();
      Counts count = entry.getValue();
      int pending = pendingReviews.getOrDefault(workItemId, 0);
      List<CurrentExecution> executions = currentExecutions.getOrDefault(workItemId, List.of());

      Optional<CurrentExecution> current = currentAttempt(count.taskCount(), executions);
      List<WorkItemBlockedReason> blocked = blockedReasons(executions, reviewers.get(workItemId));
      if (count.taskCount() > 1) {
        blocked = new ArrayList<>(blocked);
        blocked.add(WorkItemBlockedReason.selectionRequired());
      }
      if (pending > 0) {
        blocked = new ArrayList<>(blocked);
        blocked.add(WorkItemBlockedReason.reviewPending());
      }
      summaries.put(
          workItemId,
          new WorkItemExecutionSummary(
              workItemId,
              count.version(),
              count.status(),
              count.taskCount(),
              count.activeCount(),
              pending,
              current.map(CurrentExecution::taskId),
              current.flatMap(CurrentExecution::executionId),
              current.flatMap(CurrentExecution::executionStatus),
              count.taskCount() > 1,
              List.copyOf(blocked),
              Optional.empty(),
              Optional.empty(),
              count.version(),
              observedAt));
    }
    return Map.copyOf(summaries);
  }

  /**
   * The current attempt exists only for a single-task WorkItem whose task has a current execution;
   * a mid-assembly race that grew the task set suppresses it rather than guessing.
   */
  private static Optional<CurrentExecution> currentAttempt(
      int taskCount, List<CurrentExecution> executions) {
    if (taskCount != 1 || executions.size() != 1) {
      return Optional.empty();
    }
    return executions.get(0).executionId().isPresent()
        ? Optional.of(executions.get(0))
        : Optional.empty();
  }

  private static List<WorkItemBlockedReason> blockedReasons(
      List<CurrentExecution> executions, List<PrincipalId> reviewers) {
    Optional<PrincipalId> waitingOn =
        reviewers != null && reviewers.size() == 1
            ? Optional.of(reviewers.get(0))
            : Optional.empty();
    List<WorkItemBlockedReason> reasons = new ArrayList<>();
    for (CurrentExecution execution : executions) {
      if (execution.executionStatus().filter(status -> status == TaskExecutionStatus.WAITING).isEmpty()
          || execution.waitingReason().isEmpty()) {
        continue;
      }
      TaskExecutionWaitReason reason = execution.waitingReason().orElseThrow();
      boolean namesAPerson =
          reason == TaskExecutionWaitReason.REVIEW
              || reason == TaskExecutionWaitReason.CONFIRMATION
              || reason == TaskExecutionWaitReason.USER_INPUT;
      reasons.add(
          WorkItemBlockedReason.waiting(
              reason,
              execution.taskId(),
              execution.executionId().orElseThrow(),
              execution.waitingSince(),
              namesAPerson ? waitingOn : Optional.empty()));
    }
    return reasons;
  }

  private static Optional<UtcTimestamp> optionalTimestamp(Timestamp value) {
    return value == null ? Optional.empty() : Optional.of(UtcTimestamp.from(value.toInstant()));
  }

  private static String placeholders(int size) {
    return String.join(",", java.util.Collections.nCopies(size, "?"));
  }

  private static Object[] withArgs(List<Object> scope, Collection<WorkItemId> ids) {
    List<Object> args = new ArrayList<>(scope);
    ids.forEach(id -> args.add(id.value()));
    return args.toArray();
  }

  private record Counts(long version, WorkItemStatus status, int taskCount, int activeCount) {}

  private record CurrentExecution(
      TaskId taskId,
      Optional<TaskExecutionId> executionId,
      Optional<TaskExecutionStatus> executionStatus,
      Optional<TaskExecutionWaitReason> waitingReason,
      Optional<UtcTimestamp> waitingSince) {}
}
