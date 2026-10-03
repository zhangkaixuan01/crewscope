package io.crewscope.application.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.embedding.EmbeddingBatchResult;
import io.crewscope.application.embedding.EmbeddingClient;
import io.crewscope.application.embedding.EmbeddingDeliveryException;
import io.crewscope.application.embedding.TeamEmbeddingCommand;
import io.crewscope.application.knowledge.KnowledgeRepository;
import io.crewscope.application.observability.OperationalTelemetry;
import io.crewscope.application.observability.OperationalTelemetry.ErrorCode;
import io.crewscope.application.observability.OperationalTelemetry.Outcome;
import io.crewscope.application.observability.OperationalTelemetry.Request;
import io.crewscope.application.retrieval.RepositoryContentPort.RepositoryFileContent;
import io.crewscope.application.retrieval.RepositoryContentPort.RepositoryFileRef;
import io.crewscope.application.transaction.TransactionExecutor;
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
import io.crewscope.domain.model.ModelCatalogCoordinate;
import io.crewscope.domain.model.ModelConnectionHealthFailureCode;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.retrieval.EmbeddingModelRevision;
import io.crewscope.domain.retrieval.GenerationRetentionPolicy;
import io.crewscope.domain.retrieval.GenerationStatus;
import io.crewscope.domain.retrieval.RepositoryGenerationKey;
import io.crewscope.domain.retrieval.RepositoryIndexKey;
import io.crewscope.domain.retrieval.SourceCommit;
import io.crewscope.domain.retrieval.chunking.ChunkingPolicy;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Worker contract of the knowledge index (M10-I01b): the authoritative effective-version
 * gate over knowledge entries, whole-entry chunking with the explicit oversize rejection,
 * sanitized embedding failure codes, observable fencing, and the repository build's
 * open → write → activate generation sequence with drift and budget guards.
 */
final class KnowledgeIndexWorkerTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-03T08:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(30);
    private static final EmbeddingModelRevision MODEL =
            new EmbeddingModelRevision("text-embedding-v4", 1024, 3);
    private static final EmbeddingModelRevision DRIFTED =
            new EmbeddingModelRevision("text-embedding-v4", 1024, 4);

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final PrincipalId actor = PrincipalId.generate();
    private final KnowledgeEntryId entryId = KnowledgeEntryId.generate();
    private final WorkProjectId projectId = WorkProjectId.generate();
    private final RepositoryBindingId bindingId = RepositoryBindingId.generate();
    private final SourceCommit commit = new SourceCommit(
            "0123456789012345678901234567890123456789");

    private final FakeJobRepository jobs = new FakeJobRepository();
    private final FakeEmbeddingExecutor executor = new FakeEmbeddingExecutor();
    private final FakeKnowledgeRepository knowledge = new FakeKnowledgeRepository();
    private final FakeKnowledgeVectorStore knowledgeVectors = new FakeKnowledgeVectorStore();
    private final FakeContentPort content = new FakeContentPort();
    private final List<String> trace = new ArrayList<>();
    private final FakeChunkStore chunkStore = new FakeChunkStore(trace);
    private final FakeGenerationStore generationStore = new FakeGenerationStore(trace);
    private final RecordingTelemetry telemetry = new RecordingTelemetry();
    private final MutableClock clock = new MutableClock(NOW);

    // ------------------------------------------------------------------ knowledge entries

    @Test
    void embedsOneEffectiveEntryAsASingleChunkAndReachesReady() {
        knowledge.effective = Optional.of(version(1, "Onboarding", "Read the handbook first."));
        jobs.create(KnowledgeIndexJob.knowledgeEntry(
                UUID.randomUUID(), organizationId, teamId, entryId, actor, NOW));

        KnowledgeIndexWorkerRunResult result = worker(20000).runOnce();

        assertEquals(new KnowledgeIndexWorkerRunResult(1, 0, 0), result);
        assertEquals(1, executor.commands.size());
        assertEquals(
                List.of("Onboarding\n\nRead the handbook first."),
                executor.commands.get(0).inputs());
        assertEquals(1, knowledgeVectors.replaced.size());
        assertEquals(new KnowledgeEntryRevision(1), knowledgeVectors.replaced.get(0).revision());
        assertEquals(MODEL, knowledgeVectors.replaced.get(0).model());
        assertTrue(knowledgeVectors.drained.contains(entryId),
                "the same transaction purges rows of superseded revisions");
        KnowledgeIndexJob done = jobs.stored();
        assertEquals(KnowledgeIndexJobStatus.READY, done.status());
        assertEquals(1, done.chunksDone());
        assertEquals(1, done.chunksTotal());
        assertEquals(List.of(1), jobs.checkpoints());
        assertEquals(List.of("SUCCESS/NONE"), telemetry.completed);
    }

    @Test
    void theAuthoritativeGateBeatsEventOrder() {
        // The job was enqueued around revision 1, but the head moved on: the worker must
        // embed whatever is effective at claim time, never what the event carried.
        knowledge.effective = Optional.of(version(2, "Second", "the current truth"));
        jobs.create(KnowledgeIndexJob.knowledgeEntry(
                UUID.randomUUID(), organizationId, teamId, entryId, actor, NOW));

        worker(20000).runOnce();

        assertEquals(List.of("Second\n\nthe current truth"), executor.commands.get(0).inputs());
        assertEquals(new KnowledgeEntryRevision(2), knowledgeVectors.replaced.get(0).revision());
        assertEquals(KnowledgeIndexJobStatus.READY, jobs.stored().status());
    }

    @Test
    void aReclaimedEntryJobSkipsEmbeddingWhenItsRevisionIsAlreadyCommitted() {
        // Prior attempt committed revision 1 and its checkpoint before the lease expired;
        // the head is unchanged, so the reclaim may skip the embedding round trip.
        knowledge.effective = Optional.of(version(1, "Title", "body"));
        KnowledgeIndexJob enqueued = jobs.create(KnowledgeIndexJob.knowledgeEntry(
                UUID.randomUUID(), organizationId, teamId, entryId, actor, NOW));
        jobs.insertCheckpoint(enqueued.id(), 0L, 1, 1);
        knowledgeVectors.committed.add(new KnowledgeEntryRevision(1));

        KnowledgeIndexWorkerRunResult result = worker(20000).runOnce();

        assertEquals(new KnowledgeIndexWorkerRunResult(1, 0, 0), result);
        assertTrue(executor.commands.isEmpty(), "the committed row is the resume authority");
        assertTrue(knowledgeVectors.replaced.isEmpty());
        KnowledgeIndexJob done = jobs.stored();
        assertEquals(KnowledgeIndexJobStatus.READY, done.status());
        assertEquals(1, done.chunksTotal());
    }

    @Test
    void aReclaimedEntryJobReEmbedsWhenTheHeadMovedToANewRevision() {
        // The checkpoint survived the lease expiry, but the committed vector row names
        // revision 1 while the head moved to revision 2: skipping would pin the vectors
        // to the old revision and leave the projection PENDING forever. The row wins.
        knowledge.effective = Optional.of(version(2, "Second", "the current truth"));
        KnowledgeIndexJob enqueued = jobs.create(KnowledgeIndexJob.knowledgeEntry(
                UUID.randomUUID(), organizationId, teamId, entryId, actor, NOW));
        jobs.insertCheckpoint(enqueued.id(), 0L, 1, 1);
        knowledgeVectors.committed.add(new KnowledgeEntryRevision(1));

        KnowledgeIndexWorkerRunResult result = worker(20000).runOnce();

        assertEquals(new KnowledgeIndexWorkerRunResult(1, 0, 0), result);
        assertEquals(1, executor.commands.size(), "the drifted revision must be re-embedded");
        assertEquals(new KnowledgeEntryRevision(2), knowledgeVectors.replaced.get(0).revision());
        assertEquals(KnowledgeIndexJobStatus.READY, jobs.stored().status());
    }

    @Test
    void aRetiredHeadDrainsVectorsWithoutEmbedding() {
        knowledge.effective = Optional.empty();
        jobs.create(KnowledgeIndexJob.knowledgeEntry(
                UUID.randomUUID(), organizationId, teamId, entryId, actor, NOW));

        KnowledgeIndexWorkerRunResult result = worker(20000).runOnce();

        assertEquals(new KnowledgeIndexWorkerRunResult(1, 0, 0), result);
        assertTrue(executor.commands.isEmpty(), "a drained entry never spends an embedding");
        assertEquals(List.of(entryId), knowledgeVectors.drained);
        assertEquals(KnowledgeIndexJobStatus.READY, jobs.stored().status());
        assertEquals(0, jobs.stored().chunksTotal());
    }

    @Test
    void anOversizedEntryFailsExplicitlyInsteadOfBeingTruncated() {
        knowledge.effective = Optional.of(version(
                1, "Huge", "x".repeat(EmbeddingClient.MAX_INPUT_CHARS)));
        jobs.create(KnowledgeIndexJob.knowledgeEntry(
                UUID.randomUUID(), organizationId, teamId, entryId, actor, NOW));

        KnowledgeIndexWorkerRunResult result = worker(20000).runOnce();

        assertEquals(new KnowledgeIndexWorkerRunResult(1, 1, 0), result);
        assertTrue(executor.commands.isEmpty(), "an oversized entry is rejected pre-HTTP");
        assertTrue(knowledgeVectors.replaced.isEmpty());
        KnowledgeIndexJob done = jobs.stored();
        assertEquals(KnowledgeIndexJobStatus.FAILED, done.status());
        assertEquals("CHUNK_TOO_LARGE", done.failureCode().orElseThrow());
        assertEquals(List.of("FAILURE/INVALID_INPUT"), telemetry.completed);
    }

    @Test
    void anEmbeddingDeliveryFailureCarriesTheSanitizedCode() {
        knowledge.effective = Optional.of(version(1, "Title", "body"));
        executor.deliveryFailure =
                new EmbeddingDeliveryException(ModelConnectionHealthFailureCode.PROVIDER_REJECTED);
        jobs.create(KnowledgeIndexJob.knowledgeEntry(
                UUID.randomUUID(), organizationId, teamId, entryId, actor, NOW));

        KnowledgeIndexWorkerRunResult result = worker(20000).runOnce();

        assertEquals(new KnowledgeIndexWorkerRunResult(1, 1, 0), result);
        KnowledgeIndexJob done = jobs.stored();
        assertEquals(KnowledgeIndexJobStatus.FAILED, done.status());
        assertEquals("PROVIDER_REJECTED", done.failureCode().orElseThrow());
        assertTrue(knowledgeVectors.replaced.isEmpty());
    }

    // ------------------------------------------------------------------ fencing

    @Test
    void aStaleClaimIsCountedAsFencedAndDegradesTelemetry() {
        knowledge.effective = Optional.of(version(1, "Title", "body"));
        jobs.create(KnowledgeIndexJob.knowledgeEntry(
                UUID.randomUUID(), organizationId, teamId, entryId, actor, NOW));
        jobs.fenceUpdates = true;

        KnowledgeIndexWorkerRunResult result = worker(20000).runOnce();

        assertEquals(new KnowledgeIndexWorkerRunResult(1, 0, 1), result);
        assertEquals(KnowledgeIndexJobStatus.CHUNKING, jobs.stored().status(),
                "a fenced write must not fail the job; the new owner re-runs it");
        assertEquals(List.of("DEGRADED/FENCED"), telemetry.completed);
        assertTrue(telemetry.started.stream()
                .allMatch(request -> request.type() == OperationalTelemetry.Type.KNOWLEDGE_INDEX));
    }

    // ------------------------------------------------------------------ repository builds

    @Test
    void aRepositoryBuildOpensWritesAndActivatesOneGeneration() {
        content.files.add(new RepositoryFileRef("README.md", 12));
        content.contents.put("README.md", "# Title\nbody");
        content.files.add(new RepositoryFileRef("src/Main.java", 24));
        content.contents.put("src/Main.java", "class Main {\n}\n");
        jobs.create(KnowledgeIndexJob.repositoryBuild(
                UUID.randomUUID(), organizationId, teamId, projectId, indexKey(), actor, NOW));

        KnowledgeIndexWorkerRunResult result = worker(20000).runOnce();

        assertEquals(new KnowledgeIndexWorkerRunResult(1, 0, 0), result);
        assertEquals(List.of("open", "replaceBatch:1", "activate:1"), trace);
        assertEquals(2, chunkStore.written.size());
        assertEquals("README.md", chunkStore.written.get(0).path());
        assertEquals("markdown", chunkStore.written.get(0).language());
        assertEquals(1, chunkStore.written.get(0).startLine());
        assertEquals(2, chunkStore.written.get(0).endLine());
        assertEquals("src/Main.java", chunkStore.written.get(1).path());
        assertEquals(1, chunkStore.written.get(1).startLine());
        assertEquals(3, chunkStore.written.get(1).endLine());
        assertEquals(List.of(1, 2), chunkStore.written.stream()
                .map(RepositoryChunkVector::chunkSeq).toList());
        assertEquals(MODEL, chunkStore.written.get(0).model());
        KnowledgeIndexJob done = jobs.stored();
        assertEquals(KnowledgeIndexJobStatus.READY, done.status());
        assertEquals(1, done.generationBuildSequence());
        assertEquals(2, done.chunksDone());
        assertEquals(2, done.chunksTotal());
        assertEquals(List.of(1), jobs.checkpoints());
    }

    @Test
    void aReclaimedRepositoryBuildResumesAfterTheCommittedBatchTail() {
        // Twelve one-chunk files make two embedding batches (MAX_BATCH = 10). The prior
        // attempt committed the first batch's checkpoint before its lease expired, so the
        // reclaim must resume at chunk 11 — the batch tail — and never re-embed chunks 1-10.
        for (int i = 1; i <= 12; i++) {
            String path = String.format("src/f%02d.txt", i);
            content.files.add(new RepositoryFileRef(path, 8));
            content.contents.put(path, "content " + i);
        }
        KnowledgeIndexJob enqueued = jobs.create(KnowledgeIndexJob.repositoryBuild(
                UUID.randomUUID(), organizationId, teamId, projectId, indexKey(), actor, NOW));
        jobs.insertCheckpoint(enqueued.id(), 0L, 1, EmbeddingClient.MAX_BATCH);

        KnowledgeIndexWorkerRunResult result = worker(20000).runOnce();

        assertEquals(new KnowledgeIndexWorkerRunResult(1, 0, 0), result);
        assertEquals(1, executor.commands.size(), "only the tail batch reaches the provider");
        assertEquals(2, executor.commands.get(0).inputs().size());
        assertEquals(List.of(11, 12), chunkStore.written.stream()
                .map(RepositoryChunkVector::chunkSeq).toList());
        assertEquals(List.of("open", "replaceBatch:1", "activate:1"), trace);
        KnowledgeIndexJob done = jobs.stored();
        assertEquals(KnowledgeIndexJobStatus.READY, done.status());
        assertEquals(12, done.chunksTotal());
        assertEquals(12, done.chunksDone());
        assertEquals(List.of(1, 11), jobs.checkpoints());
    }

    @Test
    void aRepositoryBuildOverTheChunkBudgetFailsWithoutOpeningAGeneration() {
        content.files.add(new RepositoryFileRef("a.txt", 4));
        content.contents.put("a.txt", "alpha");
        content.files.add(new RepositoryFileRef("b.txt", 4));
        content.contents.put("b.txt", "beta");
        jobs.create(KnowledgeIndexJob.repositoryBuild(
                UUID.randomUUID(), organizationId, teamId, projectId, indexKey(), actor, NOW));

        KnowledgeIndexWorkerRunResult result = worker(1).runOnce();

        assertEquals(new KnowledgeIndexWorkerRunResult(1, 1, 0), result);
        assertTrue(trace.isEmpty(), "the budget check precedes the generation open");
        KnowledgeIndexJob done = jobs.stored();
        assertEquals(KnowledgeIndexJobStatus.FAILED, done.status());
        assertEquals("CHUNK_LIMIT_EXCEEDED", done.failureCode().orElseThrow());
        assertTrue(chunkStore.written.isEmpty());
    }

    @Test
    void aModelDriftFailsTheJobAndItsGeneration() {
        content.files.add(new RepositoryFileRef("a.txt", 4));
        content.contents.put("a.txt", "alpha");
        executor.model = DRIFTED;
        jobs.create(KnowledgeIndexJob.repositoryBuild(
                UUID.randomUUID(), organizationId, teamId, projectId, indexKey(), actor, NOW));

        KnowledgeIndexWorkerRunResult result = worker(20000).runOnce();

        assertEquals(new KnowledgeIndexWorkerRunResult(1, 1, 0), result);
        // The failed attempt's generation is marked FAILED and its vector rows dropped:
        // a never-retried build leaves neither an eternal BUILDING row nor orphans.
        assertEquals(List.of("open", "fail:1", "deleteByGeneration:1"), trace);
        KnowledgeIndexJob done = jobs.stored();
        assertEquals(KnowledgeIndexJobStatus.FAILED, done.status());
        assertEquals("MODEL_DRIFT", done.failureCode().orElseThrow());
        assertTrue(chunkStore.written.isEmpty(),
                "drifted vectors must never reach the store");
    }

    // ------------------------------------------------------------------ fixtures

    private RepositoryIndexKey indexKey() {
        return new RepositoryIndexKey(
                organizationId, teamId, bindingId, commit,
                ChunkingPolicy.defaults().policyHash(), MODEL);
    }

    private KnowledgeIndexWorker worker(int maxChunks) {
        return new KnowledgeIndexWorker(
                jobs,
                executor,
                knowledge,
                knowledgeVectors,
                content,
                chunkStore,
                generationStore,
                new DirectTransactionExecutor(),
                clock,
                telemetry,
                "worker-test",
                LEASE,
                GenerationRetentionPolicy.DEFAULT,
                maxChunks,
                ChunkingPolicy.defaults());
    }

    private KnowledgeEntryVersion version(long revision, String title, String body) {
        return KnowledgeEntryVersion.create(
                entryId,
                new io.crewscope.domain.team.TeamScope(organizationId, teamId),
                new KnowledgeEntryRevision(revision),
                revision == 1
                        ? Optional.empty()
                        : Optional.of(new KnowledgeEntryRevision(revision - 1)),
                title,
                body,
                actor,
                NOW);
    }

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

    private static final class DirectTransactionExecutor implements TransactionExecutor {
        @Override
        public <T> T required(Supplier<T> operation) {
            return operation.get();
        }
    }

    /** Configurable delivery: fixed vectors of the current model, or a delivery failure. */
    private static final class FakeEmbeddingExecutor implements KnowledgeEmbeddingExecutor {
        private final List<TeamEmbeddingCommand> commands = new ArrayList<>();
        private EmbeddingModelRevision model = MODEL;
        private EmbeddingDeliveryException deliveryFailure;

        @Override
        public EmbeddingModelRevision resolveModel(OrganizationId organizationId, TeamId teamId) {
            return model;
        }

        @Override
        public EmbeddingBatchResult embed(TeamEmbeddingCommand command) {
            commands.add(command);
            if (deliveryFailure != null) {
                throw deliveryFailure;
            }
            float[][] vectors = new float[command.inputs().size()][model.dimension()];
            for (float[] vector : vectors) {
                java.util.Arrays.fill(vector, 0.25f);
            }
            return new EmbeddingBatchResult(
                    List.of(vectors),
                    model,
                    new ModelCatalogCoordinate(
                            io.crewscope.domain.model.ModelCatalogEntryId.generate(),
                            new ModelProviderKey("dashscope"),
                            new io.crewscope.domain.model.ModelId(model.modelKey()),
                            new io.crewscope.domain.model.ModelCatalogRevision(
                                    model.revision())),
                    ModelConnectionId.generate(),
                    1);
        }
    }

    /** Only the effective-version gate is exercised; other reads fail loudly. */
    private static final class FakeKnowledgeRepository implements KnowledgeRepository {
        private Optional<KnowledgeEntryVersion> effective = Optional.empty();

        @Override
        public KnowledgeEntry create(KnowledgeEntry entry) {
            throw new UnsupportedOperationException();
        }

        @Override
        public KnowledgeEntry save(
                KnowledgeEntry entry, Optional<KnowledgeEntryVersion> appendedVersion) {
            throw new UnsupportedOperationException();
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
            return effective;
        }

        @Override
        public List<KnowledgeEntryVersion> findEffectiveVersionsByTeam(
                OrganizationId organizationId, TeamId teamId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeKnowledgeVectorStore implements KnowledgeEmbeddingVectorStore {
        private final List<KnowledgeEmbeddingVector> replaced = new ArrayList<>();
        private final List<KnowledgeEntryId> drained = new ArrayList<>();
        private final Set<KnowledgeEntryRevision> committed = new LinkedHashSet<>();

        @Override
        public void replace(KnowledgeEmbeddingVector vector) {
            replaced.add(vector);
            committed.add(vector.revision());
        }

        @Override
        public int deleteByEntry(
                OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId) {
            drained.add(entryId);
            return drained.size();
        }

        @Override
        public boolean isEmbedded(
                OrganizationId organizationId,
                TeamId teamId,
                KnowledgeEntryId entryId,
                KnowledgeEntryRevision revision) {
            return committed.contains(revision);
        }

        @Override
        public List<ScoredKnowledgeEmbedding> nearest(KnowledgeEmbeddingQuery query) {
            throw new UnsupportedOperationException("A01/I02 territory");
        }
    }

    private static final class FakeContentPort implements RepositoryContentPort {
        private final List<RepositoryFileRef> files = new ArrayList<>();
        private final Map<String, String> contents = new LinkedHashMap<>();

        @Override
        public List<RepositoryFileRef> listFiles(
                OrganizationId organizationId,
                TeamId teamId,
                RepositoryBindingId bindingId,
                SourceCommit commit) {
            return files.stream()
                    .sorted(Comparator.comparing(RepositoryFileRef::path))
                    .toList();
        }

        @Override
        public RepositoryFileContent readFile(
                OrganizationId organizationId,
                TeamId teamId,
                RepositoryBindingId bindingId,
                SourceCommit commit,
                String path) {
            return new RepositoryFileContent(path, contents.get(path));
        }
    }

    private static final class FakeChunkStore implements RepositoryChunkVectorStore {
        private final List<String> trace;
        private final List<RepositoryChunkVector> written = new ArrayList<>();

        private FakeChunkStore(List<String> trace) {
            this.trace = trace;
        }

        @Override
        public void replaceBatch(
                RepositoryGenerationKey generation, List<RepositoryChunkVector> vectors) {
            trace.add("replaceBatch:" + generation.buildSequence());
            written.addAll(vectors);
        }

        @Override
        public int deleteByGeneration(RepositoryGenerationKey generation) {
            trace.add("deleteByGeneration:" + generation.buildSequence());
            return 0;
        }
    }

    private static final class FakeGenerationStore implements RepositoryGenerationStore {
        private final List<String> trace;
        private long sequence;

        private FakeGenerationStore(List<String> trace) {
            this.trace = trace;
        }

        @Override
        public RepositoryGeneration open(RepositoryIndexKey indexKey, UUID jobId) {
            trace.add("open");
            return new RepositoryGeneration(
                    new RepositoryGenerationKey(indexKey, ++sequence), jobId,
                    GenerationStatus.BUILDING);
        }

        @Override
        public boolean activate(
                RepositoryGenerationKey generationKey,
                GenerationRetentionPolicy retention,
                io.crewscope.domain.shared.id.PrincipalId activatedBy) {
            trace.add("activate:" + generationKey.buildSequence());
            return true;
        }

        @Override
        public void fail(RepositoryGenerationKey generationKey) {
            trace.add("fail:" + generationKey.buildSequence());
        }
    }

    private static final class RecordingTelemetry implements OperationalTelemetry {
        private final List<Request> started = new ArrayList<>();
        private final List<String> completed = new ArrayList<>();

        @Override
        public OperationalTelemetry.Observation start(Request request) {
            started.add(request);
            return (outcome, errorCode) -> completed.add(outcome + "/" + errorCode);
        }
    }

    /** In-memory job store implementing the claim/checkpoint/fencing semantics. */
    private static final class FakeJobRepository implements KnowledgeIndexJobRepository {
        private record Checkpoint(int seq, int count) {}

        private final Map<UUID, KnowledgeIndexJob> values = new LinkedHashMap<>();
        private final Map<UUID, List<Checkpoint>> checkpoints = new LinkedHashMap<>();
        private boolean fenceUpdates;

        @Override
        public KnowledgeIndexJob create(KnowledgeIndexJob job) {
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
            return live(job -> job.entryId().equals(Optional.of(entryId))
                    && job.organizationId().equals(organizationId)
                    && job.teamId().equals(teamId));
        }

        @Override
        public Optional<KnowledgeIndexJob> findLiveByIndexKey(RepositoryIndexKey indexKey) {
            return live(job -> job.indexKey().equals(Optional.of(indexKey)));
        }

        @Override
        public Optional<KnowledgeIndexJob> findLatestByEntry(
                OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId) {
            return values.values().stream()
                    .filter(job -> job.entryId().equals(Optional.of(entryId)))
                    .max(Comparator.comparing(KnowledgeIndexJob::updatedAt));
        }

        @Override
        public Optional<KnowledgeIndexJob> claimNext(
                String owner, UtcTimestamp now, Duration leaseDuration) {
            Optional<KnowledgeIndexJob> candidate = values.values().stream()
                    .filter(job -> !job.status().terminal()
                            && (job.status() == KnowledgeIndexJobStatus.QUEUED
                                    || job.leaseExpiresAt()
                                            .map(lease -> lease.value().isBefore(now.value()))
                                            .orElse(false)))
                    .min(Comparator.comparing(KnowledgeIndexJob::createdAt));
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
            if (fenceUpdates
                    || stored == null
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
            checkpoints.get(jobId).add(new Checkpoint(chunkSeq, chunkCount));
            return true;
        }

        @Override
        public int maxCheckpointSeq(UUID jobId) {
            // Port contract: the tail of the furthest batch, so a resumed run starts at
            // the next chunk instead of re-embedding the whole batch head-first.
            return checkpoints.getOrDefault(jobId, List.of()).stream()
                    .mapToInt(checkpoint -> checkpoint.seq() + checkpoint.count() - 1)
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

        private Optional<KnowledgeIndexJob> live(java.util.function.Predicate<KnowledgeIndexJob> scope) {
            return values.values().stream()
                    .filter(job -> !job.status().terminal())
                    .filter(scope)
                    .min(Comparator.comparing(KnowledgeIndexJob::createdAt));
        }

        private KnowledgeIndexJob stored() {
            return values.values().iterator().next();
        }

        private List<Integer> checkpoints() {
            return checkpoints.get(stored().id()).stream()
                    .map(Checkpoint::seq)
                    .toList();
        }
    }
}
