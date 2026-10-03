package io.crewscope.infrastructure.persistence.retrieval;

import io.crewscope.application.retrieval.GenerationCatalog;
import io.crewscope.application.retrieval.GenerationSnapshot;
import io.crewscope.domain.retrieval.RepositoryGenerationKey;
import io.crewscope.domain.retrieval.RepositoryIndexKey;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.coding.RepositoryBindingId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only ACTIVE-generation lookup (M10-I01b): one query behind the partial unique
 * activation index. An empty result is the explicit "no matching generation" that
 * callers must degrade with {@code DegradationReasonCode.NO_MATCHING_GENERATION}.
 */
@Repository
public class JdbcGenerationCatalogAdapter implements GenerationCatalog {

    private final JdbcTemplate jdbc;

    public JdbcGenerationCatalogAdapter(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<GenerationSnapshot> findActiveGeneration(RepositoryIndexKey indexKey) {
        RepositoryIndexKey required = Objects.requireNonNull(indexKey, "indexKey");
        return jdbc.query("""
                SELECT * FROM crewscope.repository_index_generation
                WHERE index_key = ? AND status = 'ACTIVE'
                """, (row, number) -> map(row), IndexKeyCodec.hash(required))
                .stream().findFirst();
    }

    private static GenerationSnapshot map(ResultSet row) throws SQLException {
        RepositoryIndexKey indexKey = IndexKeyCodec.reconstitute(
                new OrganizationId(row.getObject("organization_id", UUID.class)),
                new TeamId(row.getObject("team_id", UUID.class)),
                new RepositoryBindingId(row.getObject("repository_binding_id", UUID.class)),
                row.getString("source_commit"),
                row.getString("chunk_policy_hash"),
                row.getString("model_key"),
                row.getInt("model_dimension"),
                row.getLong("model_revision"));
        return new GenerationSnapshot(
                new RepositoryGenerationKey(indexKey, row.getLong("build_sequence")),
                AuditMetadata.createdBy(
                        new PrincipalId(
                                row.getObject("activated_by_principal_id", UUID.class)),
                        UtcTimestamp.from(
                                row.getObject("activated_at", OffsetDateTime.class)
                                        .toInstant())));
    }
}
