package io.crewscope.infrastructure.persistence.workdesk;

import io.crewscope.application.workdesk.WorkDeskItem;
import io.crewscope.application.workdesk.WorkDeskQuery;
import io.crewscope.application.workdesk.WorkDeskRepository;
import io.crewscope.application.workdesk.WorkDeskSection;
import io.crewscope.application.workdesk.WorkDeskSectionPosition;
import io.crewscope.application.workdesk.WorkDeskSummary;
import io.crewscope.application.workitem.WorkItemTransitionSubject;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.time.UtcTimestamp;
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
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL adapter for the derived personal WorkDesk projection.
 *
 * <p>M9b-A06: every section pages on its own — {@code LIMIT sectionLimit + 1} probes for a next
 * page, {@code COUNT(*) OVER()} reports the section's true full-set total, and each page that
 * truncates carries the keyset tail it continues from. The Inbox section orders by the member
 * Inbox's own four keys and shares their cursor predicate, so one desk and the Inbox itself walk
 * the same ordering.
 */
@Repository
public class JdbcWorkDeskRepositoryAdapter implements WorkDeskRepository {
  private static final String PRIORITY_RANK = """
      CASE item.priority
        WHEN 'URGENT' THEN 4 WHEN 'HIGH' THEN 3
        WHEN 'NORMAL' THEN 2 WHEN 'LOW' THEN 1
      END
      """;

  private final JdbcTemplate jdbc;

  public JdbcWorkDeskRepositoryAdapter(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  @Transactional(readOnly = true)
  public WorkDeskSummary summarize(WorkDeskQuery query) {
    WorkDeskQuery value = Objects.requireNonNull(query, "query");
    Instant generatedAt = Instant.now();
    return new WorkDeskSummary(
        value.organizationId().toString(), value.teamId().toString(),
        value.projectId().map(Object::toString), generatedAt,
        List.of(
            reviewItems(value, true, Optional.empty()),
            reviewItems(value, false, Optional.empty()),
            blocked(value, Optional.empty()),
            workItems(value, Optional.empty()),
            executions(value, Optional.empty()),
            inbox(value, Optional.empty())));
  }

  @Override
  @Transactional(readOnly = true)
  public WorkDeskSection summarizeSection(WorkDeskQuery query, WorkDeskSectionPosition position) {
    WorkDeskQuery value = Objects.requireNonNull(query, "query");
    WorkDeskSectionPosition tail = Objects.requireNonNull(position, "position");
    return switch (tail.sectionKey()) {
      case "HUMAN_GATE" -> reviewItems(value, true, Optional.of(tail));
      case "REVIEW" -> reviewItems(value, false, Optional.of(tail));
      case "BLOCKED" -> blocked(value, Optional.of(tail));
      case "WORK_ITEM" -> workItems(value, Optional.of(tail));
      case "TASK_EXECUTION" -> executions(value, Optional.of(tail));
      case "INBOX" -> inbox(value, Optional.of(tail));
      default -> throw new IllegalArgumentException("unknown WorkDesk section: " + tail.sectionKey());
    };
  }

  private WorkDeskSection workItems(WorkDeskQuery query, Optional<WorkDeskSectionPosition> position) {
    StringBuilder sql = new StringBuilder("""
        WITH section AS (
          SELECT wi.id, wi.project_id, wi.title, wi.status, wi.priority, wi.updated_at,
                 wi.source_provider, assignment.role,
                 COUNT(*) OVER() AS section_total
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
    // Scope filters belong to the section definition — the total counts them — while the keyset
    // runs outside, so a continuation reports the same total as the first screen.
    appendScopeFilters(sql, args, query);
    sql.append("\n)\nSELECT * FROM section WHERE TRUE");
    appendUpdatedKeyset(sql, args, position);
    sql.append(" ORDER BY updated_at DESC, id DESC LIMIT ?");
    args.add(query.sectionLimit() + 1);
    List<SectionRow> rows = jdbc.query(sql.toString(), (row, ignored) -> {
      String status = row.getString("status");
      String role = row.getString("role");
      UUID id = row.getObject("id", UUID.class);
      String title = row.getString("title");
      return new SectionRow(
          item("WORK_ITEM", id.toString(), row.getObject("project_id").toString(),
              title, status, row.getTimestamp("updated_at"), role,
              needsAction(role, status), urgency(row.getString("priority")), progress(status),
              transitionSubject(row),
              "/work?team=" + query.teamId() + "&project=" + row.getObject("project_id") + "&workItem=" + id,
              id.toString(), title),
          WorkDeskSectionPosition.of("WORK_ITEM",
              row.getTimestamp("updated_at").toInstant(), id),
          row.getInt("section_total"));
    }, args.toArray());
    return toSection("WORK_ITEM", "我的工作项", 4, rows, query.sectionLimit());
  }

  private WorkDeskSection executions(WorkDeskQuery query, Optional<WorkDeskSectionPosition> position) {
    if (query.responsibilityRole().filter(role -> role != ResponsibilityRole.EXECUTOR).isPresent()) {
      return empty("TASK_EXECUTION", "进行中的执行", 5);
    }
    StringBuilder sql = new StringBuilder("""
        WITH section AS (
          SELECT execution.id, execution.project_id, execution.status, execution.updated_at,
                 task.id AS task_id, task.objective, task.work_item_id,
                 item.title AS work_item_title,
                 COUNT(*) OVER() AS section_total
          FROM crewscope.task_execution execution
          JOIN crewscope.task task
            ON task.organization_id = execution.organization_id
           AND task.team_id = execution.team_id AND task.id = execution.task_id
          JOIN crewscope.work_item item
            ON item.organization_id = task.organization_id AND item.team_id = task.team_id
           AND item.id = task.work_item_id
          WHERE execution.organization_id = ? AND execution.team_id = ?
            AND execution.status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
            AND (task.created_by_principal_id = ? OR execution.execution_principal_id = ?)
        """);
    List<Object> args = new ArrayList<>(List.of(
        query.organizationId().value(), query.teamId().value(),
        query.principalId().value(), query.principalId().value()));
    query.projectId().ifPresent(project -> { sql.append(" AND execution.project_id = ?"); args.add(project.value()); });
    if (query.onlyNeedsAction()) sql.append(" AND execution.status IN ('WAITING', 'MANUAL_TAKEOVER')");
    sql.append("\n)\nSELECT * FROM section WHERE TRUE");
    appendUpdatedKeyset(sql, args, position);
    sql.append(" ORDER BY updated_at DESC, id DESC LIMIT ?");
    args.add(query.sectionLimit() + 1);
    List<SectionRow> rows = jdbc.query(sql.toString(), (row, ignored) -> {
      String status = row.getString("status");
      String id = row.getObject("id").toString();
      String workItemId = row.getObject("work_item_id").toString();
      return new SectionRow(
          item("TASK_EXECUTION", id, row.getObject("project_id").toString(),
              row.getString("objective"), status, row.getTimestamp("updated_at"), null,
              needsAction(null, status), urgency("NORMAL"), Optional.empty(), null,
              taskExecutionRoute(query, row.getObject("project_id"), row.getObject("task_id"), id),
              workItemId, row.getString("work_item_title")),
          WorkDeskSectionPosition.of("TASK_EXECUTION",
              row.getTimestamp("updated_at").toInstant(), row.getObject("id", UUID.class)),
          row.getInt("section_total"));
    }, args.toArray());
    return toSection("TASK_EXECUTION", "进行中的执行", 5, rows, query.sectionLimit());
  }

  /**
   * The Task coordinate belongs to this contract: the board opens the Task detail from `task=`, so
   * an execution link without it lands on a board with nothing selected.
   */
  static String taskExecutionRoute(WorkDeskQuery query, Object projectId, Object taskId, String executionId) {
    return "/work?team=" + query.teamId() + "&project=" + projectId
        + "&task=" + taskId + "&taskExecution=" + executionId;
  }

  private WorkDeskSection reviewItems(
      WorkDeskQuery query, boolean gate, Optional<WorkDeskSectionPosition> position) {
    String key = gate ? "HUMAN_GATE" : "REVIEW";
    String title = gate ? "等我决策" : "待我 Review";
    if (query.responsibilityRole().filter(role -> role != ResponsibilityRole.REVIEWER).isPresent()) {
      return empty(key, title, gate ? 1 : 2);
    }
    // EXISTS rather than a joining assignment row: the projection must appear once per review, so
    // the section total counts review requests, not assignment matches.
    StringBuilder sql = new StringBuilder("""
        WITH section AS (
          SELECT projection.review_request_id, projection.project_id,
                 projection.request_status, projection.projected_at, task.objective,
                 projection.latest_decision_type, task.work_item_id,
                 item.title AS work_item_title,
                 COUNT(*) OVER() AS section_total
          FROM crewscope.review_request_projection projection
          JOIN crewscope.task task
            ON task.organization_id = projection.organization_id
           AND task.team_id = projection.team_id AND task.id = projection.task_id
          JOIN crewscope.work_item item
            ON item.organization_id = task.organization_id AND item.team_id = task.team_id
           AND item.id = task.work_item_id
          WHERE projection.organization_id = ? AND projection.team_id = ?
            AND projection.request_status IN ('OPEN', 'IN_PROGRESS')
            AND (projection.latest_decision_type IS NULL OR projection.latest_decision_type = 'COMMENTED')
            AND EXISTS (
              SELECT 1 FROM crewscope.responsibility_assignment assignment
              WHERE assignment.organization_id = task.organization_id
                AND assignment.team_id = task.team_id AND assignment.project_id = task.project_id
                AND assignment.work_item_id = task.work_item_id
                AND assignment.role = 'REVIEWER' AND assignment.status = 'ACTIVE'
                AND assignment.actor_principal_id = ?)
        """);
    List<Object> args = new ArrayList<>(List.of(
        query.organizationId().value(), query.teamId().value(), query.principalId().value()));
    query.projectId().ifPresent(project -> { sql.append(" AND projection.project_id = ?"); args.add(project.value()); });
    if (query.onlyNeedsAction()) sql.append(" AND projection.latest_decision_type IS NULL");
    sql.append("\n)\nSELECT * FROM section WHERE TRUE");
    appendProjectedKeyset(sql, args, position);
    sql.append(" ORDER BY projected_at DESC, review_request_id DESC LIMIT ?");
    args.add(query.sectionLimit() + 1);
    List<SectionRow> rows = jdbc.query(sql.toString(), (row, ignored) -> {
      UUID id = row.getObject("review_request_id", UUID.class);
      return new SectionRow(
          item(key, id.toString(), row.getObject("project_id").toString(), row.getString("objective"),
              row.getString("request_status"), row.getTimestamp("projected_at"), "REVIEWER", true,
              "HIGH", Optional.empty(), null,
              "/work?team=" + query.teamId() + "&project=" + row.getObject("project_id") + "&review=" + id,
              row.getObject("work_item_id").toString(), row.getString("work_item_title")),
          WorkDeskSectionPosition.of(key,
              row.getTimestamp("projected_at").toInstant(), id),
          row.getInt("section_total"));
    }, args.toArray());
    return toSection(key, title, gate ? 1 : 2, rows, query.sectionLimit());
  }

  private WorkDeskSection blocked(WorkDeskQuery query, Optional<WorkDeskSectionPosition> position) {
    if (query.responsibilityRole().filter(role -> role != ResponsibilityRole.REVIEWER).isPresent()
        || query.onlyNeedsAction()) {
      return empty("BLOCKED", "被我阻塞", 3);
    }
    // A blocked-by relation is not a separate domain fact yet; return only explicit blocked
    // WorkItems where the member is the active Reviewer, preserving the existing authority model.
    StringBuilder sql = new StringBuilder("""
        WITH section AS (
          SELECT wi.id, wi.project_id, wi.title, wi.status, wi.updated_at, wi.source_provider,
                 COUNT(*) OVER() AS section_total
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
    sql.append("\n)\nSELECT * FROM section WHERE TRUE");
    appendUpdatedKeyset(sql, args, position);
    sql.append(" ORDER BY updated_at DESC, id DESC LIMIT ?");
    args.add(query.sectionLimit() + 1);
    List<SectionRow> rows = jdbc.query(sql.toString(), (row, ignored) -> {
      UUID id = row.getObject("id", UUID.class);
      String title = row.getString("title");
      return new SectionRow(
          item("WORK_ITEM", id.toString(), row.getObject("project_id").toString(), title, "BLOCKED",
              row.getTimestamp("updated_at"), "REVIEWER", false, "HIGH", Optional.of(0),
              transitionSubject(row),
              "/work?team=" + query.teamId() + "&project=" + row.getObject("project_id") + "&workItem=" + id,
              id.toString(), title),
          WorkDeskSectionPosition.of("BLOCKED",
              row.getTimestamp("updated_at").toInstant(), id),
          row.getInt("section_total"));
    }, args.toArray());
    return toSection("BLOCKED", "被我阻塞", 3, rows, query.sectionLimit());
  }

  /** The member's unread Inbox rows, in the Inbox's own ordering. */
  private WorkDeskSection inbox(WorkDeskQuery query, Optional<WorkDeskSectionPosition> position) {
    StringBuilder sql = new StringBuilder("WITH section AS (\n"
        + "  SELECT item.inbox_item_id, item.item_type, item.priority, item.deadline, item.opened_at,\n"
        + "         item.source_status, ").append(PRIORITY_RANK).append(" AS priority_rank,\n"
        + "         COUNT(*) OVER() AS section_total\n"
        + """
          FROM crewscope.projection_pointer pointer
          JOIN crewscope.inbox_item item ON item.organization_id = pointer.organization_id
            AND item.projection_name = pointer.projection_name AND item.generation = pointer.active_generation
          LEFT JOIN crewscope.inbox_disposition disposition ON disposition.organization_id = item.organization_id
            AND disposition.team_id = item.team_id AND disposition.member_id = item.member_id
            AND disposition.inbox_item_id = item.inbox_item_id
          WHERE pointer.organization_id = ? AND pointer.projection_name = 'member-inbox'
            AND item.team_id = ? AND item.member_id = ? AND item.source_status = 'OPEN'
            AND disposition.status IS NULL
        """);
    List<Object> args = new ArrayList<>(List.of(
        query.organizationId().value(), query.teamId().value(), query.memberId().value()));
    sql.append("\n)\nSELECT * FROM section WHERE TRUE");
    appendInboxKeyset(sql, args, position);
    sql.append(" ORDER BY priority_rank DESC, deadline ASC NULLS LAST, opened_at DESC, ")
        .append("inbox_item_id DESC LIMIT ?");
    args.add(query.sectionLimit() + 1);
    List<SectionRow> rows = jdbc.query(sql.toString(), (row, ignored) -> {
      String itemType = row.getString("item_type");
      UUID id = row.getObject("inbox_item_id", UUID.class);
      Timestamp deadline = row.getTimestamp("deadline");
      return new SectionRow(
          item("INBOX", id.toString(), null,
              itemType, row.getString("source_status"), row.getTimestamp("opened_at"), null,
              true, urgency(row.getString("priority")), Optional.empty(), null,
              "/inbox?team=" + query.teamId(), null, null),
          WorkDeskSectionPosition.inbox(
              priorityRank(row.getString("priority")),
              Optional.ofNullable(deadline).map(value -> UtcTimestamp.from(value.toInstant())),
              row.getTimestamp("opened_at").toInstant(), id),
          row.getInt("section_total"));
    }, args.toArray());
    return toSection("INBOX", "未读 Inbox", 6, rows, query.sectionLimit());
  }

  /** One section row plus the keyset tail and full-set total it was read with. */
  private record SectionRow(WorkDeskItem item, WorkDeskSectionPosition tail, int total) {}

  private static WorkDeskSection toSection(
      String key, String title, int priority, List<SectionRow> rows, int limit) {
    boolean truncated = rows.size() > limit;
    List<SectionRow> page = truncated ? rows.subList(0, limit) : rows;
    // COUNT(*) OVER() repeats the same full-set total on every row; an empty page read zero rows.
    int total = rows.isEmpty() ? 0 : rows.get(0).total();
    return new WorkDeskSection(key, title, priority, total, truncated,
        page.stream().map(SectionRow::item).toList(),
        truncated && !page.isEmpty()
            ? Optional.of(page.get(page.size() - 1).tail())
            : Optional.empty());
  }

  private static WorkDeskSection empty(String key, String title, int priority) {
    return new WorkDeskSection(key, title, priority, 0, false, List.of());
  }

  /**
   * {@code (updated_at, id) < (position)} in the section's DESC ordering, over the section CTE's
   * bare column names.
   */
  private static void appendUpdatedKeyset(
      StringBuilder sql, List<Object> args, Optional<WorkDeskSectionPosition> position) {
    if (position.isEmpty()) {
      return;
    }
    WorkDeskSectionPosition tail = position.orElseThrow();
    sql.append(" AND (updated_at < ? OR (updated_at = ? AND id < ?))");
    args.add(tail.sortTime().toOffsetDateTime());
    args.add(tail.sortTime().toOffsetDateTime());
    args.add(tail.sortId());
  }

  /** {@code (projected_at, review_request_id) < (position)} over the section CTE's columns. */
  private static void appendProjectedKeyset(
      StringBuilder sql, List<Object> args, Optional<WorkDeskSectionPosition> position) {
    if (position.isEmpty()) {
      return;
    }
    WorkDeskSectionPosition tail = position.orElseThrow();
    sql.append(" AND (projected_at < ? OR (projected_at = ? AND review_request_id < ?))");
    args.add(tail.sortTime().toOffsetDateTime());
    args.add(tail.sortTime().toOffsetDateTime());
    args.add(tail.sortId());
  }

  /**
   * The Inbox's four-key cursor predicate, NULL-deadline branches included, over the section CTE's
   * {@code priority_rank} column.
   */
  private static void appendInboxKeyset(
      StringBuilder sql, List<Object> args, Optional<WorkDeskSectionPosition> position) {
    if (position.isEmpty()) {
      return;
    }
    WorkDeskSectionPosition tail = position.orElseThrow();
    int rank = tail.primaryRank().orElseThrow();
    sql.append(" AND (priority_rank < ? OR (priority_rank = ? AND (");
    args.add(rank);
    args.add(rank);
    if (tail.deadline().isPresent()) {
      sql.append("deadline > ? OR deadline IS NULL OR ")
          .append("(deadline = ? AND (opened_at < ? OR ")
          .append("(opened_at = ? AND inbox_item_id < ?)))");
      args.add(tail.deadline().orElseThrow().toOffsetDateTime());
      args.add(tail.deadline().orElseThrow().toOffsetDateTime());
    } else {
      sql.append("deadline IS NULL AND (opened_at < ? OR ")
          .append("(opened_at = ? AND inbox_item_id < ?))");
    }
    args.add(tail.sortTime().toOffsetDateTime());
    args.add(tail.sortTime().toOffsetDateTime());
    args.add(tail.sortId());
    sql.append(")))");
  }

  private static int priorityRank(String priority) {
    return switch (priority == null ? "NORMAL" : priority) {
      case "URGENT" -> 4;
      case "HIGH" -> 3;
      case "LOW" -> 1;
      default -> 2;
    };
  }

  private static WorkDeskItem item(String type, String id, String projectId, String title, String status,
      Timestamp updatedAt, String role, boolean action, String urgency, Optional<Integer> progress,
      WorkItemTransitionSubject transitionSubject, String route,
      String workItemId, String workItemTitle) {
    return new WorkDeskItem(type, id, Optional.ofNullable(projectId), Optional.ofNullable(title), status,
        updatedAt.toInstant(), Optional.ofNullable(role), action, urgency, progress, List.of(),
        Optional.ofNullable(transitionSubject), route,
        Optional.ofNullable(workItemId), Optional.ofNullable(workItemTitle),
        Optional.empty(), Optional.empty());
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

  /** Scope filters inside the WORK_ITEM section's own predicate. */
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
