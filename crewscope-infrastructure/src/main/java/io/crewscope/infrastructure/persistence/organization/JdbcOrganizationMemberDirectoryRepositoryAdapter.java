package io.crewscope.infrastructure.persistence.organization;

import io.crewscope.application.organization.OrganizationMemberDirectoryCursor;
import io.crewscope.application.organization.OrganizationMemberDirectoryEntry;
import io.crewscope.application.organization.OrganizationMemberDirectoryPage;
import io.crewscope.application.organization.OrganizationMemberDirectoryQuery;
import io.crewscope.application.organization.OrganizationMemberDirectoryRepository;
import io.crewscope.domain.identity.AccountStatus;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.id.PrincipalId;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 * PostgreSQL adapter for the full-set organization member directory (M13b-A01).
 *
 * <p>The candidate set is the organization's ACTIVE account bindings — the binding edge already
 * guarantees each row is a USER principal of this organization. Rows carry the account's identity
 * and status (never the email), the principal's display name, and the ACTIVE organization role
 * grants aggregated in one lateral join. Sorting follows the byte-stable
 * {@code lower(display_name) COLLATE "C"} key with the principal id as the tie-breaker, so a
 * continuation cursor replays the exact ordering.
 */
@Repository
public class JdbcOrganizationMemberDirectoryRepositoryAdapter
        implements OrganizationMemberDirectoryRepository {

    private static final String ORDERING = "lower(p.display_name) COLLATE \"C\", b.principal_id";

    private static final String SELECT = """
            SELECT b.account_id, b.principal_id, p.display_name, ua.username, ua.status,
                   COALESCE(r.role_keys, '{}'::varchar[]) AS org_roles,
                   lower(p.display_name) COLLATE "C" AS sort_key
            FROM crewscope.account_organization_binding b
            JOIN crewscope.principal p
              ON p.organization_id = b.organization_id AND p.id = b.principal_id
            JOIN crewscope.user_account ua ON ua.id = b.account_id
            LEFT JOIN LATERAL (
                SELECT array_agg(DISTINCT g.role) AS role_keys
                FROM crewscope.organization_role_grant g
                WHERE g.organization_id = :organizationId
                  AND g.principal_id = b.principal_id
                  AND g.status = 'ACTIVE'
            ) r ON TRUE
            WHERE b.organization_id = :organizationId
              AND b.status = 'ACTIVE'
              AND %s
            ORDER BY %s
            LIMIT :probe
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcOrganizationMemberDirectoryRepositoryAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbc = new NamedParameterJdbcTemplate(
                Objects.requireNonNull(jdbcTemplate, "jdbcTemplate"));
    }

    @Override
    @Transactional(readOnly = true)
    public OrganizationMemberDirectoryPage search(OrganizationMemberDirectoryQuery query) {
        OrganizationMemberDirectoryQuery value = Objects.requireNonNull(query, "query");

        String predicates = conditions(value);
        String sql = SELECT.formatted(predicates, ORDERING);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("organizationId", value.organizationId().value())
                .addValue("probe", value.limit() + 1);
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

        List<Row> rows = jdbc.query(sql, parameters, JdbcOrganizationMemberDirectoryRepositoryAdapter::mapRow);
        boolean more = rows.size() > value.limit();
        List<OrganizationMemberDirectoryEntry> items = rows.stream()
                .limit(value.limit())
                .map(Row::toEntry)
                .toList();
        if (!more) {
            return new OrganizationMemberDirectoryPage(
                    items, OptionalInt.empty(), Optional.empty());
        }
        // A truncating page always carries the keyset tail, so a cursor client may start from the
        // plain first page; the legacy nextOffset joins it only for an offset query.
        Row tail = rows.get(value.limit() - 1);
        OrganizationMemberDirectoryCursor tailCursor =
                new OrganizationMemberDirectoryCursor(tail.sortKey(), tail.principalId());
        return value.cursor().isPresent()
                ? new OrganizationMemberDirectoryPage(
                        items, OptionalInt.empty(), Optional.of(tailCursor))
                : new OrganizationMemberDirectoryPage(
                        items, OptionalInt.of(value.offset() + value.limit()),
                        Optional.of(tailCursor));
    }

    /** The prefix matches the display name or the username, case-insensitively and literally. */
    private static String conditions(OrganizationMemberDirectoryQuery value) {
        List<String> predicates = new ArrayList<>();
        value.namePrefix().ifPresent(ignored -> predicates.add(
                "(lower(p.display_name) COLLATE \"C\" LIKE :prefix ESCAPE '\\'"
                        + " OR lower(ua.username) COLLATE \"C\" LIKE :prefix ESCAPE '\\')"));
        value.cursor().ifPresent(ignored ->
                predicates.add("(%s) > (:sortKey, :afterId)".formatted(ORDERING)));
        if (predicates.isEmpty()) {
            predicates.add("TRUE");
        }
        return String.join("\n  AND ", predicates);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private record Row(
            UserAccountId accountId,
            PrincipalId principalId,
            String displayName,
            String username,
            AccountStatus status,
            List<String> orgRoles,
            String sortKey) {

        OrganizationMemberDirectoryEntry toEntry() {
            return new OrganizationMemberDirectoryEntry(
                    accountId, principalId, displayName, username, status,
                    orgRoles.stream().sorted().toList());
        }
    }

    private static Row mapRow(ResultSet row, int index) throws SQLException {
        Array roleArray = null;
        try {
            List<String> roles = new ArrayList<>();
            roleArray = row.getArray("org_roles");
            if (roleArray != null) {
                for (Object element : (Object[]) roleArray.getArray()) {
                    roles.add(String.valueOf(element));
                }
            }
            return new Row(
                    new UserAccountId(java.util.UUID.fromString(row.getString("account_id"))),
                    new PrincipalId(java.util.UUID.fromString(row.getString("principal_id"))),
                    row.getString("display_name"),
                    row.getString("username"),
                    AccountStatus.valueOf(row.getString("status")),
                    roles,
                    row.getString("sort_key"));
        } finally {
            if (roleArray != null) {
                roleArray.free();
            }
        }
    }
}
