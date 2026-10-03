package io.crewscope.infrastructure.persistence.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.retrieval.KnowledgeEmbeddingQuery;
import io.crewscope.application.retrieval.KnowledgeEmbeddingVector;
import io.crewscope.application.retrieval.ScoredKnowledgeEmbedding;
import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.knowledge.KnowledgeEntryRevision;
import io.crewscope.domain.retrieval.EmbeddingModelRevision;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.infrastructure.testcontainers.AbstractPgVectorContainerIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * pgvector proof for the independent vector migration chain (M10-I01a): the chain lands
 * after the default chain on a fresh database, the version foreign key holds across
 * tenants, the frozen HNSW cosine plan serves nearest, the adapter's tenant predicates
 * and effective-revision gate hold, and a tampered history fails fast instead of
 * silently drifting.
 */
class KnowledgeVectorMigrationIntegrationTest extends AbstractPgVectorContainerIntegrationTest {

    private static final EmbeddingModelRevision MODEL =
            new EmbeddingModelRevision("text-embedding-v4", 1024, 1);

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final PrincipalId actor = PrincipalId.generate();

    private JdbcTemplate jdbc;
    private PgVectorKnowledgeEmbeddingStore store;

    @BeforeEach
    void freshDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                PGVECTOR.getJdbcUrl(), PGVECTOR.getUsername(), PGVECTOR.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        // Every test starts from a bare database: the vector extension is dropped so the
        // chain under test installs it itself.
        jdbc.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        jdbc.execute("DROP EXTENSION IF EXISTS vector CASCADE");
        migrateDefaultChain();
        seedTenant(organizationId, teamId, actor);
        store = new PgVectorKnowledgeEmbeddingStore(jdbc);
    }

    @Test
    void appliesTheVectorChainAfterTheDefaultChainOnAFreshDatabase() {
        migrateVectorChain();

        // V1/V2 are this test's frozen subjects (extension + embedding table); later
        // chain members (V3+, repository chunks from M10-I01b) simply migrate with them.
        assertTrue(appliedVectorVersions() >= 2, "the vector chain must apply in full");
        assertTrue(
                jdbc.queryForObject(
                                "SELECT MAX(CAST(version AS integer)) FROM crewscope.flyway_schema_history",
                                Integer.class)
                        >= 54,
                "the default chain must have committed at its V54 tip first"
                        + " (later members, V55+ from M10-I01b, simply commit ahead too)");
        assertEquals(
                "public",
                jdbc.queryForObject(
                        "SELECT extnamespace::regnamespace::text FROM pg_extension WHERE extname = 'vector'",
                        String.class),
                "the extension is installed into public, not crewscope");
        // to_regclass renders a short name when the schema is on the search path, so the
        // proof asserts existence, not the rendered text.
        assertTrue(
                jdbc.queryForObject(
                        "SELECT to_regclass('crewscope.knowledge_entry_embedding') IS NOT NULL",
                        Boolean.class));
    }

    @Test
    void enforcesTheVersionForeignKeyAcrossTenantsAndMissingRows() {
        migrateVectorChain();
        UUID entryId = seedEntry("oncall-runbook", "PUBLISHED", 1L);
        seedVersion(entryId, 1L);
        store.replace(vector(entryId, 1, "a".repeat(64), unitVector(0)));

        OrganizationId stranger = OrganizationId.generate();
        DataIntegrityViolationException crossTenant = assertThrows(
                DataIntegrityViolationException.class,
                () -> store.replace(vector(stranger, entryId, 1, "b".repeat(64), unitVector(0))));
        assertTrue(crossTenant.getMessage().contains("fk_knowledge_embedding_version")
                        || crossTenant.getMessage().contains("violates"),
                "the FK name or SQLSTATE must surface: " + crossTenant.getMessage());

        DataIntegrityViolationException missingRevision = assertThrows(
                DataIntegrityViolationException.class,
                () -> store.replace(vector(entryId, 99, "c".repeat(64), unitVector(0))));
        assertTrue(missingRevision.getMessage().contains("violates"));
    }

    @Test
    void replacesIdempotentlyWithinOneModelSlot() {
        migrateVectorChain();
        UUID entryId = seedEntry("runbook", "PUBLISHED", 1L);
        seedVersion(entryId, 1L);

        store.replace(vector(entryId, 1, "a".repeat(64), unitVector(0)));
        store.replace(vector(entryId, 1, "a".repeat(64), unitVector(1)));
        store.replace(new KnowledgeEmbeddingVector(
                organizationId, teamId, id(entryId), new KnowledgeEntryRevision(1),
                new EmbeddingModelRevision("text-embedding-v4", 1024, 2),
                "d".repeat(64), unitVector(2)));

        assertEquals(1, embeddingRows(), "one row per model slot regardless of rewrites");
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT model_revision FROM crewscope.knowledge_entry_embedding", Long.class)
                        .longValue(),
                "a newer model revision overwrites the slot in place");
    }

    @Test
    void nearestHonoursTheEffectiveGateTenantPredicatesAndTopK() {
        migrateVectorChain();
        // Hit entry: effective revision 1, PUBLISHED, aligned with the query.
        UUID hit = seedEntry("hit", "PUBLISHED", 1L);
        seedVersion(hit, 1L);
        store.replace(vector(hit, 1, "a".repeat(64), unitVector(0)));
        // Superseded entry: head moved to revision 2, its vector row is revision 1.
        UUID superseded = seedEntry("superseded", "PUBLISHED", 2L);
        seedVersion(superseded, 1L);
        seedVersion(superseded, 2L);
        store.replace(vector(superseded, 1, "b".repeat(64), unitVector(0)));
        // Retired entry: effective pointer kept, but status is not PUBLISHED.
        UUID retired = seedEntry("retired", "RETIRED", 1L);
        seedVersion(retired, 1L);
        store.replace(vector(retired, 1, "c".repeat(64), unitVector(0)));
        // Orthogonal neighbor in the same team: visible, ranked below the hit.
        UUID neighbor = seedEntry("neighbor", "PUBLISHED", 1L);
        seedVersion(neighbor, 1L);
        store.replace(vector(neighbor, 1, "d".repeat(64), unitVector(1)));
        // Same-content entry under a different tenant: never visible.
        OrganizationId strangerOrg = OrganizationId.generate();
        TeamId strangerTeam = TeamId.generate();
        seedTenant(strangerOrg, strangerTeam, PrincipalId.generate());
        UUID stranger = seedEntryFor(strangerOrg, strangerTeam, "stranger", "PUBLISHED", 1L);
        seedVersionFor(strangerOrg, strangerTeam, stranger, 1L);
        store.replace(new KnowledgeEmbeddingVector(
                strangerOrg, strangerTeam, id(stranger), new KnowledgeEntryRevision(1),
                MODEL, "e".repeat(64), unitVector(0)));

        KnowledgeEmbeddingQuery query = new KnowledgeEmbeddingQuery(
                organizationId, teamId, MODEL, unitVector(0), 1);
        List<ScoredKnowledgeEmbedding> topOne = store.nearest(query);
        assertEquals(1, topOne.size(), "topK bounds the result");
        assertEquals(id(hit), topOne.get(0).entryId());
        assertEquals(1.0, topOne.get(0).score(), 1e-6, "cosine similarity of identical vectors");

        List<ScoredKnowledgeEmbedding> all = store.nearest(new KnowledgeEmbeddingQuery(
                organizationId, teamId, MODEL, unitVector(0), 20));
        assertEquals(
                List.of(id(hit), id(neighbor)),
                all.stream().map(ScoredKnowledgeEmbedding::entryId).toList(),
                "superseded, retired and cross-tenant rows stay behind the gate");
    }

    @Test
    void usesTheHnswCosineIndexForNearestSearch() {
        migrateVectorChain();
        UUID entry = seedEntry("indexed", "PUBLISHED", 1L);
        seedVersion(entry, 1L);
        store.replace(vector(entry, 1, "a".repeat(64), unitVector(0)));

        jdbc.execute("SET enable_seqscan = off");
        List<String> plan = jdbc.queryForList(
                """
                EXPLAIN SELECT entry_id FROM crewscope.knowledge_entry_embedding
                ORDER BY embedding <=> CAST(? AS public.vector) LIMIT 5
                """,
                String.class,
                vectorLiteral(unitVector(0)));
        String rendered = String.join("\n", plan);
        assertTrue(
                rendered.contains("hnsw") || rendered.contains("ix_knowledge_entry_embedding_hnsw"),
                "the frozen HNSW cosine index must serve the top-K: " + rendered);
    }

    @Test
    void revalidatesOnReplayAndFailsFastOnATamperedHistory() {
        migrateVectorChain();

        // A replay of the same chain validates and is a no-op: nothing new applies.
        int applied = appliedVectorVersions();
        migrateVectorChain();
        assertEquals(applied, appliedVectorVersions());

        // flyway history stores version as text; the tampered checksum must be an int.
        jdbc.update(
                "UPDATE crewscope.flyway_vector_history SET checksum = ? WHERE version = ?",
                123456789, "1");
        assertThrows(
                FlywayException.class,
                this::migrateVectorChain,
                "a tampered history must fail validation loudly, never drift silently");
    }

    @Test
    void deleteByEntryRemovesEveryModelSlotAndRevision() {
        migrateVectorChain();
        UUID entry = seedEntry("gone", "PUBLISHED", 1L);
        seedVersion(entry, 1L);
        seedVersion(entry, 2L);
        seedEntryEffective(entry, 2L);
        store.replace(vector(entry, 1, "a".repeat(64), unitVector(0)));
        store.replace(new KnowledgeEmbeddingVector(
                organizationId, teamId, id(entry), new KnowledgeEntryRevision(2),
                MODEL, "b".repeat(64), unitVector(1)));
        // Same (model_key, dimension) slot as the previous row: the newer model
        // revision overwrites it in place, so the entry owns two slots, not three.
        store.replace(new KnowledgeEmbeddingVector(
                organizationId, teamId, id(entry), new KnowledgeEntryRevision(2),
                new EmbeddingModelRevision("text-embedding-v4", 1024, 2),
                "c".repeat(64), unitVector(2)));

        int removed = store.deleteByEntry(organizationId, teamId, id(entry));

        assertEquals(2, removed);
        assertEquals(0, embeddingRows());
        assertEquals(0, store.deleteByEntry(organizationId, teamId, id(entry)), "replay is a no-op");
    }

    // ------------------------------------------------------------------ harness

    private void migrateDefaultChain() {
        Flyway.configure()
                .dataSource(
                        PGVECTOR.getJdbcUrl(), PGVECTOR.getUsername(), PGVECTOR.getPassword())
                .locations("classpath:db/migration")
                .schemas("crewscope")
                .defaultSchema("crewscope")
                .load()
                .migrate();
    }

    private void migrateVectorChain() {
        Flyway.configure()
                .dataSource(
                        PGVECTOR.getJdbcUrl(), PGVECTOR.getUsername(), PGVECTOR.getPassword())
                .locations("classpath:db/migration-vector")
                .schemas("crewscope")
                .defaultSchema("crewscope")
                .table("flyway_vector_history")
                .validateMigrationNaming(true)
                .createSchemas(false)
                // Mirrors the production runner: the default chain already filled the
                // schema, so the first vector-chain run baselines at 0 and applies V1+V2.
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    private int embeddingRows() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.knowledge_entry_embedding", Integer.class);
    }

    /** Applied vector versions only — the baseline row (version 0) is not a version. */
    private int appliedVectorVersions() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.flyway_vector_history WHERE version <> '0'",
                Integer.class);
    }

    private void seedTenant(OrganizationId org, TeamId team, PrincipalId owner) {
        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Vector Org', 'ACTIVE')",
                org.value());
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status) VALUES (?, ?, 'Vector Team', 'ACTIVE')",
                team.value(), org.value());
        jdbc.update(
                """
                INSERT INTO crewscope.principal (id, organization_id, principal_type, display_name, status)
                VALUES (?, ?, 'USER', 'Vector owner', 'ACTIVE')
                """,
                owner.value(), org.value());
    }

    /** Inserts one head with the given effective pointer; returns the entry id. */
    private UUID seedEntry(String entryKey, String status, Long effectiveRevision) {
        return seedEntryFor(organizationId, teamId, entryKey, status, effectiveRevision);
    }

    private UUID seedEntryFor(
            OrganizationId org, TeamId team, String entryKey, String status, Long effectiveRevision) {
        UUID entryId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO crewscope.knowledge_entry (
                    id, organization_id, team_id, entry_key, status, effective_revision,
                    latest_revision, version, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, now(), ?, now(), ?)
                """,
                entryId, org.value(), team.value(), entryKey, status, effectiveRevision,
                effectiveRevision == null ? 0L : effectiveRevision, actor.value(), actor.value());
        return entryId;
    }

    private void seedVersion(UUID entryId, long revision) {
        seedVersionFor(organizationId, teamId, entryId, revision);
    }

    private void seedVersionFor(OrganizationId org, TeamId team, UUID entryId, long revision) {
        jdbc.update(
                """
                INSERT INTO crewscope.knowledge_entry_version (
                    organization_id, team_id, entry_id, revision, previous_revision,
                    title, content, content_hash, created_at, created_by_principal_id)
                VALUES (?, ?, ?, ?, ?, 'Title', 'Content', ?, now(), ?)
                """,
                org.value(), team.value(), entryId, revision,
                revision == 1 ? null : revision - 1,
                UUID.nameUUIDFromBytes(("content-" + entryId + "-" + revision).getBytes())
                                .toString()
                                .replace("-", "")
                        + "0".repeat(32),
                actor.value());
    }

    private void seedEntryEffective(UUID entryId, long effectiveRevision) {
        jdbc.update(
                "UPDATE crewscope.knowledge_entry SET effective_revision = ?, latest_revision = ? WHERE id = ?",
                effectiveRevision, effectiveRevision, entryId);
    }

    private KnowledgeEmbeddingVector vector(
            UUID entryId, long revision, String contentHash, float[] embedding) {
        return new KnowledgeEmbeddingVector(
                organizationId, teamId, id(entryId), new KnowledgeEntryRevision(revision),
                MODEL, contentHash, embedding);
    }

    private KnowledgeEmbeddingVector vector(
            OrganizationId org, UUID entryId, long revision, String contentHash, float[] embedding) {
        return new KnowledgeEmbeddingVector(
                org, teamId, id(entryId), new KnowledgeEntryRevision(revision),
                MODEL, contentHash, embedding);
    }

    private static KnowledgeEntryId id(UUID value) {
        return new KnowledgeEntryId(value);
    }

    /** Unit vector on one axis: axis 0 aligns with the query, orthogonal otherwise. */
    private static float[] unitVector(int axis) {
        float[] vector = new float[MODEL.dimension()];
        vector[axis] = 1.0f;
        return vector;
    }

    private static String vectorLiteral(float[] embedding) {
        StringBuilder literal = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append(Float.toString(embedding[i]));
        }
        return literal.append(']').toString();
    }
}
