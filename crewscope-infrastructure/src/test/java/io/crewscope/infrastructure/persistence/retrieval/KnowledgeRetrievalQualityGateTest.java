package io.crewscope.infrastructure.persistence.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.embedding.EmbeddingBatchResult;
import io.crewscope.application.embedding.EmbeddingClient;
import io.crewscope.application.embedding.TeamEmbeddingCommand;
import io.crewscope.application.knowledge.KnowledgeEntryFilter;
import io.crewscope.application.knowledge.KnowledgeEntryPage;
import io.crewscope.application.knowledge.KnowledgeEntryPageRequest;
import io.crewscope.application.knowledge.KnowledgeEntryVersionPage;
import io.crewscope.application.knowledge.KnowledgeRepository;
import io.crewscope.application.knowledge.KnowledgeVersionPageRequest;
import io.crewscope.application.model.ProviderCredentialHandle;
import io.crewscope.application.model.ProviderCredentialOperation;
import io.crewscope.application.retrieval.KnowledgeEmbeddingExecutor;
import io.crewscope.application.retrieval.KnowledgeEmbeddingVector;
import io.crewscope.application.retrieval.KnowledgeIndexJob;
import io.crewscope.application.retrieval.KnowledgeRetrievalQuery;
import io.crewscope.application.retrieval.KnowledgeRetrievalResult;
import io.crewscope.application.retrieval.KnowledgeRetrievalService;
import io.crewscope.application.retrieval.RepositoryChunkVector;
import io.crewscope.application.retrieval.RepositoryGeneration;
import io.crewscope.application.retrieval.RetrievalCandidate;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBindingScope;
import io.crewscope.domain.coding.RepositoryBindingStatus;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.coding.RepositoryKind;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.knowledge.KnowledgeEntry;
import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.knowledge.KnowledgeEntryKey;
import io.crewscope.domain.knowledge.KnowledgeEntryRevision;
import io.crewscope.domain.knowledge.KnowledgeEntryVersion;
import io.crewscope.domain.model.ModelAdapterKey;
import io.crewscope.domain.model.ModelBillingSubject;
import io.crewscope.domain.model.ModelCatalogCoordinate;
import io.crewscope.domain.model.ModelCatalogEntryId;
import io.crewscope.domain.model.ModelCatalogRevision;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelConnectionOwner;
import io.crewscope.domain.model.ModelCredentialBinding;
import io.crewscope.domain.model.ModelCredentialSubject;
import io.crewscope.domain.model.ModelCredentialVersion;
import io.crewscope.domain.model.ModelDataPolicy;
import io.crewscope.domain.model.ModelDataRetentionMode;
import io.crewscope.domain.model.ModelEndpoint;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.model.ModelProviderDefinition;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelRegion;
import io.crewscope.domain.model.ModelTrainingUsagePolicy;
import io.crewscope.domain.retrieval.EmbeddingModelRevision;
import io.crewscope.domain.retrieval.GenerationRetentionPolicy;
import io.crewscope.domain.retrieval.ManifestSourceType;
import io.crewscope.domain.retrieval.RepositoryGenerationKey;
import io.crewscope.domain.retrieval.RepositoryIndexKey;
import io.crewscope.domain.retrieval.SourceCommit;
import io.crewscope.domain.retrieval.chunking.ChunkingPolicy;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamScope;
import io.crewscope.domain.team.UninitializedTeam;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.infrastructure.model.OpenAiCompatibleEmbeddingClient;
import io.crewscope.infrastructure.persistence.knowledge.PgVectorKnowledgeEmbeddingStore;
import io.crewscope.infrastructure.testcontainers.AbstractPgVectorContainerIntegrationTest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The S01-frozen offline quality gate over the annotated dataset, run through the
 * PRODUCT {@link KnowledgeRetrievalService} path (M10-A01 plan ruling 9, layer two).
 * Enabled only when {@code S01B_DASHSCOPE_KEY_FILE} points at an {@code apikey:<value>}
 * file — the key is read into memory, consumed by the zeroing handle callback and
 * never reaches logs or failure messages. CI keeps layer one (the deterministic
 * fake-vector tests); this layer spends real embedding tokens, so the corpus is
 * ingested once and every threshold is asserted inside a single test method.
 *
 * Measurement口径 mirrors scripts/m10-s01/retrieval-prototype.mjs, which evidenced
 * these numbers in isolation; this gate re-evidences them on the product SQL:
 * 80/70-line chunking with blank-line snap, corpus = the dataset's expected files
 * plus 150 deterministic distractors sampled from the product main sources, the
 * SAME corpus ingested under two teams (isolation is proven live, not assumed), and
 * query vectors pre-embedded before the clock starts — the P50/P95/P99 numbers
 * cover the SQL + merge + rank path only, the prototype's latency口径. The τ=0.55
 * unanswerable cutoff is an evaluation-layer constant (S01 §4), never a product
 * decision: the service passes scores through untruncated.
 *
 * Frozen thresholds asserted (S01 §4): knowledge Recall@5 ≥ 0.85, Recall@10 ≥ 0.90,
 * version accuracy = 1.0, code file-level Recall@10 ≥ 0.75 (fragment-level reported
 * alongside without an absolute bar), unanswerable false positives ≤ 0.10 at τ=0.55.
 * Retired knowledge versions keep their vector rows in the store — version accuracy
 * = 1.0 is exactly the load-bearing proof that the effective gate, not row absence,
 * hides them.
 */
@EnabledIfEnvironmentVariable(named = "S01B_DASHSCOPE_KEY_FILE", matches = ".+")
class KnowledgeRetrievalQualityGateTest extends AbstractPgVectorContainerIntegrationTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-04T08:00:00Z");
    private static final String ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final EmbeddingModelRevision MODEL =
            new EmbeddingModelRevision("text-embedding-v4", 1024, 3);
    private static final int DISTRACTORS = 150;
    private static final double TAU = 0.55;
    private static final Path CACHE = Path.of("/tmp/a01-embed-cache.json");
    private static final int EMBED_TEXT_LIMIT = 32_000;

    // The corpus is ingested under team A (evaluated) and team B (live isolation proof).
    private final OrganizationId organizationId = OrganizationId.generate();
    private final Principal actor = Principal.create(
            PrincipalId.generate(),
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            "Gate Owner",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);
    private final TeamInitialization teamA = TeamInitialization.create(actor, "Gate A", NOW);
    private final TeamInitialization teamB = TeamInitialization.create(actor, "Gate B", NOW);
    private final WorkProjectId projectId = WorkProjectId.generate();
    private final RepositoryBindingId bindingId = RepositoryBindingId.generate();

    private JdbcTemplate jdbc;
    private PgVectorKnowledgeEmbeddingStore knowledgeVectors;
    private PgVectorRepositoryChunkStore chunkVectors;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void theFrozenThresholdsHoldOverTheProductRetrievalPath() throws Exception {
        Path root = repoRoot();
        List<JsonNode> knowledgeEntries = dataset(root, "knowledge-entries.json");
        List<JsonNode> knowledgeQueries = dataset(root, "knowledge-queries.json");
        List<JsonNode> codeQueries = dataset(root, "code-queries.json");
        List<JsonNode> unanswerable = dataset(root, "unanswerable-queries.json");

        // ---------- corpus ----------
        Set<String> targetFiles = new LinkedHashSet<>();
        codeQueries.forEach(q -> targetFiles.add(q.get("expect").asText()));
        List<String> distractors = distractorPool(root, targetFiles);
        List<Chunk> chunks = new ArrayList<>();
        for (String path : targetFiles) {
            chunks.addAll(chunkFile(root, path));
        }
        for (String path : distractors) {
            chunks.addAll(chunkFile(root, path));
        }
        System.out.println("[gate] corpus: " + targetFiles.size() + " target + "
                + distractors.size() + " distractor files, " + chunks.size()
                + " code chunks, " + knowledgeEntries.size() + " knowledge rows");

        // ---------- embeddings (cached by content hash; the key is never cached) ----------
        List<String> knowledgeTexts = knowledgeEntries.stream()
                .map(e -> e.get("title").asText() + "\n" + e.get("content").asText())
                .toList();
        List<String> queryTexts = new ArrayList<>();
        codeQueries.forEach(q -> queryTexts.add(q.get("query").asText()));
        knowledgeQueries.forEach(q -> queryTexts.add(q.get("query").asText()));
        unanswerable.forEach(q -> queryTexts.add(q.get("query").asText()));
        Map<String, float[]> vectors = embedAll(chunks, knowledgeTexts, queryTexts);

        // ---------- ingest ----------
        freshDatabase();
        Map<TeamId, Map<String, UUID>> entryIdsByTeam = new HashMap<>();
        Map<TeamId, Map<KnowledgeEntryId, KnowledgeEntryVersion>> versionsByTeam =
                new HashMap<>();
        for (TeamId team : List.of(teamA.team().id(), teamB.team().id())) {
            ingestKnowledge(knowledgeEntries, knowledgeTexts, vectors, team,
                    entryIdsByTeam, versionsByTeam);
            RepositoryGenerationKey generation = activateFreshGeneration(team);
            List<RepositoryChunkVector> rows = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                Chunk chunk = chunks.get(i);
                rows.add(new RepositoryChunkVector(
                        generation, i + 1, chunk.path(), "java",
                        chunk.startLine(), chunk.endLine(),
                        sha(chunk.content()), chunk.content(), MODEL,
                        vectors.get(chunk.content())));
            }
            chunkVectors.replaceBatch(generation, rows);
        }

        // ---------- the product service over the real SQL ----------
        TeamAccessContext access = new TeamAccessContext(actor, false);
        TeamId evaluated = teamA.team().id();
        KnowledgeRetrievalService service = service(
                queryTexts, vectors, versionsByTeam.get(evaluated));

        // Knowledge: Recall@5/@10 + version accuracy through the effective gate.
        double recall5 = 0;
        double recall10 = 0;
        double versionCorrect = 0;
        List<String> knowledgeMisses = new ArrayList<>();
        for (JsonNode q : knowledgeQueries) {
            KnowledgeRetrievalResult result = service.retrieve(
                    access, organizationId, evaluated,
                    knowledgeOnly(q.get("query").asText(), 10));
            assertTrue(result.degradations().isEmpty(), "the knowledge route must be healthy");
            List<RetrievalCandidate> candidates = result.candidates();
            UUID expectedId = entryIdsByTeam.get(evaluated).get(q.get("expectKey").asText());
            if (candidates.stream().limit(5)
                    .anyMatch(c -> expectedId.equals(c.entry().entryId()))) {
                recall5++;
            }
            Optional<RetrievalCandidate> hit = candidates.stream()
                    .filter(c -> expectedId.equals(c.entry().entryId())).findFirst();
            if (hit.isPresent()) {
                recall10++;
                if (hit.get().entry().revision().value() == q.get("expectVersion").asLong()) {
                    versionCorrect++;
                } else {
                    knowledgeMisses.add(q.get("id").asText() + ": stale revision "
                            + hit.get().entry().revision().value());
                }
            } else {
                knowledgeMisses.add(q.get("id").asText() + ": key absent from top-10");
            }
        }
        double knowledgeN = knowledgeQueries.size();
        report("knowledge", Map.of(
                "recallAt5", recall5 / knowledgeN,
                "recallAt10", recall10 / knowledgeN,
                "versionAccuracy", versionCorrect / knowledgeN), knowledgeMisses);
        assertTrue(recall5 / knowledgeN >= 0.85, "knowledge Recall@5 fell below the frozen 0.85");
        assertTrue(recall10 / knowledgeN >= 0.90, "knowledge Recall@10 fell below the frozen 0.90");
        assertEquals(1.0, versionCorrect / knowledgeN, 1e-9,
                "version accuracy must be exactly 1.0 — the effective gate is load-bearing");

        // Code: file-level Recall@10 (frozen ≥ 0.75) with fragment-level reported
        // alongside; the top-20 fetch also feeds the τ sweep below.
        double fileHit = 0;
        double fragmentHit = 0;
        List<String> codeMisses = new ArrayList<>();
        List<KnowledgeRetrievalResult> codeTop20 = new ArrayList<>();
        for (JsonNode q : codeQueries) {
            KnowledgeRetrievalResult result = service.retrieve(
                    access, organizationId, evaluated, repositoryOnly(q.get("query").asText(), 20));
            codeTop20.add(result);
            assertTrue(result.degradations().isEmpty(), "the repository route must be healthy");
            List<RetrievalCandidate> candidates = result.candidates();
            String expect = q.get("expect").asText();
            if (candidates.stream().limit(10)
                    .anyMatch(c -> expect.equals(c.fragments().get(0).path()))) {
                fileHit++;
            } else {
                codeMisses.add(q.get("id").asText() + ": " + shortPath(expect) + " absent; top="
                        + candidates.stream().limit(3)
                                .map(c -> shortPath(c.fragments().get(0).path()))
                                .collect(Collectors.joining(",")));
            }
            List<String> topSpans = candidates.stream().limit(10)
                    .flatMap(c -> c.fragments().stream()).limit(10)
                    .map(f -> f.path()).toList();
            if (topSpans.contains(expect)) {
                fragmentHit++;
            }
        }
        double codeN = codeQueries.size();
        report("code", Map.of(
                "fileRecallAt10", fileHit / codeN,
                "fragmentRecallAt10 (informational)", fragmentHit / codeN), codeMisses);
        assertTrue(fileHit / codeN >= 0.75, "code file-level Recall@10 fell below the frozen 0.75");

        // Unanswerable: false positives at τ=0.55 (frozen ≤ 0.10) + the τ sweep as
        // informational input for Q01's re-review of the frozen constants.
        List<Double> unanswerableMax = new ArrayList<>();
        for (JsonNode q : unanswerable) {
            KnowledgeRetrievalResult result = service.retrieve(
                    access, organizationId, evaluated,
                    "code".equals(q.get("domain").asText())
                            ? repositoryOnly(q.get("query").asText(), 1)
                            : knowledgeOnly(q.get("query").asText(), 1));
            unanswerableMax.add(result.candidates().isEmpty()
                    ? 0.0 : result.candidates().get(0).score());
        }
        List<String> sweep = new ArrayList<>();
        for (int step = 0; step <= 8; step++) {
            final double tau = 0.30 + step * 0.05;
            double fp = unanswerableMax.stream().filter(s -> s >= tau).count()
                    / (double) unanswerable.size();
            double recallAtTau = 0;
            for (int i = 0; i < codeQueries.size(); i++) {
                String expect = codeQueries.get(i).get("expect").asText();
                if (codeTop20.get(i).candidates().stream()
                        .anyMatch(c -> expect.equals(c.fragments().get(0).path())
                                && c.score() >= tau)) {
                    recallAtTau++;
                }
            }
            sweep.add(String.format("τ=%.2f fp=%.3f codeR10=%.4f", tau, fp, recallAtTau / codeN));
        }
        System.out.println("[gate] unanswerable sweep: " + String.join(" | ", sweep));
        double falsePositives = unanswerableMax.stream().filter(s -> s >= TAU).count()
                / (double) unanswerable.size();
        System.out.println(String.format(
                "[gate] unanswerable false positives at τ=%.2f: %.3f (frozen bar 0.10)",
                TAU, falsePositives));
        assertTrue(falsePositives <= 0.10,
                "unanswerable false-positive rate exceeded 0.10 at the frozen τ=0.55");

        // Latency over the product path (SQL + merge + rank; query vectors are
        // pre-embedded, matching the prototype's SQL-only口径): informational.
        double[] samples = new double[30];
        for (int i = 0; i < samples.length; i++) {
            JsonNode q = codeQueries.get(i % codeQueries.size());
            long start = System.nanoTime();
            service.retrieve(access, organizationId, evaluated,
                    repositoryOnly(q.get("query").asText(), 10));
            samples[i] = (System.nanoTime() - start) / 1_000_000.0;
        }
        Arrays.sort(samples);
        System.out.println(String.format(
                "[gate] latency ms over the product path (n=%d): p50=%.1f p95=%.1f p99=%.1f",
                samples.length, samples[samples.length / 2],
                samples[(int) (samples.length * 0.95)],
                samples[Math.min(samples.length - 1, (int) (samples.length * 0.99))]));

        // Isolation, proven live: team B holds the identical corpus and answers through
        // its own coordinates, while every number above ran strictly inside team A.
        KnowledgeRetrievalService serviceB = service(
                queryTexts, vectors, versionsByTeam.get(teamB.team().id()));
        KnowledgeRetrievalResult b = serviceB.retrieve(
                new TeamAccessContext(actor, false), organizationId, teamB.team().id(),
                repositoryOnly(codeQueries.get(0).get("query").asText(), 10));
        assertTrue(!b.candidates().isEmpty(),
                "team B ingested the identical corpus — emptiness would mean the ingest, "
                        + "not the isolation, is broken");
        assertTrue(b.candidates().stream().allMatch(
                        c -> c.fragments().get(0).bindingId().equals(bindingId)),
                "chunk coordinates always carry the requested binding");
    }

    // ------------------------------------------------------------------ corpus

    private record Chunk(String path, int startLine, int endLine, String content) {}

    /** One file split by the S01 §3.3 window: 80 lines, step 70, blank-line snap. */
    private static List<Chunk> chunkFile(Path root, String path) throws IOException {
        List<String> lines = Files.readAllLines(root.resolve(path), StandardCharsets.UTF_8);
        List<Chunk> chunks = new ArrayList<>();
        for (int start = 0; start < lines.size(); start += 70) {
            int end = Math.min(lines.size(), start + 80);
            if (end < lines.size()) {
                // Snap the trailing edge back to a blank line inside the overlap.
                for (int c = end; c > end - 10 && c > start + 20; c--) {
                    if (lines.get(c - 1).isBlank()) {
                        end = c;
                        break;
                    }
                }
            }
            String content = String.join("\n", lines.subList(start, end));
            if (!content.isBlank()) {
                chunks.add(new Chunk(path, start + 1, end, content));
            }
            if (end >= lines.size()) {
                break;
            }
        }
        return chunks;
    }

    /**
     * Deterministic distractor pool from the product main sources, mirroring the
     * prototype: exclude test sources, evaluation fixtures and integration adapters,
     * then keep files whose index digest starts with a hex digit ≡ 0 (mod 7).
     */
    private static List<String> distractorPool(Path root, Set<String> targets) throws Exception {
        String listed = new String(run(root, "git", "ls-files", "*.java"), StandardCharsets.UTF_8);
        List<String> pool = Arrays.stream(listed.split("\n"))
                .map(String::trim)
                .filter(p -> !p.isEmpty())
                .filter(p -> !p.contains("/var/") && !p.contains("src/test")
                        && !p.startsWith("evaluation/") && !p.startsWith("crewscope-integration/"))
                .filter(p -> !targets.contains(p))
                .toList();
        List<String> picked = new ArrayList<>();
        for (int i = 0; i < pool.size() && picked.size() < DISTRACTORS; i++) {
            if (sha(i + "").charAt(0) % 7 == 0) {
                picked.add(pool.get(i));
            }
        }
        return picked;
    }

    // ------------------------------------------------------------------ embeddings

    /**
     * Embeds the corpus and queries with the real DashScope compatible-mode client,
     * reusing the content-hash cache at {@code /tmp/a01-embed-cache.json} across runs.
     * The API key is consumed through the zeroing handle and never stored or logged.
     */
    private Map<String, float[]> embedAll(
            List<Chunk> chunks, List<String> knowledgeTexts, List<String> queryTexts)
            throws Exception {
        Map<String, float[]> cache = new LinkedHashMap<>();
        if (Files.exists(CACHE)) {
            json.readValue(CACHE.toFile(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, float[]>>() {})
                    .forEach(cache::put);
        }
        String keyLine = Files.readAllLines(Path.of(System.getenv("S01B_DASHSCOPE_KEY_FILE")))
                .stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("key file is empty"));
        String apiKey = keyLine.startsWith("apikey:")
                ? keyLine.substring("apikey:".length()).strip()
                : keyLine;

        Set<String> distinct = new LinkedHashSet<>();
        chunks.forEach(c -> distinct.add(c.content()));
        distinct.addAll(knowledgeTexts);
        distinct.addAll(queryTexts);
        Map<String, float[]> vectors = new HashMap<>();
        List<String> missing = new ArrayList<>();
        for (String text : distinct) {
            float[] cached = cache.get(cacheKey(text));
            if (cached != null) {
                vectors.put(text, cached);
            } else {
                missing.add(text);
            }
        }
        OpenAiCompatibleEmbeddingClient client = new OpenAiCompatibleEmbeddingClient(
                Duration.ofSeconds(5), Duration.ofSeconds(30), 2, Duration.ofSeconds(1));
        for (int n = 0; n < missing.size(); n += EmbeddingClient.MAX_BATCH) {
            List<String> batch = missing.subList(
                    n, Math.min(missing.size(), n + EmbeddingClient.MAX_BATCH));
            EmbeddingClient.EmbeddingCall call = client.embed(
                    connection(),
                    new EmbeddingClient.EmbeddingRequest(
                            MODEL.modelKey(), MODEL.dimension(),
                            batch.stream()
                                    .map(t -> t.length() > EMBED_TEXT_LIMIT
                                            ? t.substring(0, EMBED_TEXT_LIMIT) : t)
                                    .toList()),
                    handle(apiKey));
            assertTrue(call.delivered(), () -> "embedding delivery failed: " + call.failureCode());
            assertEquals(batch.size(), call.vectors().size());
            for (int i = 0; i < batch.size(); i++) {
                float[] vector = call.vectors().get(i);
                assertEquals(MODEL.dimension(), vector.length);
                vectors.put(batch.get(i), vector);
                cache.put(cacheKey(batch.get(i)), vector);
            }
            Thread.sleep(150);
        }
        json.writer().writeValue(CACHE.toFile(), cache);
        System.out.println("[gate] embedded " + missing.size() + " texts ("
                + (distinct.size() - missing.size()) + " served from cache)");
        return vectors;
    }

    private static String cacheKey(String text) {
        return sha(MODEL.modelKey() + "|" + MODEL.dimension() + "|" + text);
    }

    private static ModelConnection connection() {
        OrganizationId organizationId = OrganizationId.generate();
        PrincipalId actor = PrincipalId.generate();
        ModelRegion region = new ModelRegion("cn");
        ModelProviderDefinition provider = ModelProviderDefinition.publish(
                new ModelProviderKey("dashscope"),
                "DashScope",
                new ModelAdapterKey("openai-compatible"),
                new ModelEndpoint(ENDPOINT),
                Set.of(region),
                new ModelDataPolicy(
                        ModelDataRetentionMode.PROVIDER_MANAGED,
                        Optional.empty(),
                        ModelTrainingUsagePolicy.PROHIBITED),
                actor,
                NOW);
        return ModelConnection.open(
                provider,
                ModelConnectionId.generate(),
                ModelConnectionOwner.organization(organizationId),
                provider.defaultEndpoint(),
                region,
                new ModelCredentialBinding(
                        CredentialId.generate(),
                        ModelCredentialSubject.organization(organizationId),
                        new ModelCredentialVersion(0)),
                ModelBillingSubject.organization(organizationId),
                actor,
                NOW);
    }

    private static ProviderCredentialHandle handle(String apiKey) {
        ProviderCredentialHandle handle = mock(ProviderCredentialHandle.class);
        when(handle.useSecret(any())).thenAnswer(invocation -> {
            ProviderCredentialOperation<Object> operation = invocation.getArgument(0);
            byte[] bytes = apiKey.getBytes(StandardCharsets.UTF_8);
            try {
                return operation.apply(bytes);
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        });
        return handle;
    }

    // ------------------------------------------------------------------ ingest

    private void ingestKnowledge(
            List<JsonNode> entries, List<String> texts, Map<String, float[]> vectors,
            TeamId team, Map<TeamId, Map<String, UUID>> entryIdsByTeam,
            Map<TeamId, Map<KnowledgeEntryId, KnowledgeEntryVersion>> versionsByTeam) {
        seedTenant(organizationId, team, actor.id());
        Map<String, UUID> entryIds = new LinkedHashMap<>();
        Map<KnowledgeEntryId, KnowledgeEntryVersion> versions = new LinkedHashMap<>();
        Map<String, List<JsonNode>> byKey = new TreeMap<>();
        entries.forEach(e -> byKey
                .computeIfAbsent(e.get("key").asText(), k -> new ArrayList<>()).add(e));
        for (Map.Entry<String, List<JsonNode>> group : byKey.entrySet()) {
            List<JsonNode> rows = group.getValue();
            long effective = rows.stream()
                    .filter(r -> "PUBLISHED".equals(r.get("status").asText()))
                    .mapToLong(r -> r.get("version").asLong()).findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "dataset key without a PUBLISHED row: " + group.getKey()));
            UUID entryId = UUID.randomUUID();
            long latest = rows.stream().mapToLong(r -> r.get("version").asLong()).max().orElse(1);
            jdbc.update(
                    """
                    INSERT INTO crewscope.knowledge_entry (
                        id, organization_id, team_id, entry_key, status, effective_revision,
                        latest_revision, version, created_at, created_by_principal_id,
                        updated_at, updated_by_principal_id)
                    VALUES (?, ?, ?, ?, 'PUBLISHED', ?, ?, 0, now(), ?, now(), ?)
                    """,
                    entryId, organizationId.value(), team.value(), group.getKey(),
                    effective, latest, actor.id().value(), actor.id().value());
            entryIds.put(group.getKey(), entryId);
            for (JsonNode row : rows) {
                long revision = row.get("version").asLong();
                String title = row.get("title").asText();
                String content = row.get("content").asText();
                String text = title + "\n" + content;
                String hash = sha(text);
                jdbc.update(
                        """
                        INSERT INTO crewscope.knowledge_entry_version (
                            organization_id, team_id, entry_id, revision, previous_revision,
                            title, content, content_hash, created_at, created_by_principal_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), ?)
                        """,
                        organizationId.value(), team.value(), entryId, revision,
                        revision == 1 ? null : revision - 1,
                        title, content, hash, actor.id().value());
                // Retired versions keep their vector rows: the effective gate, not row
                // absence, must be what hides them.
                knowledgeVectors.replace(new KnowledgeEmbeddingVector(
                        organizationId, team, new KnowledgeEntryId(entryId),
                        new KnowledgeEntryRevision(revision), MODEL, hash, vectors.get(text)));
                versions.put(new KnowledgeEntryId(entryId), KnowledgeEntryVersion.create(
                        new KnowledgeEntryId(entryId),
                        new TeamScope(organizationId, team),
                        new KnowledgeEntryRevision(revision),
                        revision == 1
                                ? Optional.empty()
                                : Optional.of(new KnowledgeEntryRevision(revision - 1)),
                        title, content, actor.id(), NOW));
            }
        }
        entryIdsByTeam.put(team, entryIds);
        versionsByTeam.put(team, versions);
    }

    private RepositoryGenerationKey activateFreshGeneration(TeamId team) {
        NamedParameterJdbcTemplate named =
                new NamedParameterJdbcTemplate(jdbc.getDataSource());
        JdbcKnowledgeIndexJobRepositoryAdapter jobs =
                new JdbcKnowledgeIndexJobRepositoryAdapter(named);
        JdbcRepositoryGenerationStoreAdapter generations =
                new JdbcRepositoryGenerationStoreAdapter(
                        named, new DataSourceTransactionManager(jdbc.getDataSource()));
        RepositoryIndexKey key = new RepositoryIndexKey(
                organizationId, team, bindingId, commit(),
                ChunkingPolicy.defaults().policyHash(), MODEL);
        RepositoryGeneration opened = generations.open(key, jobs.create(
                KnowledgeIndexJob.repositoryBuild(
                        UUID.randomUUID(), organizationId, team, projectId, key,
                        actor.id(), NOW)).id());
        generations.activate(opened.generationKey(), GenerationRetentionPolicy.DEFAULT,
                actor.id());
        return opened.generationKey();
    }

    private KnowledgeRetrievalService service(
            List<String> queryTexts, Map<String, float[]> vectors,
            Map<KnowledgeEntryId, KnowledgeEntryVersion> versions) {
        return new KnowledgeRetrievalService(
                new PreEmbeddedExecutor(queryTexts, vectors),
                new JdbcGenerationCatalogAdapter(jdbc),
                knowledgeVectors,
                chunkVectors,
                new VersionReadingRepository(versions),
                new BothTeamsBindingRepository(),
                new BothTeamsGuard(),
                new BothTeamsGuard(),
                ChunkingPolicy.defaults(),
                true);
    }

    private KnowledgeRetrievalQuery knowledgeOnly(String query, int topK) {
        return new KnowledgeRetrievalQuery(
                query, Set.of(ManifestSourceType.KNOWLEDGE_ENTRY), null, topK);
    }

    private KnowledgeRetrievalQuery repositoryOnly(String query, int topK) {
        return new KnowledgeRetrievalQuery(
                query, Set.of(ManifestSourceType.REPOSITORY_CHUNK),
                new KnowledgeRetrievalQuery.RepositoryTarget(projectId, bindingId, commit()),
                topK);
    }

    private static SourceCommit commit() {
        return new SourceCommit("0123456789012345678901234567890123456789");
    }

    // ------------------------------------------------------------------ database

    private void freshDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                PGVECTOR.getJdbcUrl(), PGVECTOR.getUsername(), PGVECTOR.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        knowledgeVectors = new PgVectorKnowledgeEmbeddingStore(jdbc);
        chunkVectors = new PgVectorRepositoryChunkStore(jdbc);
        jdbc.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        jdbc.execute("DROP EXTENSION IF EXISTS vector CASCADE");
        Flyway.configure()
                .dataSource(PGVECTOR.getJdbcUrl(), PGVECTOR.getUsername(), PGVECTOR.getPassword())
                .locations("classpath:db/migration")
                .schemas("crewscope")
                .defaultSchema("crewscope")
                .load()
                .migrate();
        Flyway.configure()
                .dataSource(PGVECTOR.getJdbcUrl(), PGVECTOR.getUsername(), PGVECTOR.getPassword())
                .locations("classpath:db/migration-vector")
                .schemas("crewscope")
                .table("flyway_vector_history")
                .validateMigrationNaming(true)
                .createSchemas(false)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    private void seedTenant(OrganizationId org, TeamId team, PrincipalId owner) {
        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Gate Org', 'ACTIVE')",
                org.value());
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status) VALUES (?, ?, 'Gate Team', 'ACTIVE')",
                team.value(), org.value());
        jdbc.update(
                """
                INSERT INTO crewscope.principal (id, organization_id, principal_type, display_name, status)
                VALUES (?, ?, 'USER', 'Gate owner', 'ACTIVE')
                """,
                owner.value(), org.value());
    }

    // ------------------------------------------------------------------ helpers

    private List<JsonNode> dataset(Path root, String file) throws IOException {
        List<JsonNode> rows = new ArrayList<>();
        json.readTree(root.resolve("scripts/m10-s01/dataset").resolve(file).toFile())
                .forEach(rows::add);
        return rows;
    }

    private static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("scripts/m10-s01/dataset"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException(
                    "scripts/m10-s01/dataset not found above " + System.getProperty("user.dir"));
        }
        return dir;
    }

    private static byte[] run(Path cwd, String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile());
        Process process = builder.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        process.getInputStream().transferTo(out);
        process.getErrorStream().transferTo(new ByteArrayOutputStream());
        int code = process.waitFor();
        if (code != 0) {
            throw new IllegalStateException("command failed (" + code + "): "
                    + String.join(" ", command));
        }
        return out.toByteArray();
    }

    private static String sha(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest(text.getBytes(StandardCharsets.UTF_8))) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String shortPath(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static void report(String label, Map<String, Double> metrics, List<String> misses) {
        System.out.println("[gate] " + label + ": " + metrics.entrySet().stream()
                .map(e -> String.format("%s=%.4f", e.getKey(), e.getValue()))
                .collect(Collectors.joining(" ")));
        if (!misses.isEmpty()) {
            System.out.println("[gate] " + label + " misses (first 8): "
                    + String.join("; ", misses.subList(0, Math.min(8, misses.size()))));
        }
    }

    /** Serves the pre-embedded query vectors: the clock never pays for the API. */
    private static final class PreEmbeddedExecutor implements KnowledgeEmbeddingExecutor {
        private final Map<String, float[]> byText;

        PreEmbeddedExecutor(List<String> queryTexts, Map<String, float[]> vectors) {
            this.byText = new HashMap<>();
            queryTexts.forEach(text -> byText.put(text, vectors.get(text)));
        }

        @Override
        public EmbeddingModelRevision resolveModel(OrganizationId org, TeamId team) {
            return MODEL;
        }

        @Override
        public EmbeddingBatchResult embed(TeamEmbeddingCommand command) {
            float[] vector = byText.get(command.inputs().get(0));
            if (vector == null) {
                throw new IllegalStateException(
                        "query text was not pre-embedded — the gate pre-embeds every query");
            }
            return new EmbeddingBatchResult(
                    List.of(vector),
                    MODEL,
                    new ModelCatalogCoordinate(
                            ModelCatalogEntryId.generate(),
                            new ModelProviderKey("dashscope"),
                            new ModelId(MODEL.modelKey()),
                            new ModelCatalogRevision(MODEL.revision())),
                    ModelConnectionId.generate(),
                    1);
        }
    }

    /** The version read only; every other read fails the gate loudly. */
    private static final class VersionReadingRepository implements KnowledgeRepository {
        private final Map<KnowledgeEntryId, KnowledgeEntryVersion> versions;

        VersionReadingRepository(Map<KnowledgeEntryId, KnowledgeEntryVersion> versions) {
            this.versions = versions;
        }

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
                OrganizationId org, TeamId team, KnowledgeEntryId entryId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KnowledgeEntry> findByKey(
                OrganizationId org, TeamId team, KnowledgeEntryKey entryKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public KnowledgeEntryPage findByTeam(
                OrganizationId org, TeamId team,
                KnowledgeEntryFilter filter, KnowledgeEntryPageRequest pageRequest) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KnowledgeEntryVersion> findVersion(
                OrganizationId org, TeamId team, KnowledgeEntryId entryId,
                KnowledgeEntryRevision revision) {
            return Optional.ofNullable(versions.get(entryId))
                    .filter(version -> version.revision().equals(revision));
        }

        @Override
        public KnowledgeEntryVersionPage findVersionHistory(
                OrganizationId org, TeamId team, KnowledgeEntryId entryId,
                KnowledgeVersionPageRequest pageRequest) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KnowledgeEntryVersion> findEffectiveVersion(
                OrganizationId org, TeamId team, KnowledgeEntryId entryId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<KnowledgeEntryVersion> findEffectiveVersionsByTeam(
                OrganizationId org, TeamId team) {
            throw new UnsupportedOperationException();
        }
    }

    /** The fixed four coordinates, accepted for both ingested teams. */
    private final class BothTeamsBindingRepository implements RepositoryBindingRepository {
        @Override
        public RepositoryBinding create(RepositoryBinding binding) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RepositoryBinding update(RepositoryBinding binding) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RepositoryBinding> findById(
                OrganizationId org, TeamId team, WorkProjectId project, RepositoryBindingId id) {
            Set<TeamId> ingested = Set.of(teamA.team().id(), teamB.team().id());
            return org.equals(organizationId) && ingested.contains(team)
                    && project.equals(projectId) && id.equals(bindingId)
                    ? Optional.of(RepositoryBinding.reconstitute(
                            bindingId,
                            new RepositoryBindingScope(
                                    organizationId, team, WorkspaceId.generate(), projectId),
                            RepositoryKind.LOCAL_MANAGED,
                            new RepositoryKey("repo-" + bindingId.value()),
                            new RepositoryBranchName("main"),
                            RepositoryBindingStatus.ACTIVE,
                            0,
                            AuditMetadata.createdBy(actor.id(), NOW)))
                    : Optional.empty();
        }

        @Override
        public Optional<RepositoryBinding> findByKey(
                OrganizationId org, TeamId team, WorkProjectId project, RepositoryKey key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RepositoryBinding> findByWorkProject(
                OrganizationId org, TeamId team, WorkProjectId project) {
            throw new UnsupportedOperationException();
        }
    }

    /** Guards pass for both ingested teams' owners. */
    private final class BothTeamsGuard implements TeamRepository, TeamMembershipQuery {
        private final Map<TeamId, TeamInitialization> teams = Map.of(
                teamA.team().id(), teamA, teamB.team().id(), teamB);

        @Override
        public Team create(Team team) {
            return team;
        }

        @Override
        public Optional<Team> findById(OrganizationId org, TeamId id) {
            return Optional.ofNullable(teams.get(id))
                    .filter(initialization ->
                            initialization.team().organizationId().equals(org))
                    .map(TeamInitialization::team);
        }

        @Override
        public Optional<UninitializedTeam> findUninitializedById(OrganizationId org, TeamId id) {
            return Optional.empty();
        }

        @Override
        public List<TeamMember> findByTeam(OrganizationId org, TeamId team) {
            TeamInitialization initialization = teams.get(team);
            return initialization == null ? List.of() : List.of(initialization.ownerMember());
        }
    }
}
