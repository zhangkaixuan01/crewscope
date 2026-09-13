package io.crewscope.infrastructure.persistence.search;

import io.crewscope.application.search.SearchCursor;
import io.crewscope.application.search.SearchIndexPort;
import io.crewscope.application.search.SearchQuery;
import io.crewscope.application.search.SearchResultItem;
import io.crewscope.application.search.SearchResultPage;
import io.crewscope.domain.search.SearchableObjectType;
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

/** PostgreSQL keyset search over existing authoritative tables; no secondary search index is stored. */
@Repository
public final class JdbcSearchQueryRepositoryAdapter implements SearchIndexPort {
  private final JdbcTemplate jdbc;

  public JdbcSearchQueryRepositoryAdapter(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc, "jdbc"); }

  @Override
  @Transactional(readOnly = true)
  public SearchResultPage search(SearchQuery query) {
    Objects.requireNonNull(query, "query");
    List<Object> args = new ArrayList<>();
    StringBuilder sql = new StringBuilder("SELECT object_type, object_id, project_id, title, subtitle, status, updated_at FROM (");
    boolean first = true;
    for (SearchableObjectType type : query.types()) {
      if (!first) sql.append(" UNION ALL ");
      appendType(sql, args, query, type);
      first = false;
    }
    sql.append(") candidates WHERE 1=1");
    query.after().ifPresent(cursor -> {
      sql.append(" AND (updated_at, object_id) < (?, ?)");
      args.add(Timestamp.from(cursor.updatedAt())); args.add(cursor.objectId());
    });
    sql.append(" ORDER BY updated_at DESC, object_id DESC LIMIT ?");
    args.add(query.limit() + 1);
    List<SearchRow> rows = jdbc.query(sql.toString(), (row, ignored) -> new SearchRow(
        SearchableObjectType.valueOf(row.getString("object_type")), row.getObject("object_id", UUID.class),
        row.getObject("project_id", UUID.class), row.getString("title"), row.getString("subtitle"),
        row.getString("status"), row.getTimestamp("updated_at").toInstant()), args.toArray());
    boolean hasMore = rows.size() > query.limit();
    List<SearchRow> bounded = hasMore ? rows.subList(0, query.limit()) : rows;
    List<SearchResultItem> items = bounded.stream().map(row -> toItem(query, row)).toList();
    Optional<SearchCursor> next = hasMore && !bounded.isEmpty()
        ? Optional.of(new SearchCursor(bounded.get(bounded.size() - 1).updatedAt(), bounded.get(bounded.size() - 1).objectId()))
        : Optional.empty();
    return new SearchResultPage(items, next);
  }

  private static void appendType(StringBuilder sql, List<Object> args, SearchQuery query, SearchableObjectType type) {
    String term = "%" + query.text().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    switch (type) {
      case WORK_ITEM -> {
        sql.append("SELECT 'WORK_ITEM' object_type, wi.id object_id, wi.project_id, wi.title, wi.item_key subtitle, wi.status, wi.updated_at FROM crewscope.work_item wi WHERE wi.organization_id = ? AND wi.team_id = ? AND wi.status <> 'ARCHIVED' AND (wi.title ILIKE ? ESCAPE '!' OR wi.item_key ILIKE ? ESCAPE '!'");
        args.add(query.organizationId().value()); args.add(query.teamId().value()); args.add(term); args.add(term); project(sql, args, query, "wi.project_id");
      }
      case CONVERSATION -> {
        sql.append("SELECT 'CONVERSATION' object_type, c.id object_id, NULL::uuid project_id, c.title, c.visibility subtitle, c.status, c.updated_at FROM crewscope.conversation c WHERE c.organization_id = ? AND c.team_id = ? AND c.status = 'ACTIVE' AND (c.visibility = 'TEAM' OR c.owner_member_id = ?) AND c.title ILIKE ? ESCAPE '!'");
        args.add(query.organizationId().value()); args.add(query.teamId().value()); args.add(query.memberId().value()); args.add(term);
      }
      case TASK -> {
        sql.append("SELECT 'TASK' object_type, t.id object_id, t.project_id, COALESCE(wi.title, '任务') title, t.source_type subtitle, t.status, t.updated_at FROM crewscope.task t JOIN crewscope.work_item wi ON wi.organization_id = t.organization_id AND wi.team_id = t.team_id AND wi.project_id = t.project_id AND wi.id = t.work_item_id WHERE t.organization_id = ? AND t.team_id = ? AND t.status <> 'CANCELLED' AND (wi.title ILIKE ? ESCAPE '!' OR CAST(t.id AS text) ILIKE ? ESCAPE '!'");
        args.add(query.organizationId().value()); args.add(query.teamId().value()); args.add(term); args.add(term); project(sql, args, query, "t.project_id");
      }
      case REPOSITORY_BINDING -> {
        sql.append("SELECT 'REPOSITORY_BINDING' object_type, rb.id object_id, rb.project_id, rb.repository_key title, rb.repository_kind subtitle, rb.status, rb.updated_at FROM crewscope.repository_binding rb WHERE rb.organization_id = ? AND rb.team_id = ? AND rb.status = 'ACTIVE' AND rb.repository_key ILIKE ? ESCAPE '!'");
        args.add(query.organizationId().value()); args.add(query.teamId().value()); args.add(term); project(sql, args, query, "rb.project_id");
      }
      case AGENT -> {
        sql.append("SELECT 'AGENT' object_type, ap.id object_id, NULL::uuid project_id, p.display_name title, ap.profile_type subtitle, ap.status, ap.updated_at FROM crewscope.agent_profile ap JOIN crewscope.principal p ON p.organization_id = ap.organization_id AND p.id = ap.agent_principal_id WHERE ap.organization_id = ? AND ap.team_id = ? AND ap.status = 'ACTIVE' AND (ap.profile_type = 'TEAM' OR ap.owner_member_id = ?) AND p.display_name ILIKE ? ESCAPE '!'");
        args.add(query.organizationId().value()); args.add(query.teamId().value()); args.add(query.memberId().value()); args.add(term);
      }
      case TEAM_MEMBER -> {
        sql.append("SELECT 'TEAM_MEMBER' object_type, tm.id object_id, NULL::uuid project_id, p.display_name title, 'MEMBER' subtitle, tm.status, tm.updated_at FROM crewscope.team_member tm JOIN crewscope.principal p ON p.organization_id = tm.organization_id AND p.id = tm.user_principal_id WHERE tm.organization_id = ? AND tm.team_id = ? AND tm.status = 'ACTIVE' AND p.display_name ILIKE ? ESCAPE '!'");
        args.add(query.organizationId().value()); args.add(query.teamId().value()); args.add(term);
      }
    }
  }

  private static void project(StringBuilder sql, List<Object> args, SearchQuery query, String column) {
    query.projectId().ifPresent(project -> { sql.append(" AND ").append(column).append(" = ?"); args.add(project.value()); });
  }

  private static SearchResultItem toItem(SearchQuery query, SearchRow row) {
    String team = query.teamId().toString();
    String route = switch (row.type()) {
      case WORK_ITEM -> "/work?team=" + team + "&project=" + row.projectId() + "&workItem=" + row.objectId();
      case CONVERSATION -> "/conversation?team=" + team + "&conversation=" + row.objectId();
      case TASK -> "/work?team=" + team + "&project=" + row.projectId() + "&task=" + row.objectId();
      case REPOSITORY_BINDING -> "/settings/repositories?team=" + team + "&project=" + row.projectId() + "&binding=" + row.objectId();
      case AGENT -> "/settings/agents?team=" + team + "&agent=" + row.objectId();
      case TEAM_MEMBER -> "/team/members?team=" + team + "&member=" + row.objectId();
    };
    return new SearchResultItem(row.type(), row.objectId(), Optional.ofNullable(row.projectId()), row.title(), Optional.ofNullable(row.subtitle()), row.status(), row.updatedAt(), route, Optional.of(row.title()));
  }

  private record SearchRow(SearchableObjectType type, UUID objectId, UUID projectId, String title, String subtitle, String status, Instant updatedAt) {}
}
