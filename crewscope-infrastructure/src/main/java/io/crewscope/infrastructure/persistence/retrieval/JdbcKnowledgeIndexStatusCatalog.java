package io.crewscope.infrastructure.persistence.retrieval;

import io.crewscope.application.retrieval.KnowledgeIndexStatus;
import io.crewscope.application.retrieval.KnowledgeIndexStatusCatalog;
import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Derived index-status projection (M10-I01b): INDEXED when a vector row exists for the
 * head's effective revision of a PUBLISHED entry, FAILED when the latest job of the
 * entry failed, PENDING otherwise. The status is computed, never stored — a stored
 * column would drift from the vector rows it summarizes. The batch form answers entry
 * listings with one query instead of one per row. Deliberately not a {@code @Repository}:
 * the query touches the vector-chain tables, so the server assembly only creates this
 * bean when the vector store is enabled.
 */
public class JdbcKnowledgeIndexStatusCatalog implements KnowledgeIndexStatusCatalog {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcKnowledgeIndexStatusCatalog(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public KnowledgeIndexStatus statusOf(
            OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId) {
        Objects.requireNonNull(entryId, "entryId");
        Map<KnowledgeEntryId, KnowledgeIndexStatus> statuses = statusesOf(
                organizationId, teamId, List.of(entryId));
        return statuses.getOrDefault(entryId, KnowledgeIndexStatus.PENDING);
    }

    @Override
    public Map<KnowledgeEntryId, KnowledgeIndexStatus> statusesOf(
            OrganizationId organizationId, TeamId teamId, List<KnowledgeEntryId> entryIds) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(entryIds, "entryIds");
        List<UUID> ids = entryIds.stream()
                .map(id -> Objects.requireNonNull(id, "entryIds element").value())
                .distinct()
                .toList();
        Map<KnowledgeEntryId, KnowledgeIndexStatus> statuses = new HashMap<>();
        if (ids.isEmpty()) {
            return statuses;
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("organizationId", organizationId.value())
                .addValue("teamId", teamId.value())
                .addValue("entryIds", ids);
        jdbc.query("""
                SELECT e.id AS entry_id,
                       CASE
                           WHEN e.status = 'PUBLISHED' AND EXISTS (
                               SELECT 1 FROM crewscope.knowledge_entry_embedding v
                               WHERE v.organization_id = e.organization_id
                                 AND v.team_id = e.team_id
                                 AND v.entry_id = e.id
                                 AND v.revision = e.effective_revision)
                               THEN 'INDEXED'
                           WHEN (SELECT j.status
                                 FROM crewscope.knowledge_index_job j
                                 WHERE j.organization_id = e.organization_id
                                   AND j.team_id = e.team_id
                                   AND j.entry_id = e.id
                                 ORDER BY j.updated_at DESC, j.id DESC
                                 LIMIT 1) = 'FAILED'
                               THEN 'FAILED'
                           ELSE 'PENDING'
                       END AS index_status
                FROM crewscope.knowledge_entry e
                WHERE e.organization_id = :organizationId
                  AND e.team_id = :teamId
                  AND e.id IN (:entryIds)
                """, parameters, (row, number) -> {
            statuses.put(
                    new KnowledgeEntryId(row.getObject("entry_id", UUID.class)),
                    KnowledgeIndexStatus.valueOf(row.getString("index_status")));
            return null;
        });
        return statuses;
    }
}
