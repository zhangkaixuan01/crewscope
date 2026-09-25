package io.crewscope.infrastructure.persistence.principal;

import io.crewscope.application.principal.PrincipalDirectoryCursor;
import io.crewscope.application.principal.PrincipalDirectoryEntry;
import io.crewscope.application.principal.PrincipalDirectoryPage;
import io.crewscope.application.principal.PrincipalDirectoryPurpose;
import io.crewscope.application.principal.PrincipalDirectoryQuery;
import io.crewscope.application.principal.PrincipalDirectoryRepository;
import io.crewscope.application.principal.PrincipalKind;
import io.crewscope.domain.identity.PrincipalStatus;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.team.TeamMemberId;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL adapter for the full-set Team subject directory (M9b-A06).
 *
 * <p>The authorized candidate set is resolved in a CTE before any filter or page applies: ACTIVE
 * members plus the Agent profiles visible to the viewing member — the whole Team's membership
 * history for an AUDIT purpose. Rows then sort on the byte-stable {@code lower(display_name)
 * COLLATE "C"} key with the principal id as the tie-breaker, so a continuation cursor replays the
 * exact ordering, and each page joins its members' grantable roles in one lateral aggregate
 * instead of one query per member.
 */
@Repository
public class JdbcPrincipalDirectoryRepositoryAdapter implements PrincipalDirectoryRepository {

    /** %s receives the member candidate predicate; the agent candidates follow unchanged. */
    private static final String CANDIDATES = """
            SELECT tm.user_principal_id AS principal_id, 'USER' AS kind, tm.id AS member_id
            FROM crewscope.team_member tm
            WHERE tm.organization_id = :organizationId
              AND tm.team_id = :teamId
              AND tm.status %s
            UNION ALL
            SELECT ap.agent_principal_id, 'AGENT', NULL::uuid
            FROM crewscope.agent_profile ap
            WHERE ap.organization_id = :organizationId
              AND ap.team_id = :teamId
              AND ap.status = 'ACTIVE'
              AND (ap.ownership_type = 'TEAM' OR ap.owner_member_id = :viewerMemberId)
            """;

    private static final String ORDERING = "lower(p.display_name) COLLATE \"C\", c.principal_id";

    private static final String SELECT = """
            SELECT c.principal_id, c.kind, p.display_name, p.status,
                   COALESCE(r.role_keys, '{}'::varchar[]) AS roles,
                   lower(p.display_name) COLLATE "C" AS sort_key
            FROM (%s) c
            JOIN crewscope.principal p
              ON p.organization_id = :organizationId AND p.id = c.principal_id
            LEFT JOIN LATERAL (
                SELECT array_agg(DISTINCT tr.role_key) AS role_keys
                FROM crewscope.team_member_role mr
                JOIN crewscope.team_role tr
                  ON tr.organization_id = mr.organization_id
                 AND tr.team_id = mr.team_id
                 AND tr.id = mr.team_role_id
                WHERE mr.organization_id = :organizationId
                  AND mr.team_id = :teamId
                  AND mr.team_member_id = c.member_id
                  AND mr.status = 'ACTIVE'
                  AND mr.valid_from <= :now
                  AND (mr.expires_at IS NULL OR mr.expires_at > :now)
                  AND tr.status = 'ACTIVE'
            ) r ON TRUE
            WHERE %s
            ORDER BY %s
            LIMIT :probe
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcPrincipalDirectoryRepositoryAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbc = new NamedParameterJdbcTemplate(
                Objects.requireNonNull(jdbcTemplate, "jdbcTemplate"));
    }

    @Override
    @Transactional(readOnly = true)
    public PrincipalDirectoryPage search(
            PrincipalDirectoryQuery query, TeamMemberId viewerMemberId) {
        PrincipalDirectoryQuery value = Objects.requireNonNull(query, "query");
        TeamMemberId viewer = Objects.requireNonNull(viewerMemberId, "viewerMemberId");

        String predicates = String.join("\n  AND ", conditions(value));
        String sql = SELECT.formatted(candidates(value.purpose()), predicates, ORDERING);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("organizationId", value.organizationId().value())
                .addValue("teamId", value.teamId().value())
                .addValue("viewerMemberId", viewer.value())
                .addValue("now", Timestamp.from(Instant.now()))
                .addValue("probe", value.limit() + 1);
        if (!value.ids().isEmpty()) {
            parameters.addValue("ids",
                    value.ids().stream().map(PrincipalId::value).toList());
        }
        if (!value.types().isEmpty()) {
            parameters.addValue("kinds", value.types().stream().map(Enum::name).toList());
        }
        value.namePrefix().ifPresent(prefix ->
                parameters.addValue("prefix", escapeLike(prefix.toLowerCase(Locale.ROOT)) + "%"));
        value.cursor().ifPresent(cursor -> {
            parameters.addValue("sortKey", cursor.sortKey());
            parameters.addValue("afterId", cursor.principalId().value());
        });
        if (value.cursor().isEmpty() && value.offset() > 0) {
            sql += " OFFSET :offset";
            parameters.addValue("offset", value.offset());
        }

        List<Row> rows = jdbc.query(sql, parameters, JdbcPrincipalDirectoryRepositoryAdapter::mapRow);
        boolean more = rows.size() > value.limit();
        List<PrincipalDirectoryEntry> items = rows.stream()
                .limit(value.limit())
                .map(Row::toEntry)
                .toList();
        if (!more || !value.ids().isEmpty()) {
            return new PrincipalDirectoryPage(items, OptionalInt.empty(), Optional.empty());
        }
        // A truncating page always carries the keyset tail, so a cursor client may start from the
        // plain first page; the legacy nextOffset joins it only for an offset query.
        Row tail = rows.get(value.limit() - 1);
        PrincipalDirectoryCursor tailCursor =
                new PrincipalDirectoryCursor(tail.sortKey(), tail.principalId());
        return value.cursor().isPresent()
                ? new PrincipalDirectoryPage(items, OptionalInt.empty(), Optional.of(tailCursor))
                : new PrincipalDirectoryPage(
                        items, OptionalInt.of(value.offset() + value.limit()),
                        Optional.of(tailCursor));
    }

    private static List<String> conditions(PrincipalDirectoryQuery value) {
        List<String> predicates = new ArrayList<>();
        if (!value.ids().isEmpty()) {
            predicates.add("c.principal_id IN (:ids)");
        }
        if (!value.types().isEmpty()) {
            predicates.add("c.kind IN (:kinds)");
        }
        value.namePrefix().ifPresent(prefix ->
                predicates.add("lower(p.display_name) COLLATE \"C\" LIKE :prefix ESCAPE '\\'"));
        value.cursor().ifPresent(ignored ->
                predicates.add("(%s) > (:sortKey, :afterId)".formatted(ORDERING)));
        if (predicates.isEmpty()) {
            predicates.add("TRUE");
        }
        return predicates;
    }

    /** ACTIVE members only for ASSIGNMENT; the full membership history for an AUDIT purpose. */
    private static String candidates(PrincipalDirectoryPurpose purpose) {
        String membership = purpose == PrincipalDirectoryPurpose.AUDIT
                ? "IS NOT NULL"
                : "= 'ACTIVE'";
        return CANDIDATES.formatted(membership);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private record Row(
            PrincipalId principalId,
            PrincipalKind kind,
            String displayName,
            PrincipalStatus status,
            List<String> roles,
            String sortKey) {

        PrincipalDirectoryEntry toEntry() {
            return new PrincipalDirectoryEntry(
                    principalId, kind, displayName, status, roles.stream().sorted().toList());
        }
    }

    private static Row mapRow(ResultSet row, int index) throws SQLException {
        Array roleArray = null;
        try {
            List<String> roles = new ArrayList<>();
            roleArray = row.getArray("roles");
            if (roleArray != null) {
                for (Object element : (Object[]) roleArray.getArray()) {
                    roles.add(String.valueOf(element));
                }
            }
            return new Row(
                    new PrincipalId(java.util.UUID.fromString(row.getString("principal_id"))),
                    PrincipalKind.valueOf(row.getString("kind")),
                    row.getString("display_name"),
                    PrincipalStatus.valueOf(row.getString("status")),
                    roles,
                    row.getString("sort_key"));
        } finally {
            if (roleArray != null) {
                roleArray.free();
            }
        }
    }
}
