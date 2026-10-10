package io.crewscope.application.retrieval;

import static io.crewscope.application.retrieval.KnowledgeIndexFailureCodes.CHUNK_LIMIT_EXCEEDED;
import static io.crewscope.application.retrieval.KnowledgeIndexFailureCodes.CHUNK_TOO_LARGE;
import static io.crewscope.application.retrieval.KnowledgeIndexFailureCodes.GENERATION_CONFLICT;
import static io.crewscope.application.retrieval.KnowledgeIndexFailureCodes.INTERNAL;
import static io.crewscope.application.retrieval.KnowledgeIndexFailureCodes.MODEL_DRIFT;

import io.crewscope.application.embedding.EmbeddingBatchResult;
import io.crewscope.application.embedding.EmbeddingClient;
import io.crewscope.application.embedding.EmbeddingDeliveryException;
import io.crewscope.application.embedding.TeamEmbeddingCommand;
import io.crewscope.application.knowledge.KnowledgeRepository;
import io.crewscope.application.observability.OperationalTelemetry;
import io.crewscope.application.observability.OperationalTelemetry.ErrorCode;
import io.crewscope.application.observability.OperationalTelemetry.Observation;
import io.crewscope.application.observability.OperationalTelemetry.Outcome;
import io.crewscope.application.observability.OperationalTelemetry.Request;
import io.crewscope.application.retrieval.RepositoryContentPort.RepositoryFileContent;
import io.crewscope.application.retrieval.RepositoryContentPort.RepositoryFileRef;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.knowledge.KnowledgeEntryVersion;
import io.crewscope.domain.retrieval.GenerationRetentionPolicy;
import io.crewscope.domain.retrieval.RepositoryGenerationKey;
import io.crewscope.domain.retrieval.RepositoryIndexKey;
import io.crewscope.domain.retrieval.chunking.ChunkingPolicy;
import io.crewscope.domain.retrieval.chunking.CodeLineChunker;
import io.crewscope.domain.retrieval.chunking.MarkdownHeadingChunker;
import io.crewscope.domain.retrieval.chunking.SourceChunk;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The durable knowledge-index engine (M10-I01b): claims one live job per pass and walks
 * it through CHUNKING → EMBEDDING → ACTIVATING → READY — repository builds only; a
 * knowledge entry is one whole chunk whose activation is inlined into the EMBEDDING
 * transaction, so entry jobs never enter ACTIVATING. Entries are re-read through the
 * authoritative effective-version gate at claim time, so event order never decides what
 * gets indexed; a non-PUBLISHED head drains the entry's vectors instead. Repository
 * builds chunk the binding's tree at the frozen commit under the frozen policy, embed in
 * batches of {@link EmbeddingClient#MAX_BATCH} with per-batch checkpoints (resume = max
 * committed sequence + 1), and finish with the single transaction generation activation.
 * Every write is fenced by the claim token: an observable FENCE aborts the pass as
 * {@code DEGRADED/FENCED}, never as silent loss.
 */
public final class KnowledgeIndexWorker {

    private static final String BATCH_COMMAND_ID_NAMESPACE =
            "io.crewscope/knowledge-index-batch-v1/";
    private static final Map<String, String> EXTENSION_LANGUAGES = Map.ofEntries(
            Map.entry("java", "java"),
            Map.entry("kt", "kotlin"),
            Map.entry("kts", "kotlin"),
            Map.entry("ts", "typescript"),
            Map.entry("tsx", "typescript"),
            Map.entry("js", "javascript"),
            Map.entry("mjs", "javascript"),
            Map.entry("cjs", "javascript"),
            Map.entry("py", "python"),
            Map.entry("go", "go"),
            Map.entry("rs", "rust"),
            Map.entry("rb", "ruby"),
            Map.entry("php", "php"),
            Map.entry("c", "c"),
            Map.entry("h", "c"),
            Map.entry("cpp", "cpp"),
            Map.entry("cc", "cpp"),
            Map.entry("hpp", "cpp"),
            Map.entry("cs", "csharp"),
            Map.entry("swift", "swift"),
            Map.entry("sql", "sql"),
            Map.entry("sh", "shell"),
            Map.entry("bash", "shell"),
            Map.entry("yml", "yaml"),
            Map.entry("yaml", "yaml"),
            Map.entry("json", "json"),
            Map.entry("xml", "xml"),
            Map.entry("toml", "toml"),
            Map.entry("tf", "terraform"),
            Map.entry("properties", "properties"));

    private final KnowledgeIndexJobRepository jobs;
    private final KnowledgeEmbeddingExecutor embeddings;
    private final KnowledgeRepository knowledge;
    private final KnowledgeEmbeddingVectorStore knowledgeVectors;
    private final RepositoryContentPort repositoryContent;
    private final RepositoryChunkVectorStore repositoryChunkVectors;
    private final RepositoryGenerationStore generations;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;
    private final OperationalTelemetry telemetry;
    private final String workerId;
    private final Duration leaseDuration;
    private final GenerationRetentionPolicy retentionPolicy;
    private final int maxChunksPerGeneration;
    private final ChunkingPolicy policy;
    private final CodeLineChunker lineChunker;
    private final MarkdownHeadingChunker markdownChunker;

    public KnowledgeIndexWorker(
            KnowledgeIndexJobRepository jobs,
            KnowledgeEmbeddingExecutor embeddings,
            KnowledgeRepository knowledge,
            KnowledgeEmbeddingVectorStore knowledgeVectors,
            RepositoryContentPort repositoryContent,
            RepositoryChunkVectorStore repositoryChunkVectors,
            RepositoryGenerationStore generations,
            TransactionExecutor transactions,
            TimeProvider timeProvider,
            OperationalTelemetry telemetry,
            String workerId,
            Duration leaseDuration,
            GenerationRetentionPolicy retentionPolicy,
            int maxChunksPerGeneration,
            ChunkingPolicy chunkingPolicy) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.embeddings = Objects.requireNonNull(embeddings, "embeddings");
        this.knowledge = Objects.requireNonNull(knowledge, "knowledge");
        this.knowledgeVectors = Objects.requireNonNull(knowledgeVectors, "knowledgeVectors");
        this.repositoryContent = Objects.requireNonNull(repositoryContent, "repositoryContent");
        this.repositoryChunkVectors =
                Objects.requireNonNull(repositoryChunkVectors, "repositoryChunkVectors");
        this.generations = Objects.requireNonNull(generations, "generations");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.workerId = requireWorkerId(workerId);
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        this.retentionPolicy = Objects.requireNonNull(retentionPolicy, "retentionPolicy");
        if (maxChunksPerGeneration < 1) {
            throw new IllegalArgumentException("maxChunksPerGeneration must be positive");
        }
        this.maxChunksPerGeneration = maxChunksPerGeneration;
        ChunkingPolicy policy = Objects.requireNonNull(chunkingPolicy, "chunkingPolicy");
        this.policy = policy;
        this.lineChunker = new CodeLineChunker(policy);
        this.markdownChunker = new MarkdownHeadingChunker(policy);
    }

    /** Claims and advances at most one job; a fenced write is counted, never hidden. */
    public KnowledgeIndexWorkerRunResult runOnce() {
        Optional<KnowledgeIndexJob> claimed =
                jobs.claimNext(workerId, timeProvider.now(), leaseDuration);
        if (claimed.isEmpty()) {
            return new KnowledgeIndexWorkerRunResult(0, 0, 0, 0);
        }
        KnowledgeIndexJob job = claimed.get();
        Observation observation = telemetry.start(Request.knowledgeIndex());
        try {
            FinishedJob finished = switch (job.source()) {
                case KNOWLEDGE_ENTRY -> new FinishedJob(runKnowledgeEntry(job), 0);
                case REPOSITORY -> runRepository(job);
            };
            observation.succeed();
            return new KnowledgeIndexWorkerRunResult(
                    1,
                    finished.job().status() == KnowledgeIndexJobStatus.FAILED ? 1 : 0,
                    0,
                    finished.skippedFiles());
        } catch (JobFailedException failure) {
            failClaimed(job, failure.code());
            observation.complete(Outcome.FAILURE, errorCodeOf(failure.code()));
            return new KnowledgeIndexWorkerRunResult(1, 1, 0, 0);
        } catch (FencedWriteException fenced) {
            observation.complete(Outcome.DEGRADED, ErrorCode.FENCED);
            return new KnowledgeIndexWorkerRunResult(1, 0, 1, 0);
        } catch (RepositoryContentFailure contentFailure) {
            failClaimed(job, contentFailure.failureCode());
            observation.complete(Outcome.FAILURE, errorCodeOf(contentFailure.failureCode()));
            return new KnowledgeIndexWorkerRunResult(1, 1, 0, 0);
        } catch (RuntimeException unexpected) {
            failClaimed(job, INTERNAL);
            observation.complete(Outcome.FAILURE, ErrorCode.UNKNOWN);
            return new KnowledgeIndexWorkerRunResult(1, 1, 0, 0);
        }
    }

    // ---------------------------------------------------------------- knowledge entries

    private KnowledgeIndexJob runKnowledgeEntry(KnowledgeIndexJob job) {
        KnowledgeEntryId entryId = job.entryId().orElseThrow();
        Optional<KnowledgeEntryVersion> effective = knowledge.findEffectiveVersion(
                job.organizationId(), job.teamId(), entryId);
        if (effective.isEmpty()) {
            // Authoritative gate: the head is no longer PUBLISHED, so drain the vectors.
            knowledgeVectors.deleteByEntry(job.organizationId(), job.teamId(), entryId);
            return commit(job.ready(timeProvider.now()));
        }
        KnowledgeEntryVersion version = effective.get();
        String text = embeddableText(version);
        if (text.length() > EmbeddingClient.MAX_INPUT_CHARS) {
            throw new JobFailedException(CHUNK_TOO_LARGE);
        }
        KnowledgeIndexJob chunking = advance(job, KnowledgeIndexJobStatus.CHUNKING, 0, 1);
        // Resume authority is the committed vector row, never the bare checkpoint: a
        // reclaimed job whose entry moved to a new revision re-embeds it, because the
        // stored row still names the old one and the projection would stay PENDING.
        boolean alreadyEmbedded = jobs.maxCheckpointSeq(chunking.id()) > 0
                && knowledgeVectors.isEmbedded(
                        chunking.organizationId(), chunking.teamId(), entryId, version.revision());
        if (!alreadyEmbedded) {
            EmbeddingBatchResult result = embedBatch(chunking, List.of(text), 1);
            KnowledgeEmbeddingVector vector = new KnowledgeEmbeddingVector(
                    chunking.organizationId(),
                    chunking.teamId(),
                    entryId,
                    version.revision(),
                    result.model(),
                    version.contentHash().value(),
                    result.vectors().get(0));
            transactions.required(() -> {
                // Same-transaction old-row purge: a superseded revision never lingers.
                knowledgeVectors.deleteByEntry(
                        chunking.organizationId(), chunking.teamId(), entryId);
                knowledgeVectors.replace(vector);
                return null;
            });
            checkpoint(chunking, 1, 1);
        }
        job = chunking;
        return commit(advance(job, KnowledgeIndexJobStatus.EMBEDDING, 1, 1)
                .ready(timeProvider.now()));
    }

    /** One knowledge entry is exactly one chunk: title, blank line, content. */
    private static String embeddableText(KnowledgeEntryVersion version) {
        return version.title() + "\n\n" + version.content();
    }

    // ---------------------------------------------------------------- repository builds

    private FinishedJob runRepository(KnowledgeIndexJob job) {
        RepositoryIndexKey indexKey = job.indexKey().orElseThrow();
        RepositoryGeneration opened = null;
        try {
            RepositoryChunking chunking = chunkRepository(indexKey);
            List<SourceChunk> chunks = chunking.chunks();
            int skippedFiles = chunking.skippedFiles();
            if (chunks.size() > maxChunksPerGeneration) {
                throw new JobFailedException(CHUNK_LIMIT_EXCEEDED);
            }
            int resumeSeq = jobs.maxCheckpointSeq(job.id()) + 1;
            job = advance(job, KnowledgeIndexJobStatus.CHUNKING, resumeSeq - 1, chunks.size());
            opened = generations.open(indexKey, job.id());
            job = commit(job
                    .withGeneration(opened.generationKey().buildSequence(), timeProvider.now())
                    .withStatus(KnowledgeIndexJobStatus.EMBEDDING, timeProvider.now())
                    .withProgress(resumeSeq - 1, chunks.size(), timeProvider.now()));
            RepositoryGenerationKey generationKey = opened.generationKey();
            for (int first = resumeSeq; first <= chunks.size();
                    first += EmbeddingClient.MAX_BATCH) {
                int last = Math.min(first + EmbeddingClient.MAX_BATCH - 1, chunks.size());
                List<SourceChunk> batch = chunks.subList(first - 1, last);
                EmbeddingBatchResult result = embedBatch(job, embedInputs(batch), first);
                if (!result.model().equals(indexKey.embeddingModelRevision())) {
                    throw new JobFailedException(MODEL_DRIFT);
                }
                repositoryChunkVectors.replaceBatch(
                        generationKey, repositoryVectors(generationKey, result, batch, first));
                checkpoint(job, first, batch.size());
                job = advance(job, KnowledgeIndexJobStatus.EMBEDDING, last, chunks.size());
            }
            job = advance(job, KnowledgeIndexJobStatus.ACTIVATING,
                    chunks.size(), chunks.size());
            if (!generations.activate(generationKey, retentionPolicy, job.createdBy())) {
                throw new JobFailedException(GENERATION_CONFLICT);
            }
            return new FinishedJob(commit(job.ready(timeProvider.now())), skippedFiles);
        } catch (JobFailedException failure) {
            discardAttempt(opened);
            throw failure;
        } catch (FencedWriteException fenced) {
            // Deliberately no cleanup: the job is still alive and the reclaimer resumes
            // this very job, and open() reuses its BUILDING generation — the rows and
            // honest checkpoints committed before the fence are the resume substrate.
            // Discarding here would delete the reclaimer's half-built generation.
            throw fenced;
        } catch (RuntimeException unexpected) {
            discardAttempt(opened);
            throw unexpected;
        }
    }

    /**
     * Best-effort cleanup of a terminally failed attempt's own generation: mark it
     * FAILED and drop its chunk vectors, so a never-retried build leaves neither an
     * eternal BUILDING row nor invisible orphan vectors. Only called on paths that
     * fail the job itself — never on fencing, where the generation survives for the
     * reclaimer. A secondary failure here must not mask the primary one.
     */
    private void discardAttempt(RepositoryGeneration opened) {
        if (opened == null) {
            return;
        }
        try {
            generations.fail(opened.generationKey());
            repositoryChunkVectors.deleteByGeneration(opened.generationKey());
        } catch (RuntimeException cleanupFailure) {
            // Retention pruning reclaims both on the next successful build.
        }
    }

    /**
     * Collects the repository's chunks under the frozen policy. M10-Q02 follow-up: a file
     * whose chunking yields a chunk over {@link EmbeddingClient#MAX_INPUT_CHARS} is skipped
     * whole — the line and heading chunkers never split a single line, so a 33k+-character
     * line (minified JS, a long markdown table row) makes the file un-embeddable at any
     * window. Skipping is per file and counted through the run result, never silent (S01's
     * no-silent-truncation rule); the application layer owns no logger and the job row
     * stays closed-field, so per-file detail is not persisted — the count is the observable.
     * The entry path stays fail-closed and the per-generation chunk budget stays a
     * job-level failure.
     */
    private RepositoryChunking chunkRepository(RepositoryIndexKey indexKey) {
        List<RepositoryFileRef> files = repositoryContent.listFiles(
                indexKey.organizationId(),
                indexKey.teamId(),
                indexKey.repositoryBindingId(),
                indexKey.sourceCommit());
        List<SourceChunk> chunks = new ArrayList<>();
        int skippedFiles = 0;
        for (RepositoryFileRef file : files) {
            if (excludedByPolicy(file)) {
                continue;
            }
            RepositoryFileContent content = repositoryContent.readFile(
                    indexKey.organizationId(),
                    indexKey.teamId(),
                    indexKey.repositoryBindingId(),
                    indexKey.sourceCommit(),
                    file.path());
            List<SourceChunk> fileChunks = chunkFile(file.path(), content.content());
            boolean oversized = fileChunks.stream().anyMatch(
                    chunk -> chunk.content().length() > EmbeddingClient.MAX_INPUT_CHARS);
            if (oversized) {
                skippedFiles++;
                continue;
            }
            chunks.addAll(fileChunks);
        }
        return new RepositoryChunking(chunks, skippedFiles);
    }

    private boolean excludedByPolicy(RepositoryFileRef file) {
        return policy.excludes(file.path(), file.sizeBytes());
    }

    private List<SourceChunk> chunkFile(String path, String content) {
        if (isMarkdown(path)) {
            return markdownChunker.chunk(path, content);
        }
        return lineChunker.chunk(path, languageOf(path), content);
    }

    private List<RepositoryChunkVector> repositoryVectors(
            RepositoryGenerationKey generation,
            EmbeddingBatchResult result,
            List<SourceChunk> batch,
            int firstSeq) {
        List<RepositoryChunkVector> vectors = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            SourceChunk chunk = batch.get(i);
            vectors.add(new RepositoryChunkVector(
                    generation,
                    firstSeq + i,
                    chunk.path(),
                    chunk.language(),
                    chunk.startLine(),
                    chunk.endLine(),
                    chunk.contentHash(),
                    chunk.content(),
                    result.model(),
                    result.vectors().get(i)));
        }
        return vectors;
    }

    private static List<String> embedInputs(List<SourceChunk> batch) {
        List<String> inputs = new ArrayList<>(batch.size());
        for (SourceChunk chunk : batch) {
            inputs.add(chunk.content());
        }
        return inputs;
    }

    // ---------------------------------------------------------------- embedding seam

    private EmbeddingBatchResult embedBatch(
            KnowledgeIndexJob job, List<String> inputs, int firstChunkSeq) {
        TeamEmbeddingCommand command = new TeamEmbeddingCommand(
                job.organizationId(),
                job.teamId(),
                job.createdBy(),
                batchCommandId(job.id(), firstChunkSeq),
                job.id(),
                inputs);
        try {
            return embeddings.embed(command);
        } catch (EmbeddingDeliveryException failure) {
            throw new JobFailedException(failure.failureCode().name());
        }
    }

    private static UUID batchCommandId(UUID jobId, int firstChunkSeq) {
        String source = BATCH_COMMAND_ID_NAMESPACE + jobId + "/" + firstChunkSeq;
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- claim plumbing

    private KnowledgeIndexJob advance(
            KnowledgeIndexJob job, KnowledgeIndexJobStatus status, int done, int total) {
        UtcTimestamp now = timeProvider.now();
        return commit(job.withStatus(status, now).withProgress(done, total, now));
    }

    private KnowledgeIndexJob commit(KnowledgeIndexJob next) {
        return jobs.updateClaimed(next, workerId, timeProvider.now(), leaseDuration)
                .orElseThrow(FencedWriteException::new);
    }

    private void checkpoint(KnowledgeIndexJob job, int chunkSeq, int chunkCount) {
        if (!jobs.insertCheckpoint(job.id(), job.claimToken(), chunkSeq, chunkCount)) {
            throw new FencedWriteException();
        }
    }

    private void failClaimed(KnowledgeIndexJob job, String code) {
        try {
            commit(job.failed(code, timeProvider.now()));
        } catch (FencedWriteException fenced) {
            // The claim went stale exactly while failing; the new owner re-runs the job.
        }
    }

    private static OperationalTelemetry.ErrorCode errorCodeOf(String code) {
        return switch (code) {
            case CHUNK_TOO_LARGE, CHUNK_LIMIT_EXCEEDED -> ErrorCode.INVALID_INPUT;
            case MODEL_DRIFT, GENERATION_CONFLICT -> ErrorCode.CONFLICT;
            case INTERNAL -> ErrorCode.UNKNOWN;
            default -> ErrorCode.INVALID_RESPONSE;
        };
    }

    // ---------------------------------------------------------------- chunking helpers

    private static boolean isMarkdown(String path) {
        String name = fileName(path);
        return name.endsWith(".md") || name.endsWith(".markdown");
    }

    private static String languageOf(String path) {
        String name = fileName(path);
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return name.equals("dockerfile") ? "dockerfile" : "text";
        }
        return EXTENSION_LANGUAGES.getOrDefault(name.substring(dot + 1), "text");
    }

    private static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    }

    private static String requireWorkerId(String value) {
        String normalized = Objects.requireNonNull(value, "workerId").strip();
        if (normalized.isEmpty() || normalized.length() > 160) {
            throw new IllegalArgumentException(
                    "workerId must contain 1 to 160 characters");
        }
        return normalized;
    }

    /** Expected business failure carrying the job's stable failure code. */
    private static final class JobFailedException extends RuntimeException {

        private final String code;

        private JobFailedException(String code) {
            super("knowledge index job failed: " + code);
            this.code = code;
        }

        private String code() {
            return code;
        }
    }

    /** A claim-guarded write hit zero rows: an observable fence, not an error. */
    private static final class FencedWriteException extends RuntimeException {
        private FencedWriteException() {
            super("knowledge index claim was fenced");
        }
    }

    /** A repository's collectable chunks plus how many files were skipped as oversized. */
    private record RepositoryChunking(List<SourceChunk> chunks, int skippedFiles) {}

    /** A finished job plus how many repository files it skipped (zero for entry jobs). */
    private record FinishedJob(KnowledgeIndexJob job, int skippedFiles) {}
}
