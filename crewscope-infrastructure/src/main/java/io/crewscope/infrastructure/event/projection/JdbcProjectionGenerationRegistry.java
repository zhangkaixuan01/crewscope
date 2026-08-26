package io.crewscope.infrastructure.event.projection;

import io.crewscope.domain.projection.ProjectionFencingToken;
import io.crewscope.domain.projection.ProjectionDefinition;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionGenerationKey;
import io.crewscope.domain.projection.ProjectionGenerationLease;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Reads the durable Projection Registry for every event instead of caching process-local routes. */
@Repository
public class JdbcProjectionGenerationRegistry {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate bootstrapTransaction;

    public JdbcProjectionGenerationRegistry(
            JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.bootstrapTransaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.bootstrapTransaction.setName("crewscope-projection-bootstrap");
        this.bootstrapTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Atomically registers a Projection and its first active Generation for a new Organization.
     * Existing Generations are never reactivated; a conflicting Definition fails closed.
     */
    public void bootstrapIfAbsent(UUID organizationId, ProjectionDefinition definition) {
        UUID organization = Objects.requireNonNull(organizationId, "organizationId");
        ProjectionDefinition projection = Objects.requireNonNull(definition, "definition");
        bootstrapTransaction.executeWithoutResult(status -> bootstrap(organization, projection));
    }

    private void bootstrap(UUID organization, ProjectionDefinition projection) {
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.projection_definition (
                    projection_name, definition_version, projection_schema_version,
                    canonical_encoder, validator
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (projection_name, definition_version) DO NOTHING
                """,
                projection.name().value(),
                projection.version().value(),
                projection.projectionSchemaVersion().value(),
                projection.canonicalEncoder(),
                projection.validator());
        Integer matched = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM crewscope.projection_definition
                WHERE projection_name = ? AND definition_version = ?
                  AND projection_schema_version = ?
                  AND canonical_encoder = ? AND validator = ?
                """,
                Integer.class,
                projection.name().value(),
                projection.version().value(),
                projection.projectionSchemaVersion().value(),
                projection.canonicalEncoder(),
                projection.validator());
        if (matched == null || matched != 1) {
            throw new IllegalStateException(
                    "Projection Definition conflicts with the registered runtime handler");
        }
        jdbcTemplate.update(
                """
                WITH created_generation AS (
                    INSERT INTO crewscope.projection_generation (
                        organization_id, projection_name, generation, definition_version,
                        status, fencing_token, version, created_at, updated_at
                    )
                    SELECT ?, ?, 1, ?, 'ACTIVE', 1, 0,
                           CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                    WHERE NOT EXISTS (
                        SELECT 1 FROM crewscope.projection_generation
                        WHERE organization_id = ? AND projection_name = ?
                    )
                    ON CONFLICT (organization_id, projection_name, generation) DO NOTHING
                    RETURNING generation
                ), active_generation AS (
                    SELECT generation FROM created_generation
                    UNION ALL
                    SELECT generation
                    FROM crewscope.projection_generation
                    WHERE organization_id = ? AND projection_name = ? AND status = 'ACTIVE'
                )
                INSERT INTO crewscope.projection_pointer (
                    organization_id, projection_name, active_generation, version, updated_at
                )
                SELECT ?, ?, generation, 0, CURRENT_TIMESTAMP
                FROM active_generation
                ORDER BY generation
                LIMIT 1
                ON CONFLICT (organization_id, projection_name) DO NOTHING
                """,
                organization,
                projection.name().value(),
                projection.version().value(),
                organization,
                projection.name().value(),
                organization,
                projection.name().value(),
                organization,
                projection.name().value());
    }

    /** Returns ACTIVE first, followed by writable shadow Generations in numeric order. */
    public List<ProjectionGenerationLease> writableLeases(
            UUID organizationId, ProjectionName projectionName) {
        OrganizationId organization = new OrganizationId(
                Objects.requireNonNull(organizationId, "organizationId"));
        ProjectionName projection = Objects.requireNonNull(projectionName, "projectionName");
        return jdbcTemplate.query(
                """
                SELECT generation, fencing_token
                FROM crewscope.projection_generation
                WHERE organization_id = ?
                  AND projection_name = ?
                  AND status IN ('ACTIVE', 'BUILDING', 'VALIDATING')
                ORDER BY CASE status WHEN 'ACTIVE' THEN 0 ELSE 1 END, generation
                """,
                (resultSet, rowNumber) -> new ProjectionGenerationLease(
                        new ProjectionGenerationKey(
                                organization,
                                projection,
                                new ProjectionGeneration(resultSet.getLong("generation"))),
                        new ProjectionFencingToken(resultSet.getLong("fencing_token"))),
                organization.value(),
                projection.value());
    }
}
