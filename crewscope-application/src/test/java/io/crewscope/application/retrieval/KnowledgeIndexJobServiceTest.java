package io.crewscope.application.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.embedding.EmbeddingBatchResult;
import io.crewscope.application.embedding.TeamEmbeddingCommand;
import io.crewscope.application.knowledge.KnowledgeRepository;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.knowledge.KnowledgeEntry;
import io.crewscope.application.knowledge.KnowledgeEntryFilter;
import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.knowledge.KnowledgeEntryKey;
import io.crewscope.application.knowledge.KnowledgeEntryPage;
import io.crewscope.application.knowledge.KnowledgeEntryPageRequest;
import io.crewscope.domain.knowledge.KnowledgeEntryRevision;
import io.crewscope.domain.knowledge.KnowledgeEntryVersion;
import io.crewscope.application.knowledge.KnowledgeEntryVersionPage;
import io.crewscope.application.knowledge.KnowledgeVersionPageRequest;
import io.crewscope.domain.retrieval.EmbeddingModelRevision;
import io.crewscope.domain.retrieval.RepositoryIndexKey;
import io.crewscope.domain.retrieval.SourceCommit;
import io.crewscope.domain.retrieval.chunking.ChunkingPolicy;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkProjectId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Enqueue-face contract of the knowledge index (M10-I01b): the refresh gate, the
 * ungated cleanup path, per-target enqueue idempotency, the rebuild seed, and the
 * model resolution frozen into repository index keys.
 */
final class KnowledgeIndexJobServiceTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-03T08:00:00Z");
    private static final EmbeddingModelRevision MODEL =
            new EmbeddingModelRevision("text-embedding-v4", 1024, 3);

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final PrincipalId actor = PrincipalId.generate();
    private final KnowledgeEntryId entryId = KnowledgeEntryId.generate();
    private final WorkProjectId projectId = WorkProjectId.generate();
    private final RepositoryBindingId bindingId = RepositoryBindingId.generate();
    private final SourceCommit commit = new SourceCommit(
            "0123456789012345678901234567890123456789");

    private final FakeJobRepository jobs = new FakeJobRepository();
    private final FakeEmbeddingExecutor embeddings = new FakeEmbeddingExecutor();
    private final FakeKnowledgeRepository knowledge = new FakeKnowledgeRepository();
    private final MutableClock clock = new MutableClock(NOW);

    @Test
    void refreshEnqueueIsGatedWhileCleanupNeverIs() {
        KnowledgeIndexJobService disabled =
                new KnowledgeIndexJobService(jobs, embeddings, knowledge, clock, false);

        assertTrue(disabled.enqueueEntryRefresh(organizationId, teamId, entryId, actor).isEmpty(),
                "a closed refresh gate skips, it does not error");
        assertTrue(jobs.values.isEmpty(), "the gate must not leave a job behind");

        KnowledgeIndexJob cleanup =
                disabled.enqueueEntryCleanup(organizationId, teamId, entryId, actor);

        assertEquals(KnowledgeIndexJobStatus.QUEUED, cleanup.status());
        assertEquals(KnowledgeIndexJobSource.KNOWLEDGE_ENTRY, cleanup.source());
        assertEquals(entryId, cleanup.entryId().orElseThrow());
        assertEquals(1, jobs.values.size());
        assertTrue(disabled.enqueueRepositoryBuild(
                organizationId, teamId, projectId, bindingId, commit, actor).isEmpty(),
                "repository builds are refresh-class and share the gate");
    }

    @Test
    void duplicateEnqueueReturnsTheLiveJobInsteadOfASecondOne() {
        KnowledgeIndexJobService service = service(true);

        KnowledgeIndexJob first = service.enqueueEntryRefresh(
                organizationId, teamId, entryId, actor).orElseThrow();
        KnowledgeIndexJob second = service.enqueueEntryRefresh(
                organizationId, teamId, entryId, actor).orElseThrow();

        assertEquals(first.id(), second.id());
        assertEquals(1, jobs.values.size());

        KnowledgeIndexJob cleanup = service.enqueueEntryCleanup(
                organizationId, teamId, entryId, actor);
        assertEquals(first.id(), cleanup.id(),
                "cleanup converges onto the same live job; the worker's gate decides");
        assertEquals(1, jobs.values.size());
    }

    @Test
    void rebuildSeedsOneRefreshJobPerEffectiveVersion() {
        knowledge.effectiveVersions.addAll(List.of(
                versionOf(KnowledgeEntryId.generate(), 1),
                versionOf(KnowledgeEntryId.generate(), 2)));
        KnowledgeIndexJobService service = service(true);

        assertEquals(2, service.rebuildTeamKnowledge(organizationId, teamId, actor));
        assertEquals(2, jobs.values.size());
        assertEquals(0, service.rebuildTeamKnowledge(organizationId, teamId, actor),
                "a second pass re-seeds nothing while the jobs are still live");
        assertEquals(2, jobs.values.size());
    }

    @Test
    void repositoryBuildFreezesTheResolvedModelIntoTheIndexKey() {
        embeddings.model = MODEL;
        KnowledgeIndexJobService service = service(true);

        KnowledgeIndexJob job = service.enqueueRepositoryBuild(
                organizationId, teamId, projectId, bindingId, commit, actor).orElseThrow();

        assertEquals(KnowledgeIndexJobSource.REPOSITORY, job.source());
        assertEquals(projectId, job.projectId().orElseThrow());
        RepositoryIndexKey indexKey = job.indexKey().orElseThrow();
        assertEquals(organizationId, indexKey.organizationId());
        assertEquals(teamId, indexKey.teamId());
        assertEquals(bindingId, indexKey.repositoryBindingId());
        assertEquals(commit, indexKey.sourceCommit());
        assertEquals(MODEL, indexKey.embeddingModelRevision(),
                "the enqueue-time model resolution is frozen into the key");
        assertEquals(ChunkingPolicy.defaults().policyHash(), indexKey.chunkingPolicyHash());
    }

    @Test
    void repositoryBuildFailsFastWhenNoModelResolves() {
        embeddings.failure = new DomainValidationException(
                "teamEmbedding.connection", "no usable embedding model connection");
        KnowledgeIndexJobService service = service(true);

        DomainValidationException denied = assertThrows(DomainValidationException.class,
                () -> service.enqueueRepositoryBuild(
                        organizationId, teamId, projectId, bindingId, commit, actor));

        assertEquals("teamEmbedding.connection", denied.error().details().get("field"));
        assertTrue(jobs.values.isEmpty(), "a failed resolution must not leave a job behind");
    }

    @Test
    void duplicateRepositoryBuildReturnsTheLiveJobOfTheSameIndexKey() {
        embeddings.model = MODEL;
        KnowledgeIndexJobService service = service(true);

        KnowledgeIndexJob first = service.enqueueRepositoryBuild(
                organizationId, teamId, projectId, bindingId, commit, actor).orElseThrow();
        KnowledgeIndexJob second = service.enqueueRepositoryBuild(
                organizationId, teamId, projectId, bindingId, commit, actor).orElseThrow();

        assertEquals(first.id(), second.id());
        assertEquals(1, jobs.values.size());
    }

    @Test
    void entryEnqueueRacingAConcurrentCreateConvergesOntoTheWinner() {
        KnowledgeIndexJobService service = service(true);
        jobs.raceOnCreate = true;

        KnowledgeIndexJob converged = service.enqueueEntryRefresh(
                organizationId, teamId, entryId, actor).orElseThrow();

        assertEquals(1, jobs.values.size());
        assertEquals(converged.id(), jobs.values.values().iterator().next().id(),
                "the 23505 loser converges onto the racing writer's job");
        assertEquals(converged.id(),
                service.enqueueEntryCleanup(organizationId, teamId, entryId, actor).id(),
                "the cleanup path converges onto the same live job");
        assertEquals(1, jobs.values.size());
    }

    @Test
    void repositoryBuildRacingAConcurrentCreateConvergesOntoTheWinner() {
        embeddings.model = MODEL;
        KnowledgeIndexJobService service = service(true);
        jobs.raceOnCreate = true;

        KnowledgeIndexJob converged = service.enqueueRepositoryBuild(
                organizationId, teamId, projectId, bindingId, commit, actor).orElseThrow();

        assertEquals(1, jobs.values.size());
        assertEquals(converged.id(), jobs.values.values().iterator().next().id(),
                "the index-key race converges onto the racing writer's job");
        assertEquals(bindingId, converged.indexKey().orElseThrow().repositoryBindingId());
    }

    @Test
    void rebuildSkipsEntriesWhoseLiveSlotWasRaced() {
        knowledge.effectiveVersions.addAll(List.of(
                versionOf(KnowledgeEntryId.generate(), 1),
                versionOf(KnowledgeEntryId.generate(), 2)));
        KnowledgeIndexJobService service = service(true);
        jobs.raceOnCreate = true;

        assertEquals(1, service.rebuildTeamKnowledge(organizationId, teamId, actor),
                "the raced entry does not count as created; the second one does");
        assertEquals(2, jobs.values.size(),
                "the racing winner plus the second entry's own job");
    }

    // ------------------------------------------------------------------ fixtures

    private KnowledgeIndexJobService service(boolean refreshEnabled) {
        return new KnowledgeIndexJobService(jobs, embeddings, knowledge, clock, refreshEnabled);
    }

    private KnowledgeEntryVersion versionOf(KnowledgeEntryId entryId, long revision) {
        return KnowledgeEntryVersion.create(
                entryId,
                new io.crewscope.domain.team.TeamScope(organizationId, teamId),
                new KnowledgeEntryRevision(revision),
                revision == 1
                        ? Optional.empty()
                        : Optional.of(new KnowledgeEntryRevision(revision - 1)),
                "Title " + revision,
                "Content " + revision,
                PrincipalId.generate(),
                NOW);
    }

    /** Clock the tests advance explicitly; enqueue only reads it. */
    private static final class MutableClock implements TimeProvider {
        private UtcTimestamp now;

        private MutableClock(UtcTimestamp now) {
            this.now = now;
        }

        @Override
        public UtcTimestamp now() {
            return now;
        }
    }

    /** Configurable governance seam: a fixed model, or a resolution failure. */
    private static final class FakeEmbeddingExecutor implements KnowledgeEmbeddingExecutor {
        private EmbeddingModelRevision model = MODEL;
        private DomainValidationException failure;

        @Override
        public EmbeddingModelRevision resolveModel(OrganizationId organizationId, TeamId teamId) {
            if (failure != null) {
                throw failure;
            }
            return model;
        }

        @Override
        public EmbeddingBatchResult embed(TeamEmbeddingCommand command) {
            throw new UnsupportedOperationException("enqueue tests never embed");
        }
    }

    /** In-memory job store implementing the claim/checkpoint semantics of the port. */
    private static final class FakeJobRepository implements KnowledgeIndexJobRepository {
        private final Map<UUID, KnowledgeIndexJob> values = new LinkedHashMap<>();
        private final Map<UUID, List<int[]>> checkpoints = new LinkedHashMap<>();

        /**
         * When set, the next create simulates the check-then-create race: a concurrent
         * writer's job for the same target lands first, then our insert throws the
         * translated live-conflict error the JDBC adapter produces on SQLState 23505.
         */
        private boolean raceOnCreate;

        @Override
        public KnowledgeIndexJob create(KnowledgeIndexJob job) {
            if (raceOnCreate) {
                raceOnCreate = false;
                KnowledgeIndexJob racingWriter;
                String constraint;
                if (job.source() == KnowledgeIndexJobSource.REPOSITORY) {
                    racingWriter = KnowledgeIndexJob.repositoryBuild(
                            UUID.randomUUID(), job.organizationId(), job.teamId(),
                            job.projectId().orElseThrow(), job.indexKey().orElseThrow(),
                            job.createdBy(), job.createdAt());
                    constraint = "ux_knowledge_index_job_index_key_live";
                } else {
                    racingWriter = KnowledgeIndexJob.knowledgeEntry(
                            UUID.randomUUID(), job.organizationId(), job.teamId(),
                            job.entryId().orElseThrow(), job.createdBy(), job.createdAt());
                    constraint = "ux_knowledge_index_job_entry_live";
                }
                values.put(racingWriter.id(), racingWriter);
                checkpoints.put(racingWriter.id(), new ArrayList<>());
                throw new KnowledgeIndexJobLiveConflictException(constraint);
            }
            values.put(job.id(), job);
            checkpoints.put(job.id(), new ArrayList<>());
            return job;
        }

        @Override
        public Optional<KnowledgeIndexJob> findById(
                OrganizationId organizationId, TeamId teamId, UUID jobId) {
            KnowledgeIndexJob job = values.get(jobId);
            return job != null
                    && job.organizationId().equals(organizationId)
                    && job.teamId().equals(teamId)
                    ? Optional.of(job) : Optional.empty();
        }

        @Override
        public Optional<KnowledgeIndexJob> findLiveByEntry(
                OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId) {
            return values.values().stream()
                    .filter(job -> job.source() == KnowledgeIndexJobSource.KNOWLEDGE_ENTRY
                            && job.organizationId().equals(organizationId)
                            && job.teamId().equals(teamId)
                            && job.entryId().equals(Optional.of(entryId))
                            && !job.status().terminal())
                    .min(Comparator.comparing(KnowledgeIndexJob::createdAt)
                            .thenComparing(job -> job.id().toString()));
        }

        @Override
        public Optional<KnowledgeIndexJob> findLiveByIndexKey(RepositoryIndexKey indexKey) {
            return values.values().stream()
                    .filter(job -> job.source() == KnowledgeIndexJobSource.REPOSITORY
                            && job.indexKey().equals(Optional.of(indexKey))
                            && !job.status().terminal())
                    .min(Comparator.comparing(KnowledgeIndexJob::createdAt)
                            .thenComparing(job -> job.id().toString()));
        }

        @Override
        public Optional<KnowledgeIndexJob> findLatestByEntry(
                OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId) {
            return values.values().stream()
                    .filter(job -> job.organizationId().equals(organizationId)
                            && job.teamId().equals(teamId)
                            && job.entryId().equals(Optional.of(entryId)))
                    .max(Comparator.comparing(KnowledgeIndexJob::updatedAt));
        }

        @Override
        public KnowledgeIndexJobPage findByTeam(
                OrganizationId organizationId,
                TeamId teamId,
                KnowledgeIndexJobFilter filter,
                KnowledgeIndexJobPageRequest pageRequest) {
            List<KnowledgeIndexJob> ordered = values.values().stream()
                    .filter(job -> job.organizationId().equals(organizationId)
                            && job.teamId().equals(teamId))
                    .sorted(Comparator.comparing(KnowledgeIndexJob::createdAt)
                            .thenComparing(job -> job.id().toString()))
                    .toList();
            int start = 0;
            if (pageRequest.afterJobId().isPresent()) {
                UUID after = pageRequest.afterJobId().orElseThrow();
                List<UUID> ids = ordered.stream().map(KnowledgeIndexJob::id).toList();
                int index = ids.indexOf(after);
                if (index < 0) {
                    throw new IllegalArgumentException(
                            "cursor job " + after + " is not part of this Team's jobs");
                }
                start = index + 1;
            }
            List<KnowledgeIndexJob> candidates = ordered.subList(start, ordered.size()).stream()
                    .filter(job -> filter.source().map(job.source()::equals).orElse(true))
                    .filter(job -> filter.status().map(job.status()::equals).orElse(true))
                    .limit(pageRequest.limit() + 1L)
                    .toList();
            if (candidates.size() <= pageRequest.limit()) {
                return new KnowledgeIndexJobPage(candidates, Optional.empty());
            }
            List<KnowledgeIndexJob> items = candidates.subList(0, pageRequest.limit());
            return new KnowledgeIndexJobPage(
                    items, Optional.of(items.get(items.size() - 1).id()));
        }

        @Override
        public Optional<KnowledgeIndexJob> claimNext(
                String owner, UtcTimestamp now, Duration leaseDuration) {
            Optional<KnowledgeIndexJob> candidate = values.values().stream()
                    .filter(job -> !job.status().terminal()
                            && (job.status() == KnowledgeIndexJobStatus.QUEUED
                                    || job.leaseExpiresAt()
                                            .map(lease -> lease.value()
                                                    .isBefore(now.value()))
                                            .orElse(false)))
                    .min(Comparator.comparing(KnowledgeIndexJob::createdAt)
                            .thenComparing(job -> job.id().toString()));
            if (candidate.isEmpty()) {
                return Optional.empty();
            }
            KnowledgeIndexJob claimed = candidate.get().asClaimed(
                    owner, UtcTimestamp.from(now.value().plus(leaseDuration)), now);
            values.put(claimed.id(), claimed);
            return Optional.of(claimed);
        }

        @Override
        public Optional<KnowledgeIndexJob> updateClaimed(
                KnowledgeIndexJob job, String owner, UtcTimestamp now, Duration leaseDuration) {
            KnowledgeIndexJob stored = values.get(job.id());
            if (stored == null
                    || stored.status().terminal()
                    || stored.claimToken() != job.claimToken()
                    || !owner.equals(stored.claimedBy().orElse(null))) {
                return Optional.empty();
            }
            values.put(job.id(), job);
            return Optional.of(job);
        }

        @Override
        public boolean insertCheckpoint(
                UUID jobId, long claimToken, int chunkSeq, int chunkCount) {
            KnowledgeIndexJob stored = values.get(jobId);
            if (stored == null || stored.status().terminal()
                    || stored.claimToken() != claimToken) {
                return false;
            }
            checkpoints.get(jobId).add(new int[] {chunkSeq, chunkCount});
            return true;
        }

        @Override
        public int maxCheckpointSeq(UUID jobId) {
            // Port contract: batch tail, not batch head (see the JDBC adapter).
            return checkpoints.getOrDefault(jobId, List.of()).stream()
                    .mapToInt(checkpoint -> checkpoint[0] + checkpoint[1] - 1)
                    .max()
                    .orElse(0);
        }

        @Override
        public Optional<KnowledgeIndexJob> cancelQueued(
                KnowledgeIndexJob job, UtcTimestamp cancelledAt) {
            KnowledgeIndexJob stored = values.get(job.id());
            if (stored == null || stored.status() != KnowledgeIndexJobStatus.QUEUED) {
                return Optional.empty();
            }
            KnowledgeIndexJob cancelled =
                    stored.cancelled(KnowledgeIndexFailureCodes.CANCELLED, cancelledAt);
            values.put(cancelled.id(), cancelled);
            return Optional.of(cancelled);
        }
    }

    /** Only the rebuild seed is exercised; every other read fails the test loudly. */
    private static final class FakeKnowledgeRepository implements KnowledgeRepository {
        private final List<KnowledgeEntryVersion> effectiveVersions = new ArrayList<>();

        @Override
        public KnowledgeEntry create(KnowledgeEntry entry) {
            throw new UnsupportedOperationException();
        }

        @Override
        public KnowledgeEntry save(
                KnowledgeEntry entry, Optional<KnowledgeEntryVersion> appendedVersion) {
            throw new OptimisticLockConflictException("KnowledgeEntry", "test", 1, 2);
        }

        @Override
        public Optional<KnowledgeEntry> findById(
                OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KnowledgeEntry> findByKey(
                OrganizationId organizationId, TeamId teamId, KnowledgeEntryKey entryKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public KnowledgeEntryPage findByTeam(
                OrganizationId organizationId,
                TeamId teamId,
                KnowledgeEntryFilter filter,
                KnowledgeEntryPageRequest pageRequest) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KnowledgeEntryVersion> findVersion(
                OrganizationId organizationId,
                TeamId teamId,
                KnowledgeEntryId entryId,
                KnowledgeEntryRevision revision) {
            throw new UnsupportedOperationException();
        }

        @Override
        public KnowledgeEntryVersionPage findVersionHistory(
                OrganizationId organizationId,
                TeamId teamId,
                KnowledgeEntryId entryId,
                KnowledgeVersionPageRequest pageRequest) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KnowledgeEntryVersion> findEffectiveVersion(
                OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<KnowledgeEntryVersion> findEffectiveVersionsByTeam(
                OrganizationId organizationId, TeamId teamId) {
            return List.copyOf(effectiveVersions);
        }
    }
}
