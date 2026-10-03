package io.crewscope.infrastructure.persistence.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.retrieval.KnowledgeEmbeddingVector;
import io.crewscope.application.retrieval.KnowledgeIndexJob;
import io.crewscope.application.retrieval.KnowledgeIndexStatus;
import io.crewscope.application.retrieval.KnowledgeIndexStatusCatalog;
import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.knowledge.KnowledgeEntryRevision;
import io.crewscope.domain.retrieval.EmbeddingModelRevision;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.infrastructure.persistence.knowledge.PgVectorKnowledgeEmbeddingStore;
import io.crewscope.infrastructure.testcontainers.AbstractPgVectorContainerIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * PostgreSQL proof for the derived index-status projection (M10-I01b): INDEXED is
 * computed from a vector row matching the PUBLISHED head's effective revision, FAILED
 * follows the latest job of the entry, and everything else — including retired heads
 * with stale vectors — stays PENDING. The batch form answers listings consistently
 * with the single-entry form.
 */
class KnowledgeIndexStatusCatalogIntegrationTest extends AbstractPgVectorContainerIntegrationTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-03T08:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(30);
    private static final EmbeddingModelRevision MODEL =
            new EmbeddingModelRevision("text-embedding-v4", 1024, 1);

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final PrincipalId actor = PrincipalId.generate();

    private JdbcTemplate jdbc;
    private JdbcKnowledgeIndexJobRepositoryAdapter jobs;
    private PgVectorKnowledgeEmbeddingStore vectors;
    private KnowledgeIndexStatusCatalog catalog;

    @BeforeEach
    void freshDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                PGVECTOR.getJdbcUrl(), PGVECTOR.getUsername(), PGVECTOR.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jobs = new JdbcKnowledgeIndexJobRepositoryAdapter(
                new NamedParameterJdbcTemplate(dataSource));
        vectors = new PgVectorKnowledgeEmbeddingStore(jdbc);
        catalog = new JdbcKnowledgeIndexStatusCatalog(
                new NamedParameterJdbcTemplate(dataSource));
        jdbc.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        jdbc.execute("DROP EXTENSION IF EXISTS vector CASCADE");
        migrateDefaultChain();
        migrateVectorChain();
        seedTenant();
    }

    @Test
    void marksAnEntryIndexedOnlyWhenVectorsMatchTheEffectiveRevision() {
        UUID entry = seedEntry("runbook", "PUBLISHED", 1L);
        seedVersion(entry, 1L);
        vectors.replace(vector(entry, 1, unitVector(0)));

        assertEquals(
                KnowledgeIndexStatus.INDEXED,
                catalog.statusOf(organizationId, teamId, id(entry)));

        // The head moved to revision 2 while the vector row still serves revision 1:
        // the entry drops back to PENDING instead of serving stale vectors.
        seedVersion(entry, 2L);
        seedEntryEffective(entry, 2L);
        assertEquals(
                KnowledgeIndexStatus.PENDING,
                catalog.statusOf(organizationId, teamId, id(entry)));
    }

    @Test
    void surfacesTheLatestFailedJobBehindAnIndexedVector() {
        UUID indexed = seedEntry("indexed", "PUBLISHED", 1L);
        seedVersion(indexed, 1L);
        vectors.replace(vector(indexed, 1, unitVector(0)));
        failLatestJob(id(indexed));
        assertEquals(
                KnowledgeIndexStatus.INDEXED,
                catalog.statusOf(organizationId, teamId, id(indexed)),
                "a present effective vector wins over the failed history");

        UUID failed = seedEntry("failed", "PUBLISHED", 1L);
        seedVersion(failed, 1L);
        failLatestJob(id(failed));
        assertEquals(
                KnowledgeIndexStatus.FAILED,
                catalog.statusOf(organizationId, teamId, id(failed)));
    }

    @Test
    void defaultsToPendingForRetiredHeadsAndStrangers() {
        UUID retired = seedEntry("retired", "RETIRED", 1L);
        seedVersion(retired, 1L);
        vectors.replace(vector(retired, 1, unitVector(0)));
        assertEquals(
                KnowledgeIndexStatus.PENDING,
                catalog.statusOf(organizationId, teamId, id(retired)),
                "a non-PUBLISHED head is never INDEXED even with vectors on disk");

        UUID untouched = seedEntry("untouched", "PUBLISHED", 1L);
        seedVersion(untouched, 1L);
        assertEquals(
                KnowledgeIndexStatus.PENDING,
                catalog.statusOf(organizationId, teamId, id(untouched)));

        assertEquals(
                KnowledgeIndexStatus.PENDING,
                catalog.statusOf(OrganizationId.generate(), TeamId.generate(), id(retired)),
                "another tenant's projection never leaks");
    }

    @Test
    void batchesIntoOneConsistentProjection() {
        UUID indexed = seedEntry("indexed", "PUBLISHED", 1L);
        seedVersion(indexed, 1L);
        vectors.replace(vector(indexed, 1, unitVector(0)));
        UUID failed = seedEntry("failed", "PUBLISHED", 1L);
        seedVersion(failed, 1L);
        failLatestJob(id(failed));
        UUID pending = seedEntry("pending", "PUBLISHED", 1L);
        seedVersion(pending, 1L);

        Map<KnowledgeEntryId, KnowledgeIndexStatus> batched = catalog.statusesOf(
                organizationId, teamId, List.of(id(indexed), id(failed), id(pending)));

        assertEquals(3, batched.size());
        assertEquals(KnowledgeIndexStatus.INDEXED, batched.get(id(indexed)));
        assertEquals(KnowledgeIndexStatus.FAILED, batched.get(id(failed)));
        assertEquals(KnowledgeIndexStatus.PENDING, batched.get(id(pending)));
        batched.forEach((entryId, status) -> assertEquals(
                status, catalog.statusOf(organizationId, teamId, entryId),
                "the batch and single forms agree on " + entryId));
    }

    // ------------------------------------------------------------------ fixtures

    private void failLatestJob(KnowledgeEntryId entry) {
        jobs.create(KnowledgeIndexJob.knowledgeEntry(
                UUID.randomUUID(), organizationId, teamId, entry, actor, NOW));
        KnowledgeIndexJob claim = jobs.claimNext("worker-test", NOW, LEASE).orElseThrow();
        UtcTimestamp done = UtcTimestamp.from(NOW.value().plusSeconds(30));
        assertTrue(jobs.updateClaimed(
                claim.failed("CHUNK_TOO_LARGE", done), "worker-test", done, LEASE).isPresent());
    }

    private KnowledgeEmbeddingVector vector(UUID entryId, long revision, float[] embedding) {
        return new KnowledgeEmbeddingVector(
                organizationId, teamId, id(entryId), new KnowledgeEntryRevision(revision),
                MODEL, "a".repeat(64), embedding);
    }

    private static KnowledgeEntryId id(UUID value) {
        return new KnowledgeEntryId(value);
    }

    private static float[] unitVector(int axis) {
        float[] vector = new float[MODEL.dimension()];
        vector[axis] = 1.0f;
        return vector;
    }

    private void migrateDefaultChain() {
        Flyway.configure()
                .dataSource(PGVECTOR.getJdbcUrl(), PGVECTOR.getUsername(), PGVECTOR.getPassword())
                .locations("classpath:db/migration")
                .schemas("crewscope")
                .defaultSchema("crewscope")
                .load()
                .migrate();
    }

    private void migrateVectorChain() {
        Flyway.configure()
                .dataSource(PGVECTOR.getJdbcUrl(), PGVECTOR.getUsername(), PGVECTOR.getPassword())
                .locations("classpath:db/migration-vector")
                .schemas("crewscope")
                .defaultSchema("crewscope")
                .table("flyway_vector_history")
                .validateMigrationNaming(true)
                .createSchemas(false)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    private void seedTenant() {
        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Status Org', 'ACTIVE')",
                organizationId.value());
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status) VALUES (?, ?, 'Status Team', 'ACTIVE')",
                teamId.value(), organizationId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.principal (id, organization_id, principal_type, display_name, status)
                VALUES (?, ?, 'USER', 'Status owner', 'ACTIVE')
                """,
                actor.value(), organizationId.value());
    }

    private UUID seedEntry(String entryKey, String status, Long effectiveRevision) {
        UUID entryId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO crewscope.knowledge_entry (
                    id, organization_id, team_id, entry_key, status, effective_revision,
                    latest_revision, version, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, now(), ?, now(), ?)
                """,
                entryId, organizationId.value(), teamId.value(), entryKey, status,
                effectiveRevision, effectiveRevision == null ? 0L : effectiveRevision,
                actor.value(), actor.value());
        return entryId;
    }

    private void seedVersion(UUID entryId, long revision) {
        jdbc.update(
                """
                INSERT INTO crewscope.knowledge_entry_version (
                    organization_id, team_id, entry_id, revision, previous_revision,
                    title, content, content_hash, created_at, created_by_principal_id)
                VALUES (?, ?, ?, ?, ?, 'Title', 'Content', ?, now(), ?)
                """,
                organizationId.value(), teamId.value(), entryId, revision,
                revision == 1 ? null : revision - 1,
                UUID.nameUUIDFromBytes(("content-" + entryId + "-" + revision).getBytes())
                                .toString().replace("-", "") + "0".repeat(32),
                actor.value());
    }

    private void seedEntryEffective(UUID entryId, long effectiveRevision) {
        jdbc.update(
                "UPDATE crewscope.knowledge_entry SET effective_revision = ?, latest_revision = ?"
                        + " WHERE id = ?",
                effectiveRevision, effectiveRevision, entryId);
    }
}
