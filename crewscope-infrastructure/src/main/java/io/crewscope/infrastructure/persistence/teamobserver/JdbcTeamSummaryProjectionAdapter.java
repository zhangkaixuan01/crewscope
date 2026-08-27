package io.crewscope.infrastructure.persistence.teamobserver;

import io.crewscope.application.teamobserver.TeamSummaryProjectionPort;
import io.crewscope.application.teamobserver.TeamSummaryProjectionQuery;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.teamobserver.TeamSummaryDataScope;
import io.crewscope.domain.teamobserver.TeamSummaryEntry;
import io.crewscope.domain.teamobserver.TeamSummarySection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Sanitized, bounded and current-scope SQL projection used only by read-only Observer Tools. */
@Repository
public class JdbcTeamSummaryProjectionAdapter implements TeamSummaryProjectionPort {

    private final JdbcTemplate jdbc;

    public JdbcTeamSummaryProjectionAdapter(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<TeamSummaryEntry> read(TeamSummaryProjectionQuery query) {
        TeamSummaryProjectionQuery required = Objects.requireNonNull(query, "query");
        return switch (required.dataScope()) {
            case TEAM_ACTIVITY -> activity(required);
            case TEAM_INBOX_SUMMARY -> inbox(required);
            case WORK_ITEM_SUMMARY -> workItems(required);
            case TASK_SUMMARY -> tasks(required);
            case ARTIFACT_SUMMARY -> artifacts(required);
        };
    }

    private List<TeamSummaryEntry> activity(TeamSummaryProjectionQuery query) {
        return jdbc.query(
                """
                SELECT event.activity_event_id, event.event_type, event.category
                FROM crewscope.projection_pointer pointer
                JOIN crewscope.activity_event event
                  ON event.organization_id = pointer.organization_id
                 AND event.projection_name = pointer.projection_name
                 AND event.generation = pointer.active_generation
                WHERE pointer.organization_id = ? AND pointer.projection_name = 'team-activity'
                  AND event.team_id = ? AND event.visibility = 'TEAM_MEMBERS'
                ORDER BY event.team_sequence DESC
                LIMIT ?
                """,
                (row, ignored) -> entry(
                        query,
                        activitySection(row.getString("event_type"), row.getString("category")),
                        "Team activity " + safe(row.getString("event_type")),
                        base(query) + "/activity/"
                                + row.getObject("activity_event_id", UUID.class)),
                query.request().organizationId().value(),
                query.request().teamId().value(),
                query.limit());
    }

    private List<TeamSummaryEntry> inbox(TeamSummaryProjectionQuery query) {
        return jdbc.query(
                """
                SELECT item.inbox_item_id, item.item_type, item.priority, item.source_type
                FROM crewscope.projection_pointer pointer
                JOIN crewscope.inbox_item item
                  ON item.organization_id = pointer.organization_id
                 AND item.projection_name = pointer.projection_name
                 AND item.generation = pointer.active_generation
                LEFT JOIN crewscope.inbox_disposition disposition
                  ON disposition.organization_id = item.organization_id
                 AND disposition.team_id = item.team_id
                 AND disposition.member_id = item.member_id
                 AND disposition.inbox_item_id = item.inbox_item_id
                WHERE pointer.organization_id = ? AND pointer.projection_name = 'member-inbox'
                  AND item.team_id = ? AND item.member_id = ? AND item.source_status = 'OPEN'
                  AND COALESCE(disposition.status, 'UNREAD') <> 'ARCHIVED'
                ORDER BY CASE item.priority
                    WHEN 'URGENT' THEN 4 WHEN 'HIGH' THEN 3
                    WHEN 'NORMAL' THEN 2 ELSE 1 END DESC,
                  item.opened_at DESC, item.inbox_item_id DESC
                LIMIT ?
                """,
                (row, ignored) -> entry(
                        query,
                        inboxSection(row.getString("item_type")),
                        safe(row.getString("priority")) + " "
                                + safe(row.getString("item_type")) + " Inbox item from "
                                + safe(row.getString("source_type")),
                        base(query) + "/inbox/"
                                + row.getObject("inbox_item_id", UUID.class)),
                query.request().organizationId().value(),
                query.request().teamId().value(),
                query.request().requestingMemberId().value(),
                query.limit());
    }

    private List<TeamSummaryEntry> workItems(TeamSummaryProjectionQuery query) {
        return jdbc.query(
                """
                SELECT id, project_id, item_key, title, status, priority
                FROM crewscope.work_item
                WHERE organization_id = ? AND team_id = ? AND status <> 'ARCHIVED'
                ORDER BY updated_at DESC, id DESC
                LIMIT ?
                """,
                (row, ignored) -> entry(
                        query,
                        workItemSection(row.getString("status")),
                        safe(row.getString("item_key")) + " · "
                                + safe(row.getString("title")) + " · "
                                + safe(row.getString("status")),
                        base(query) + "/work-projects/"
                                + row.getObject("project_id", UUID.class) + "/work-items/"
                                + row.getObject("id", UUID.class)),
                query.request().organizationId().value(),
                query.request().teamId().value(),
                query.limit());
    }

    private List<TeamSummaryEntry> tasks(TeamSummaryProjectionQuery query) {
        return jdbc.query(
                """
                SELECT id, status
                FROM crewscope.task
                WHERE organization_id = ? AND team_id = ?
                ORDER BY updated_at DESC, id DESC
                LIMIT ?
                """,
                (row, ignored) -> entry(
                        query,
                        taskSection(row.getString("status")),
                        "Task " + row.getObject("id", UUID.class) + " · "
                                + safe(row.getString("status")),
                        base(query) + "/tasks/" + row.getObject("id", UUID.class)),
                query.request().organizationId().value(),
                query.request().teamId().value(),
                query.limit());
    }

    private List<TeamSummaryEntry> artifacts(TeamSummaryProjectionQuery query) {
        return jdbc.query(
                """
                SELECT artifact_id, task_id, kind, content_type, size_bytes
                FROM crewscope.runtime_artifact
                WHERE organization_id = ? AND team_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """,
                (row, ignored) -> entry(
                        query,
                        TeamSummarySection.PROGRESS,
                        safe(row.getString("kind")) + " artifact · "
                                + safe(row.getString("content_type")) + " · "
                                + row.getLong("size_bytes") + " bytes",
                        base(query) + "/tasks/" + row.getObject("task_id", UUID.class)),
                query.request().organizationId().value(),
                query.request().teamId().value(),
                query.limit());
    }

    private static TeamSummaryEntry entry(
            TeamSummaryProjectionQuery query,
            TeamSummarySection section,
            String summary,
            String evidencePath) {
        return new TeamSummaryEntry(
                query.request().organizationId(),
                query.request().teamId(),
                query.request().requestingMemberId(),
                section,
                query.dataScope(),
                safe(summary),
                evidencePath);
    }

    static TeamSummarySection activitySection(String eventType, String category) {
        String event = eventType.toUpperCase(Locale.ROOT);
        if (event.contains("BLOCK") || event.contains("FAIL")) {
            return TeamSummarySection.BLOCKERS;
        }
        return "SYSTEM".equals(category)
                ? TeamSummarySection.ANOMALIES
                : TeamSummarySection.PROGRESS;
    }

    static TeamSummarySection inboxSection(String itemType) {
        return switch (itemType) {
            case "REVIEW" -> TeamSummarySection.REVIEW_BACKLOG;
            case "CONFIRMATION" -> TeamSummarySection.PENDING_CONFIRMATIONS;
            default -> TeamSummarySection.ANOMALIES;
        };
    }

    static TeamSummarySection workItemSection(String status) {
        return switch (status) {
            case "BLOCKED" -> TeamSummarySection.BLOCKERS;
            case "IN_REVIEW" -> TeamSummarySection.REVIEW_BACKLOG;
            default -> TeamSummarySection.PROGRESS;
        };
    }

    static TeamSummarySection taskSection(String status) {
        return switch (status) {
            case "WAITING" -> TeamSummarySection.BLOCKERS;
            case "FAILED", "CANCELLED" -> TeamSummarySection.ANOMALIES;
            default -> TeamSummarySection.PROGRESS;
        };
    }

    private static String base(TeamSummaryProjectionQuery query) {
        return "/api/v1/organizations/" + query.request().organizationId()
                + "/teams/" + query.request().teamId();
    }

    /** DB text is treated as data: collapse controls and cap it before model exposure. */
    static String safe(String value) {
        String normalized = Objects.requireNonNullElse(value, "unknown")
                .codePoints()
                .map(character -> Character.isISOControl(character)
                                || Character.getType(character) == Character.FORMAT
                        ? ' '
                        : character)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString()
                .replaceAll("\\s+", " ")
                .strip();
        if (normalized.isEmpty()) {
            normalized = "unknown";
        }
        return normalized.length() <= TeamSummaryEntry.MAX_SUMMARY_LENGTH
                ? normalized
                : normalized.substring(0, TeamSummaryEntry.MAX_SUMMARY_LENGTH);
    }
}
