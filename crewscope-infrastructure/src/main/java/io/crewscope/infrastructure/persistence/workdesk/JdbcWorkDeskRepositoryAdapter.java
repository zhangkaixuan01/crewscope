package io.crewscope.infrastructure.persistence.workdesk;

import io.crewscope.application.workdesk.WorkDeskItem;
import io.crewscope.application.workdesk.WorkDeskQuery;
import io.crewscope.application.workdesk.WorkDeskRepository;
import io.crewscope.application.workdesk.WorkDeskSection;
import io.crewscope.application.workdesk.WorkDeskSummary;
import io.crewscope.application.workitem.WorkItemTransitionSubject;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.workitem.WorkItemSource;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL adapter for the derived personal WorkDesk projection. */
@Repository
public class JdbcWorkDeskRepositoryAdapter implements WorkDeskRepository {
  private static final int MAX_ITEMS = 500;
  private final JdbcTemplate jdbc;

  public JdbcWorkDeskRepositoryAdapter(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  @Transactional(readOnly = true)
  public WorkDeskSummary summarize(WorkDeskQuery query) {
    WorkDeskQuery value = Objects.requireNonNull(query, "query");
    Instant generatedAt = Instant.now();
    List<WorkDeskItem> workItems = workItems(value);
    List<WorkDeskItem> executions = executions(value);
    List<WorkDeskItem> reviews = reviews(value);
    List<WorkDeskItem> gates = gates(value);
    List<WorkDeskItem> blocked = blocked(value);
    long unread = unreadInbox(value);
    // Consume one shared budget in priority order so one noisy section cannot crowd out all others.
    int remaining = MAX_ITEMS;
    WorkDeskSection humanGate = section("HUMAN_GATE", "等我决策", 1, gates, remaining);
    remaining -= humanGate.items().size();
    WorkDeskSection review = section("REVIEW", "待我 Review", 2, reviews, remaining);
    remaining -= review.items().size();
    WorkDeskSection blockedSection = section("BLOCKED", "被我阻塞", 3, blocked, remaining);
    remaining -= blockedSection.items().size();
    WorkDeskSection workItem = section("WORK_ITEM", "我的工作项", 4, workItems, remaining);
    remaining -= workItem.items().size();
    WorkDeskSection taskExecution = section("TASK_EXECUTION", "进行中的执行", 5, executions, remaining);
    List<WorkDeskSection> sections = List.of(
        humanGate,
        review,
        blockedSection,
        workItem,
        taskExecution,
        inboxSection(value, unread, generatedAt));
    return new WorkDeskSummary(
        value.organizationId().toString(), value.teamId().toString(),
        value.projectId().map(Object::toString), generatedAt, sections);
  }

  private List<WorkDeskItem> workItems(WorkDeskQuery query) {
    StringBuilder sql = new StringBuilder("""
        SELECT wi.id, wi.project_id, wi.title, wi.status, wi.priority, wi.updated_at,
               wi.source_provider, assignment.role
        FROM crewscope.work_item wi
        JOIN crewscope.responsibility_assignment assignment
          ON assignment.organization_id = wi.organization_id
         AND assignment.team_id = wi.team_id
         AND assignment.project_id = wi.project_id
         AND assignment.work_item_id = wi.id
         AND assignment.status = 'ACTIVE'
        WHERE wi.organization_id = ? AND wi.team_id = ?
          AND assignment.actor_principal_id = ?
        """);
    List<Object> args = new ArrayList<>(List.of(
        query.organizationId().value(), query.teamId().value(), query.principalId().value()));
    appendScopeFilters(sql, args, query);
    sql.append(" ORDER BY wi.updated_at DESC, wi.id DESC LIMIT ").append(MAX_ITEMS + 1);
    return jdbc.query(sql.toString(), (row, ignored) -> {
      String status = row.getString("status");
      String role = row.getString("role");
      return item("WORK_ITEM", row.getObject("id").toString(), row.getObject("project_id").toString(),
          row.getString("title"), status, row.getTimestamp("updated_at"), role,
          needsAction(role, status), urgency(row.getString("priority")), progress(status),
          transitionSubject(row),
          "/work?team=" + query.teamId() + "&project=" + row.getObject("project_id") + "&workItem=" + row.getObject("id"));
    }, args.toArray());
  }

  private List<WorkDeskItem> executions(WorkDeskQuery query) {
    if (query.responsibilityRole().filter(role -> role != ResponsibilityRole.EXECUTOR).isPresent()) {
      return List.of();
    }
    StringBuilder sql = new StringBuilder("""
        SELECT execution.id, execution.project_id, execution.status, execution.updated_at,
               task.id AS task_id, task.objective, task.work_item_id
        FROM crewscope.task_execution execution
        JOIN crewscope.task task
          ON task.organization_id = execution.organization_id
         AND task.team_id = execution.team_id AND task.id = execution.task_id
        WHERE execution.organization_id = ? AND execution.team_id = ?
          AND execution.status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
          AND (task.created_by_principal_id = ? OR execution.execution_principal_id = ?)
        """);
    List<Object> args = new ArrayList<>(List.of(
        query.organizationId().value(), query.teamId().value(),
        query.principalId().value(), query.principalId().value()));
    query.projectId().ifPresent(project -> { sql.append(" AND execution.project_id = ?"); args.add(project.value()); });
    if (query.onlyNeedsAction()) sql.append(" AND execution.status IN ('WAITING', 'MANUAL_TAKEOVER')");
    sql.append(" ORDER BY execution.updated_at DESC, execution.id DESC LIMIT ").append(MAX_ITEMS + 1);
    return jdbc.query(sql.toString(), (row, ignored) -> {
      String status = row.getString("status");
      String id = row.getObject("id").toString();
      return item("TASK_EXECUTION", id, row.getObject("project_id").toString(),
          row.getString("objective"), status, row.getTimestamp("updated_at"), null,
          needsAction(null, status), urgency("NORMAL"), Optional.empty(), null,
          taskExecutionRoute(query, row.getObject("project_id"), row.getObject("task_id"), id));
    }, args.toArray());
  }

  /**
   * The Task coordinate belongs to this contract: the board opens the Task detail from `task=`, so
   * an execution link without it lands on a board with nothing selected.
   */
  static String taskExecutionRoute(WorkDeskQuery query, Object projectId, Object taskId, String executionId) {
    return "/work?team=" + query.teamId() + "&project=" + projectId
        + "&task=" + taskId + "&taskExecution=" + executionId;
  }

  private List<WorkDeskItem> reviews(WorkDeskQuery query) {
    return reviewItems(query, false);
  }

  private List<WorkDeskItem> gates(WorkDeskQuery query) {
    return reviewItems(query, true);
  }

  private List<WorkDeskItem> reviewItems(WorkDeskQuery query, boolean gate) {
    if (query.responsibilityRole().filter(role -> role != ResponsibilityRole.REVIEWER).isPresent()) {
      return List.of();
    }
    StringBuilder sql = new StringBuilder("""
        SELECT DISTINCT projection.review_request_id, projection.project_id,
               projection.request_status, projection.projected_at, task.objective,
               projection.latest_decision_type
        FROM crewscope.review_request_projection projection
        JOIN crewscope.task task
          ON task.organization_id = projection.organization_id
         AND task.team_id = projection.team_id AND task.id = projection.task_id
        JOIN crewscope.responsibility_assignment assignment
          ON assignment.organization_id = task.organization_id
         AND assignment.team_id = task.team_id AND assignment.project_id = task.project_id
         AND assignment.work_item_id = task.work_item_id
         AND assignment.role = 'REVIEWER' AND assignment.status = 'ACTIVE'
         AND assignment.actor_principal_id = ?
        WHERE projection.organization_id = ? AND projection.team_id = ?
          AND projection.request_status IN ('OPEN', 'IN_PROGRESS')
          AND (projection.latest_decision_type IS NULL OR projection.latest_decision_type = 'COMMENTED')
        """);
    List<Object> args = new ArrayList<>(List.of(
        query.principalId().value(), query.organizationId().value(), query.teamId().value()));
    query.projectId().ifPresent(project -> { sql.append(" AND projection.project_id = ?"); args.add(project.value()); });
    if (query.onlyNeedsAction()) sql.append(" AND projection.latest_decision_type IS NULL");
    sql.append(" ORDER BY projection.projected_at DESC, projection.review_request_id DESC LIMIT ").append(MAX_ITEMS + 1);
    return jdbc.query(sql.toString(), (row, ignored) -> {
      String id = row.getObject("review_request_id").toString();
      String objectType = gate ? "HUMAN_GATE" : "REVIEW_REQUEST";
      return item(objectType, id, row.getObject("project_id").toString(), row.getString("objective"),
          row.getString("request_status"), row.getTimestamp("projected_at"), "REVIEWER", true,
          "HIGH", Optional.empty(), null, "/work?team=" + query.teamId() + "&project=" + row.getObject("project_id") + "&review=" + id);
    }, args.toArray());
  }

  private List<WorkDeskItem> blocked(WorkDeskQuery query) {
    if (query.responsibilityRole().filter(role -> role != ResponsibilityRole.REVIEWER).isPresent()) {
      return List.of();
    }
    // A blocked-by relation is not a separate domain fact yet; return only explicit blocked
    // WorkItems where the member is the active Reviewer, preserving the existing authority model.
    StringBuilder sql = new StringBuilder("""
        SELECT wi.id, wi.project_id, wi.title, wi.status, wi.updated_at, wi.source_provider
        FROM crewscope.work_item wi
        JOIN crewscope.responsibility_assignment assignment
          ON assignment.organization_id = wi.organization_id AND assignment.team_id = wi.team_id
         AND assignment.project_id = wi.project_id AND assignment.work_item_id = wi.id
         AND assignment.role = 'REVIEWER' AND assignment.status = 'ACTIVE'
         AND assignment.actor_principal_id = ?
        WHERE wi.organization_id = ? AND wi.team_id = ? AND wi.status = 'BLOCKED'
        """);
    List<Object> args = new ArrayList<>(List.of(query.principalId().value(), query.organizationId().value(), query.teamId().value()));
    query.projectId().ifPresent(project -> { sql.append(" AND wi.project_id = ?"); args.add(project.value()); });
    sql.append(" ORDER BY wi.updated_at DESC, wi.id DESC LIMIT ").append(MAX_ITEMS + 1);
    if (query.onlyNeedsAction()) return List.of();
    return jdbc.query(sql.toString(), (row, ignored) -> item("WORK_ITEM", row.getObject("id").toString(), row.getObject("project_id").toString(), row.getString("title"), "BLOCKED", row.getTimestamp("updated_at"), "REVIEWER", false, "HIGH", Optional.of(0), transitionSubject(row), "/work?team=" + query.teamId() + "&project=" + row.getObject("project_id") + "&workItem=" + row.getObject("id")), args.toArray());
  }

  private long unreadInbox(WorkDeskQuery query) {
    return jdbc.queryForObject("""
        SELECT COUNT(*) FROM crewscope.projection_pointer pointer
        JOIN crewscope.inbox_item item ON item.organization_id = pointer.organization_id
          AND item.projection_name = pointer.projection_name AND item.generation = pointer.active_generation
        LEFT JOIN crewscope.inbox_disposition disposition ON disposition.organization_id = item.organization_id
          AND disposition.team_id = item.team_id AND disposition.member_id = item.member_id
          AND disposition.inbox_item_id = item.inbox_item_id
        WHERE pointer.organization_id = ? AND pointer.projection_name = 'member-inbox'
          AND item.team_id = ? AND item.member_id = ? AND item.source_status = 'OPEN'
          AND disposition.status IS NULL
        """, Long.class, query.organizationId().value(), query.teamId().value(), query.memberId().value());
  }

  private static WorkDeskSection inboxSection(WorkDeskQuery query, long unread, Instant generatedAt) {
    WorkDeskItem item = item("INBOX", query.memberId().toString(), query.projectId().map(Object::toString).orElse(null),
        "未读 Inbox", "OPEN", generatedAt, null, unread > 0, unread > 0 ? "HIGH" : "NORMAL", Optional.empty(), null,
        "/inbox?team=" + query.teamId());
    int total = (int) Math.min(unread, Integer.MAX_VALUE);
    return new WorkDeskSection("INBOX", "未读 Inbox", 6, total, false, unread > 0 ? List.of(item) : List.of());
  }

  private static WorkDeskSection section(
      String key, String title, int priority, List<WorkDeskItem> rows, int budget) {
    int limit = Math.min(MAX_ITEMS, Math.max(0, budget));
    boolean truncated = rows.size() > limit;
    List<WorkDeskItem> bounded = truncated ? rows.subList(0, limit) : rows;
    return new WorkDeskSection(key, title, priority, rows.size(), truncated, bounded);
  }

  private static WorkDeskItem item(String type, String id, String projectId, String title, String status,
      Timestamp updatedAt, String role, boolean action, String urgency, Optional<Integer> progress,
      WorkItemTransitionSubject transitionSubject, String route) {
    return item(type, id, projectId, title, status, updatedAt.toInstant(), role, action, urgency, progress,
        transitionSubject, route);
  }

  private static WorkDeskItem item(String type, String id, String projectId, String title, String status,
      Instant updatedAt, String role, boolean action, String urgency, Optional<Integer> progress,
      WorkItemTransitionSubject transitionSubject, String route) {
    return new WorkDeskItem(type, id, Optional.ofNullable(projectId), Optional.ofNullable(title), status,
        updatedAt, Optional.ofNullable(role), action, urgency, progress, List.of(),
        Optional.ofNullable(transitionSubject), route);
  }

  /**
   * Reports the facts a transition projection needs, or nothing at all when the row is not a
   * WorkItem. Availability itself is decided in the application layer, never here.
   */
  private static WorkItemTransitionSubject transitionSubject(ResultSet row) throws SQLException {
    return new WorkItemTransitionSubject(
        WorkProjectId.from(row.getObject("project_id").toString()),
        WorkItemStatus.valueOf(row.getString("status")),
        WorkItemSource.valueOf(row.getString("source_provider")).isNative());
  }

  private static void appendScopeFilters(StringBuilder sql, List<Object> args, WorkDeskQuery query) {
    query.projectId().ifPresent(project -> { sql.append(" AND wi.project_id = ?"); args.add(project.value()); });
    query.responsibilityRole().ifPresent(role -> { sql.append(" AND assignment.role = ?"); args.add(role.name()); });
    if (query.onlyNeedsAction()) sql.append(" AND (assignment.role = 'REVIEWER' OR wi.status IN ('IN_REVIEW', 'BLOCKED'))");
  }

  private static boolean needsAction(String role, String status) {
    return "REVIEWER".equals(role) || "IN_REVIEW".equals(status) || "BLOCKED".equals(status)
        || "WAITING".equals(status) || "MANUAL_TAKEOVER".equals(status);
  }

  private static String urgency(String priority) {
    return switch (priority == null ? "NORMAL" : priority) {
      case "URGENT" -> "URGENT";
      case "HIGH" -> "HIGH";
      case "LOW" -> "LOW";
      default -> "NORMAL";
    };
  }

  private static Optional<Integer> progress(String status) {
    return Optional.of(switch (status) {
      case "BACKLOG" -> 0; case "READY" -> 20; case "IN_PROGRESS" -> 60;
      case "IN_REVIEW" -> 80; case "DONE", "ARCHIVED" -> 100; default -> 0;
    });
  }
}
