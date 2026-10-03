package io.crewscope.infrastructure.persistence.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.retrieval.KnowledgeIndexJob;
import io.crewscope.application.retrieval.KnowledgeIndexJobFilter;
import io.crewscope.application.retrieval.KnowledgeIndexJobLiveConflictException;
import io.crewscope.application.retrieval.KnowledgeIndexJobPage;
import io.crewscope.application.retrieval.KnowledgeIndexJobPageRequest;
import io.crewscope.application.retrieval.KnowledgeIndexJobSource;
import io.crewscope.application.retrieval.KnowledgeIndexJobStatus;
import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.retrieval.RepositoryIndexKey;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.infrastructure.testcontainers.AbstractPgVectorContainerIntegrationTest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * PostgreSQL proof for the durable knowledge-index job table (M10-I01b, V55): both
 * migration chains land together, the discriminator's mutually exclusive target shapes
 * and the status/lease/failure-code shapes are enforced by CHECKs, claiming picks the
 * oldest live job and bumps the fencing token, an expired lease is reclaimed with a
 * monotonic token, stale-token writes and checkpoints hit zero rows, and each target
 * admits at most one live job.
 */
class KnowledgeIndexJobMigrationIntegrationTest extends AbstractPgVectorContainerIntegrationTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-03T08:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(30);

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final PrincipalId actor = PrincipalId.generate();
    private final KnowledgeEntryId entryId = KnowledgeEntryId.generate();

    private JdbcTemplate jdbc;
    private JdbcKnowledgeIndexJobRepositoryAdapter jobs;

    @BeforeEach
    void freshDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                PGVECTOR.getJdbcUrl(), PGVECTOR.getUsername(), PGVECTOR.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jobs = new JdbcKnowledgeIndexJobRepositoryAdapter(
                new NamedParameterJdbcTemplate(dataSource));
        jdbc.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        jdbc.execute("DROP EXTENSION IF EXISTS vector CASCADE");
        migrateDefaultChain();
        migrateVectorChain();
        seedTenant();
        seedEntry();
    }

    @Test
    void landsBothChainsWithTheVectorChainReferencingV56() {
        assertTrue(
                jdbc.queryForObject(
                                "SELECT MAX(CAST(version AS integer)) FROM crewscope.flyway_schema_history",
                                Integer.class)
                        >= 56,
                "the default chain must have committed its V55/V56 tip");
        assertEquals(
                3,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.flyway_vector_history WHERE version IN ('1', '2', '3')",
                        Integer.class),
                "the vector chain lands V3 referencing the V56 generation table");
        assertTrue(jdbc.queryForObject(
                "SELECT to_regclass('crewscope.knowledge_index_job') IS NOT NULL"
                        + " AND to_regclass('crewscope.knowledge_index_checkpoint') IS NOT NULL"
                        + " AND to_regclass('crewscope.repository_index_generation') IS NOT NULL"
                        + " AND to_regclass('crewscope.repository_chunk_embedding') IS NOT NULL",
                Boolean.class));
        assertTrue(jdbc.queryForObject(
                "SELECT to_regclass('crewscope.ix_knowledge_index_job_team_listing') IS NOT NULL",
                Boolean.class),
                "V57 (M10-I01c) lands the Team listing keyset index");
    }

    @Test
    void rejectsBrokenTargetShapes() {
        DataIntegrityViolationException missingEntry = assertThrows(
                DataIntegrityViolationException.class,
                () -> insertRawJob("QUEUED", null, null, null, null));
        assertTrue(missingEntry.getMessage().contains("ck_knowledge_index_job_target"),
                "the CHECK name must surface: " + missingEntry.getMessage());

        // A knowledge job that also carries the repository coordinate is the second
        // broken shape: neither branch of the mutually exclusive CHECK holds.
        DataIntegrityViolationException bothTargets = assertThrows(
                DataIntegrityViolationException.class,
                () -> insertRawJob("QUEUED", entryId.value(), "a".repeat(64), null, null));
        assertTrue(bothTargets.getMessage().contains("ck_knowledge_index_job_target"),
                "the CHECK name must surface: " + bothTargets.getMessage());
    }

    @Test
    void enforcesStatusFailureAndLeaseShapeChecks() {
        DataIntegrityViolationException terminalLease = assertThrows(
                DataIntegrityViolationException.class,
                () -> insertRawJob("READY", entryId.value(), null, "worker-a", null));
        assertTrue(terminalLease.getMessage().contains("ck_knowledge_index_job_terminal_lease"),
                "the CHECK name must surface: " + terminalLease.getMessage());

        DataIntegrityViolationException liveWithoutLease = assertThrows(
                DataIntegrityViolationException.class,
                () -> insertRawJob("CHUNKING", entryId.value(), null, null, null));
        assertTrue(
                liveWithoutLease.getMessage().contains("ck_knowledge_index_job_claimed_lease"),
                "the CHECK name must surface: " + liveWithoutLease.getMessage());

        DataIntegrityViolationException successWithFailureCode = assertThrows(
                DataIntegrityViolationException.class,
                () -> insertRawJob("READY", entryId.value(), null, null, "INTERNAL"));
        assertTrue(successWithFailureCode.getMessage()
                        .contains("ck_knowledge_index_job_failure_shape"),
                "the CHECK name must surface: " + successWithFailureCode.getMessage());

        DataIntegrityViolationException failureWithoutCode = assertThrows(
                DataIntegrityViolationException.class,
                () -> insertRawJob("FAILED", entryId.value(), null, null, null));
        assertTrue(failureWithoutCode.getMessage()
                        .contains("ck_knowledge_index_job_failure_shape"),
                "the CHECK name must surface: " + failureWithoutCode.getMessage());
    }

    @Test
    void claimsTheOldestQueuedJobAndBumpsAttemptAndToken() {
        KnowledgeEntryId secondEntry = KnowledgeEntryId.generate();
        seedEntryFor(secondEntry);
        jobs.create(KnowledgeIndexJob.knowledgeEntry(
                UUID.randomUUID(), organizationId, teamId, secondEntry, actor,
                UtcTimestamp.from(NOW.value().plusSeconds(60))));
        KnowledgeIndexJob oldest = jobs.create(KnowledgeIndexJob.knowledgeEntry(
                UUID.randomUUID(), organizationId, teamId, entryId, actor, NOW));

        Optional<KnowledgeIndexJob> claimed = jobs.claimNext("worker-a", NOW, LEASE);

        assertTrue(claimed.isPresent());
        assertEquals(oldest.id(), claimed.get().id(), "the oldest created_at wins");
        assertEquals(KnowledgeIndexJobStatus.CHUNKING, claimed.get().status());
        assertEquals(1, claimed.get().attempt());
        assertEquals(1, claimed.get().claimToken());
        assertEquals(Optional.of("worker-a"), claimed.get().claimedBy());
        assertEquals(
                UtcTimestamp.from(NOW.value().plus(LEASE)),
                claimed.get().leaseExpiresAt().orElseThrow());

        Optional<KnowledgeIndexJob> next = jobs.claimNext(
                "worker-b", UtcTimestamp.from(NOW.value().plusSeconds(120)), LEASE);
        assertTrue(next.isPresent());
        assertEquals(1, next.get().claimToken(), "each job has its own token space");
    }

    @Test
    void reclaimsAnExpiredLeaseWithAMonotonicToken() {
        jobs.create(KnowledgeIndexJob.knowledgeEntry(
                UUID.randomUUID(), organizationId, teamId, entryId, actor, NOW));
        jobs.claimNext("worker-a", NOW, LEASE);
        expireLease();

        Optional<KnowledgeIndexJob> reclaimed = jobs.claimNext("worker-b", NOW, LEASE);

        assertTrue(reclaimed.isPresent());
        assertEquals("worker-b", reclaimed.get().claimedBy().orElseThrow());
        assertEquals(2, reclaimed.get().attempt(), "a reclaim is a retry");
        assertEquals(2, reclaimed.get().claimToken(), "the fencing token only moves up");
    }

    @Test
    void fencesStaleClaimTokensWithZeroRowUpdates() {
        jobs.create(KnowledgeIndexJob.knowledgeEntry(
                UUID.randomUUID(), organizationId, teamId, entryId, actor, NOW));
        KnowledgeIndexJob staleClaim = jobs.claimNext("worker-a", NOW, LEASE).orElseThrow();
        expireLease();
        KnowledgeIndexJob currentClaim = jobs.claimNext("worker-b", NOW, LEASE).orElseThrow();

        UtcTimestamp later = UtcTimestamp.from(NOW.value().plusSeconds(30));
        Optional<KnowledgeIndexJob> fenced = jobs.updateClaimed(
                staleClaim.withStatus(KnowledgeIndexJobStatus.EMBEDDING, later),
                "worker-a", later, LEASE);
        assertTrue(fenced.isEmpty(), "a stale token must write zero rows");

        Optional<KnowledgeIndexJob> valid = jobs.updateClaimed(
                currentClaim.withStatus(KnowledgeIndexJobStatus.EMBEDDING, later),
                "worker-b", later, LEASE);
        assertTrue(valid.isPresent(), "the current owner's write lands");
        assertEquals(
                KnowledgeIndexJobStatus.EMBEDDING,
                jobs.findById(organizationId, teamId, currentClaim.id())
                        .orElseThrow().status());
    }

    @Test
    void guardsCheckpointsByTheClaimTokenAndResumesAtMaxPlusOne() {
        jobs.create(KnowledgeIndexJob.knowledgeEntry(
                UUID.randomUUID(), organizationId, teamId, entryId, actor, NOW));
        KnowledgeIndexJob claim = jobs.claimNext("worker-a", NOW, LEASE).orElseThrow();

        assertTrue(jobs.insertCheckpoint(claim.id(), claim.claimToken(), 1, 10));
        assertTrue(jobs.insertCheckpoint(claim.id(), claim.claimToken(), 11, 10));
        assertEquals(20, jobs.maxCheckpointSeq(claim.id()),
                "the position is the tail of the last committed batch");

        expireLease();
        KnowledgeIndexJob reclaim = jobs.claimNext("worker-b", NOW, LEASE).orElseThrow();
        assertEquals(claim.claimToken() + 1, reclaim.claimToken());

        assertFalse(jobs.insertCheckpoint(claim.id(), claim.claimToken(), 21, 10),
                "a checkpoint from a stale token is rejected by the EXISTS gate");
        assertTrue(jobs.insertCheckpoint(reclaim.id(), reclaim.claimToken(), 21, 10),
                "the resume batch starts at max + 1");
        assertEquals(30, jobs.maxCheckpointSeq(claim.id()));
    }

    @Test
    void allowsOneLiveJobPerKnowledgeEntryUntilTerminal() {
        jobs.create(KnowledgeIndexJob.knowledgeEntry(
                UUID.randomUUID(), organizationId, teamId, entryId, actor, NOW));

        KnowledgeIndexJobLiveConflictException duplicate = assertThrows(
                KnowledgeIndexJobLiveConflictException.class,
                () -> jobs.create(KnowledgeIndexJob.knowledgeEntry(
                        UUID.randomUUID(), organizationId, teamId, entryId, actor,
                        UtcTimestamp.from(NOW.value().plusSeconds(60)))));
        assertTrue(duplicate.getMessage().contains("ux_knowledge_index_job_entry_live"),
                "the live-job constraint name must surface: " + duplicate.getMessage());

        KnowledgeIndexJob claim = jobs.claimNext("worker-a", NOW, LEASE).orElseThrow();
        UtcTimestamp done = UtcTimestamp.from(NOW.value().plusSeconds(30));
        assertTrue(jobs.updateClaimed(claim.ready(done), "worker-a", done, LEASE).isPresent());

        jobs.create(KnowledgeIndexJob.knowledgeEntry(
                UUID.randomUUID(), organizationId, teamId, entryId, actor,
                UtcTimestamp.from(NOW.value().plusSeconds(120))));
        assertTrue(true, "a terminal job frees the entry for a fresh enqueue");
    }

    @Test
    void allowsOneLiveRepositoryJobPerIndexKeyUntilTerminal() {
        RepositoryIndexKey coordinate = indexKey();
        jobs.create(KnowledgeIndexJob.repositoryBuild(
                UUID.randomUUID(), organizationId, teamId, WorkProjectId.generate(),
                coordinate, actor, NOW));

        KnowledgeIndexJobLiveConflictException duplicate = assertThrows(
                KnowledgeIndexJobLiveConflictException.class,
                () -> jobs.create(KnowledgeIndexJob.repositoryBuild(
                        UUID.randomUUID(), organizationId, teamId, WorkProjectId.generate(),
                        coordinate, actor, UtcTimestamp.from(NOW.value().plusSeconds(60)))));
        assertTrue(duplicate.getMessage().contains("ux_knowledge_index_job_index_key_live"),
                "the live-job constraint name must surface: " + duplicate.getMessage());

        KnowledgeIndexJob claim = jobs.claimNext("worker-a", NOW, LEASE).orElseThrow();
        assertTrue(jobs.cancelQueued(claim, NOW).isEmpty(),
                "a claimed job is no longer QUEUED and cannot be cancelled as such");
        UtcTimestamp done = UtcTimestamp.from(NOW.value().plusSeconds(30));
        assertTrue(jobs.updateClaimed(claim.failed("INTERNAL", done), "worker-a", done, LEASE)
                .isPresent());

        jobs.create(KnowledgeIndexJob.repositoryBuild(
                UUID.randomUUID(), organizationId, teamId, WorkProjectId.generate(),
                coordinate, actor, UtcTimestamp.from(NOW.value().plusSeconds(120))));
        assertTrue(true, "a terminal job frees the index coordinate for a fresh enqueue");
    }

    @Test
    void listsTeamJobsByKeysetWithFiltersAndTenantIsolation() {
        KnowledgeEntryId secondEntry = KnowledgeEntryId.generate();
        seedEntryFor(secondEntry);
        KnowledgeIndexJob first = jobs.create(KnowledgeIndexJob.knowledgeEntry(
                UUID.randomUUID(), organizationId, teamId, entryId, actor, NOW));
        KnowledgeIndexJob second = jobs.create(KnowledgeIndexJob.knowledgeEntry(
                UUID.randomUUID(), organizationId, teamId, secondEntry, actor,
                UtcTimestamp.from(NOW.value().plusSeconds(60))));
        KnowledgeIndexJob build = jobs.create(KnowledgeIndexJob.repositoryBuild(
                UUID.randomUUID(), organizationId, teamId, WorkProjectId.generate(),
                indexKey(), actor, UtcTimestamp.from(NOW.value().plusSeconds(120))));
        OrganizationId strangerOrg = OrganizationId.generate();
        TeamId strangerTeam = TeamId.generate();
        PrincipalId strangerActor = PrincipalId.generate();
        seedTenantFor(strangerOrg, strangerTeam, strangerActor);
        KnowledgeIndexJob stranger = jobs.create(KnowledgeIndexJob.repositoryBuild(
                UUID.randomUUID(), strangerOrg, strangerTeam, WorkProjectId.generate(),
                indexKey(strangerOrg, strangerTeam), strangerActor, NOW));

        // One-item pages walk (created_at, id) ascending; the page boundary carries
        // the last seen job id as the cursor.
        KnowledgeIndexJobPage pageOne = jobs.findByTeam(
                organizationId, teamId, KnowledgeIndexJobFilter.all(),
                new KnowledgeIndexJobPageRequest(Optional.empty(), 1));
        assertEquals(List.of(first.id()), ids(pageOne));
        assertEquals(Optional.of(first.id()), pageOne.nextAfterJobId());
        KnowledgeIndexJobPage pageTwo = jobs.findByTeam(
                organizationId, teamId, KnowledgeIndexJobFilter.all(),
                new KnowledgeIndexJobPageRequest(pageOne.nextAfterJobId(), 1));
        assertEquals(List.of(second.id()), ids(pageTwo));
        KnowledgeIndexJobPage pageThree = jobs.findByTeam(
                organizationId, teamId, KnowledgeIndexJobFilter.all(),
                new KnowledgeIndexJobPageRequest(pageTwo.nextAfterJobId(), 10));
        assertEquals(List.of(build.id()), ids(pageThree));
        assertTrue(pageThree.nextAfterJobId().isEmpty(), "the final page carries no cursor");

        // Filters narrow without disturbing the keyset order.
        KnowledgeIndexJobPage repositoryOnly = jobs.findByTeam(
                organizationId, teamId,
                new KnowledgeIndexJobFilter(
                        Optional.of(KnowledgeIndexJobSource.REPOSITORY), Optional.empty()),
                new KnowledgeIndexJobPageRequest(Optional.empty(), 10));
        assertEquals(List.of(build.id()), ids(repositoryOnly));
        KnowledgeIndexJobPage queuedOnly = jobs.findByTeam(
                organizationId, teamId,
                new KnowledgeIndexJobFilter(
                        Optional.empty(), Optional.of(KnowledgeIndexJobStatus.QUEUED)),
                new KnowledgeIndexJobPageRequest(Optional.empty(), 10));
        assertEquals(3, queuedOnly.items().size(), "every seeded job is still QUEUED");

        // Tenant isolation: the stranger's job never leaks into this Team's pages, and
        // a cursor foreign to the querying Team resolves to nothing (callers reject it
        // through their own findById gate before paging).
        KnowledgeIndexJobPage strangers = jobs.findByTeam(
                strangerOrg, strangerTeam, KnowledgeIndexJobFilter.all(),
                new KnowledgeIndexJobPageRequest(Optional.empty(), 10));
        assertEquals(List.of(stranger.id()), ids(strangers));
        KnowledgeIndexJobPage foreignCursor = jobs.findByTeam(
                strangerOrg, strangerTeam, KnowledgeIndexJobFilter.all(),
                new KnowledgeIndexJobPageRequest(Optional.of(first.id()), 10));
        assertTrue(foreignCursor.items().isEmpty());
        assertTrue(foreignCursor.nextAfterJobId().isEmpty());
    }

    // ------------------------------------------------------------------ fixtures

    private static List<UUID> ids(KnowledgeIndexJobPage page) {
        return page.items().stream().map(KnowledgeIndexJob::id).toList();
    }

    private RepositoryIndexKey indexKey() {
        return indexKey(organizationId, teamId);
    }

    private RepositoryIndexKey indexKey(OrganizationId org, TeamId team) {
        return IndexKeyCodec.reconstitute(
                org,
                team,
                io.crewscope.domain.coding.RepositoryBindingId.generate(),
                "0123456789012345678901234567890123456789",
                "b".repeat(64),
                "text-embedding-v4",
                1024,
                3);
    }

    private void expireLease() {
        // Relative to the row's own lease, never the database wall clock: the fixture
        // clock is behind real time, so now()-relative values would not look expired.
        jdbc.update(
                "UPDATE crewscope.knowledge_index_job"
                        + " SET lease_expires_at = lease_expires_at - interval '2 hours'"
                        + " WHERE status IN ('CHUNKING', 'EMBEDDING', 'ACTIVATING')");
    }

    /** Raw INSERT that bypasses the domain record's own validation on purpose. */
    private void insertRawJob(
            String status, UUID entry, String indexKey, String claimedBy, String failureCode) {
        jdbc.update(
                """
                INSERT INTO crewscope.knowledge_index_job
                    (id, organization_id, team_id, source, entry_id, index_key, status,
                     attempt, chunks_done, chunks_total, failure_code, claimed_by,
                     lease_expires_at, created_by_principal_id, created_at, updated_at)
                VALUES (?, ?, ?, 'KNOWLEDGE_ENTRY', ?, ?, ?, 0, 0, 0, ?, ?, ?, ?, now(), now())
                """,
                UUID.randomUUID(), organizationId.value(), teamId.value(), entry, indexKey,
                status, failureCode, claimedBy,
                claimedBy == null ? null : OffsetDateTime.now().plusMinutes(30),
                actor.value());
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
        seedTenantFor(organizationId, teamId, actor);
    }

    private void seedTenantFor(OrganizationId org, TeamId team, PrincipalId principal) {
        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Index Org', 'ACTIVE')",
                org.value());
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status) VALUES (?, ?, 'Index Team', 'ACTIVE')",
                team.value(), org.value());
        jdbc.update(
                """
                INSERT INTO crewscope.principal (id, organization_id, principal_type, display_name, status)
                VALUES (?, ?, 'USER', 'Index owner', 'ACTIVE')
                """,
                principal.value(), org.value());
    }

    private void seedEntry() {
        seedEntryFor(entryId);
    }

    private void seedEntryFor(KnowledgeEntryId id) {
        jdbc.update(
                """
                INSERT INTO crewscope.knowledge_entry (
                    id, organization_id, team_id, entry_key, status, effective_revision,
                    latest_revision, version, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id)
                VALUES (?, ?, ?, ?, 'PUBLISHED', 1, 1, 0, now(), ?, now(), ?)
                """,
                id.value(), organizationId.value(), teamId.value(),
                "entry-" + id.value(), actor.value(), actor.value());
    }
}
