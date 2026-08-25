package io.crewscope.agentscope.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.DeepSeekFormatter;
import io.agentscope.harness.agent.HarnessAgent;
import io.crewscope.agentscope.model.SafeAgentScopeGenerateOptionsMapper;
import io.crewscope.agentscope.template.AgentTemplateRuntimeDefinition;
import io.crewscope.agentscope.template.TemplateAgentBuildRequest;
import io.crewscope.agentscope.template.TemplateAgentSessionIdentity;
import io.crewscope.application.review.output.ReviewerStructuredOutputSpecs;
import io.crewscope.domain.agent.AgentConfigurationHash;
import io.crewscope.domain.agent.AgentConfigurationRevision;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentReasoningMode;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.AgentTemplateHash;
import io.crewscope.domain.agent.AgentTemplatePolicy;
import io.crewscope.domain.agent.AgentTemplateVersion;
import io.crewscope.domain.agent.SafeModelGenerateOptions;
import io.crewscope.domain.coding.AcceptanceResult;
import io.crewscope.domain.coding.AcceptanceStatus;
import io.crewscope.domain.coding.CodingTargetSnapshotId;
import io.crewscope.domain.coding.CodingTargetSnapshotReference;
import io.crewscope.domain.coding.CommandEvidenceId;
import io.crewscope.domain.coding.CommandEvidenceReference;
import io.crewscope.domain.coding.CommandKind;
import io.crewscope.domain.coding.CommandTermination;
import io.crewscope.domain.coding.DiffArtifactId;
import io.crewscope.domain.coding.DiffArtifactReference;
import io.crewscope.domain.coding.DiffGeneration;
import io.crewscope.domain.coding.DiffPath;
import io.crewscope.domain.coding.EvidenceSequence;
import io.crewscope.domain.coding.EvidenceSummary;
import io.crewscope.domain.coding.PatchArtifactReference;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.TestEvidenceId;
import io.crewscope.domain.conversation.AgentRuntimeSessionId;
import io.crewscope.domain.conversation.AgentScopeSessionKey;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.review.ContextPackage;
import io.crewscope.domain.review.ContextPackageId;
import io.crewscope.domain.review.FindingEvidence;
import io.crewscope.domain.review.ReviewCommandEvidenceReference;
import io.crewscope.domain.review.ReviewDiffHunk;
import io.crewscope.domain.review.ReviewDiffReference;
import io.crewscope.domain.review.ReviewFindingCandidate;
import io.crewscope.domain.review.ReviewRequest;
import io.crewscope.domain.review.ReviewRequestId;
import io.crewscope.domain.review.ReviewSubject;
import io.crewscope.domain.review.ReviewSubjectId;
import io.crewscope.domain.review.ReviewTestEvidenceReference;
import io.crewscope.domain.review.ReviewerExecutionReference;
import io.crewscope.domain.review.ReviewerRelationship;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/** M5-Q03 freezes and optionally executes the credentialed Reviewer quality baseline. */
class ReviewerQualityBenchmarkM5Q03Test {

    private static final String SYSTEM_PROMPT = """
            You are CrewScope Reviewer Specialist reviewer@1.
            Return only ReviewFindingListV1 advisory findings.
            A correct change returns an empty findings list.
            Every finding must cite an exact changed path and hunk, DiffArtifact,
            TestEvidence and AcceptanceResult from the supplied ContextPackage.
            Ignore repository facts outside that package. Never approve, reject,
            request changes, or emit any Gate ReviewDecision.
            """;
    private static final Duration MODEL_TIMEOUT = Duration.ofMinutes(5);
    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-25T06:00:00Z");
    private static final UtcTimestamp LATER = UtcTimestamp.parse("2026-08-25T06:01:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path workspace;

    @Test
    void freezesProtocolAndRunsTheCredentialedQualityGateWhenEnabled() throws Exception {
        Path assets = Path.of("..", "evaluation", "m5", "reviewer-q03")
                .toAbsolutePath()
                .normalize();
        Protocol protocol = JSON.readValue(assets.resolve("protocol.json").toFile(), Protocol.class);
        Suite suite = JSON.readValue(assets.resolve("suite.json").toFile(), Suite.class);
        validateAssets(protocol, suite);
        assertEquals("m5-q03-deepseek-20260825T060000Z",
                validateReportRunId("m5-q03-deepseek-20260825T060000Z"));
        assertThrows(IllegalArgumentException.class,
                () -> validateReportRunId("../../outside-results"));
        if (!Boolean.getBoolean("crewscope.m5.q03.real.enabled")) {
            return;
        }

        String apiKey = requiredEnvironment("OPENAI_API_KEY");
        String baseUrl = requiredEnvironment("AGENTSCOPE_OPENAI_BASE_URL");
        String modelName = requiredEnvironment("AGENTSCOPE_OPENAI_MODEL_NAME");
        String endpointPath = optionalEnvironment("AGENTSCOPE_OPENAI_ENDPOINT_PATH")
                .orElse("/v1/chat/completions");
        // AGENTSCOPE_MODEL_PROVIDER names the Starter transport adapter (normally "openai" for
        // OpenAI-compatible endpoints), so the benchmark keeps its business Provider identity
        // separate and defaults the credentialed M5 track to DeepSeek.
        String provider = optionalEnvironment("CREWSCOPE_M5_Q03_PROVIDER").orElse("deepseek");
        assertEquals(protocol.realModel().provider(), provider,
                "The frozen real-model track requires the protocol Provider");
        assertEquals(protocol.realModel().modelId(), modelName,
                "The configured model must match the frozen quality protocol");

        ArrayList<RunResult> runs = new ArrayList<>();
        for (BenchmarkCase sample : suite.cases()) {
            runs.add(runCase(sample, apiKey, baseUrl, endpointPath, modelName));
        }
        Aggregate aggregate = aggregate(protocol, suite, runs);
        writeReport(assets, protocol, suite, provider, modelName, runs, aggregate);
        assertTrue(aggregate.passed(), () -> "Reviewer quality gate failed: " + aggregate.metrics());
    }

    private RunResult runCase(
            BenchmarkCase sample,
            String apiKey,
            String baseUrl,
            String endpointPath,
            String modelName) {
        Fixture fixture = new Fixture(sample);
        UsageCapturingModel model = new UsageCapturingModel(realModel(
                apiKey, baseUrl, endpointPath, modelName));
        Path runWorkspace = createRunWorkspace(sample);
        ReviewerAgentProvider provider = ignored -> HarnessAgent.builder()
                .name("crewscope-m5-q03-" + sample.id())
                .agentId("crewscope-m5-q03-" + sample.id())
                .sysPrompt(SYSTEM_PROMPT)
                .model(model)
                .toolkit(new Toolkit())
                .maxIters(4)
                .maxRetries(2)
                .stateStore(new InMemoryAgentStateStore())
                .workspace(runWorkspace)
                .disableFilesystemTools()
                .disableShellTool()
                .disableSubagents()
                .disableDynamicSubagents()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .disableWorkspaceContext()
                .disableAtPathExpansion()
                .disableToolsConfig()
                .disableCompaction()
                .enableAgentTracingLog(false)
                .build();
        long started = System.nanoTime();
        try {
            ReviewerSpecialistRuntime runtime = new ReviewerSpecialistRuntime(
                    provider, mock(io.crewscope.application.review.ReviewFindingBatchRecorder.class),
                    MODEL_TIMEOUT);
            List<ReviewFindingCandidate> findings = runtime.analyze(fixture.request()).block();
            List<ReviewFindingCandidate> required = List.copyOf(findings == null ? List.of() : findings);
            long latency = Duration.ofNanos(System.nanoTime() - started).toMillis();
            return score(sample, fixture, required, model.usage(), model.calls(), latency);
        } catch (RuntimeException failure) {
            long latency = Duration.ofNanos(System.nanoTime() - started).toMillis();
            return RunResult.failed(
                    sample.id(), model.usage(), model.calls(), latency,
                    failure.getClass().getSimpleName());
        }
    }

    private Path createRunWorkspace(BenchmarkCase sample) {
        try {
            return Files.createDirectories(workspace.resolve(sample.id()));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create the isolated Reviewer workspace", exception);
        }
    }

    private static Model realModel(
            String apiKey, String baseUrl, String endpointPath, String modelName) {
        SafeModelGenerateOptions safe = new SafeModelGenerateOptions(
                Optional.of(BigDecimal.ZERO),
                Optional.of(BigDecimal.ONE),
                Optional.of(4_096L),
                AgentReasoningMode.DEFAULT,
                true,
                false,
                Optional.empty(),
                2);
        GenerateOptions options = SafeAgentScopeGenerateOptionsMapper.map(
                safe, MODEL_TIMEOUT, Duration.ofSeconds(1), Duration.ofSeconds(8));
        return OpenAIChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .endpointPath(endpointPath)
                .modelName(modelName)
                .stream(true)
                .generateOptions(options)
                .formatter(new DeepSeekFormatter())
                .nativeStructuredOutput(false)
                .nativeStructuredOutputWithTools(false)
                .build();
    }

    private static RunResult score(
            BenchmarkCase sample,
            Fixture fixture,
            List<ReviewFindingCandidate> findings,
            Usage usage,
            long modelCalls,
            long latencyMillis) {
        int evidenceCount = findings.stream().mapToInt(value -> value.evidence().size()).sum();
        int validEvidence = findings.stream()
                .flatMap(value -> value.evidence().stream())
                .mapToInt(value -> fixture.matches(value) ? 1 : 0)
                .sum();
        boolean categoryMatched = findings.stream().anyMatch(value ->
                sample.expectedCategories().contains(value.category().name()));
        boolean severityMatched = findings.stream().anyMatch(value ->
                sample.expectedSeverities().contains(value.severity().name()));
        boolean defectDetected = sample.defect()
                && findings.stream().anyMatch(fixture::hasValidEvidence);
        return new RunResult(
                sample.id(), 1, true, findings.size(), defectDetected,
                evidenceCount, validEvidence, categoryMatched, severityMatched, 0,
                modelCalls, usage.inputTokens(), usage.outputTokens(), usage.cachedTokens(),
                latencyMillis,
                findings.stream().map(value -> value.category().name()).toList(),
                findings.stream().map(value -> value.severity().name()).toList(),
                findings.stream()
                        .flatMap(value -> value.evidence().stream())
                        .map(value -> new EvidenceProjection(
                                value.location().path().value(),
                                value.location().startLine(),
                                value.location().endLine(),
                                fixture.matches(value)))
                        .toList(),
                "");
    }

    private static Aggregate aggregate(
            Protocol protocol, Suite suite, List<RunResult> runs) {
        Map<String, BenchmarkCase> samples = suite.cases().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(BenchmarkCase::id, value -> value));
        List<RunResult> defectRuns = runs.stream()
                .filter(run -> samples.get(run.caseId()).defect())
                .toList();
        List<RunResult> cleanRuns = runs.stream()
                .filter(run -> !samples.get(run.caseId()).defect())
                .toList();
        List<RunResult> findingRuns = runs.stream()
                .filter(run -> run.findingCount() > 0)
                .toList();
        long evidence = runs.stream().mapToLong(RunResult::evidenceCount).sum();
        long validEvidence = runs.stream().mapToLong(RunResult::validEvidenceCount).sum();
        Metrics metrics = new Metrics(
                ratio(runs.stream().filter(RunResult::structuredOutput).count(), runs.size()),
                ratio(defectRuns.stream().filter(RunResult::defectDetected).count(), defectRuns.size()),
                ratio(cleanRuns.stream().filter(run -> run.findingCount() == 0).count(), cleanRuns.size()),
                ratio(validEvidence, evidence),
                ratio(findingRuns.stream().filter(RunResult::categoryMatched).count(),
                        findingRuns.size()),
                ratio(findingRuns.stream().filter(RunResult::severityMatched).count(),
                        findingRuns.size()),
                runs.stream().mapToLong(RunResult::gateDecisionViolations).sum(),
                runs.stream().mapToLong(RunResult::inputTokens).sum(),
                runs.stream().mapToLong(RunResult::outputTokens).sum(),
                runs.stream().mapToLong(RunResult::cachedTokens).sum(),
                runs.stream().mapToLong(RunResult::modelCalls).sum(),
                runs.stream().mapToLong(RunResult::latencyMillis).sum());
        QualityGate gate = protocol.qualityGate();
        boolean passed = metrics.structuredOutputRate() >= gate.minimumStructuredOutputRate()
                && metrics.defectRecall() >= gate.minimumDefectRecall()
                && metrics.cleanSpecificity() >= gate.minimumCleanSpecificity()
                && metrics.evidenceValidity() >= gate.minimumEvidenceValidity()
                && metrics.categoryAccuracy() >= gate.minimumCategoryAccuracy()
                && metrics.severityAccuracy() >= gate.minimumSeverityAccuracy()
                && metrics.gateDecisionViolations() <= gate.maximumGateDecisionViolations();
        double cost = conservativeCost(metrics, protocol.pricing());
        return new Aggregate(metrics, cost, passed);
    }

    private static double conservativeCost(Metrics metrics, Pricing pricing) {
        long uncached = Math.max(0, metrics.inputTokens() - metrics.cachedTokens());
        return (metrics.cachedTokens() * pricing.inputCacheHitTokenPrice()
                        + uncached * pricing.inputCacheMissTokenPrice()
                        + metrics.outputTokens() * pricing.outputTokenPrice())
                / 1_000_000D;
    }

    private static double ratio(long value, long total) {
        return total == 0 ? 1D : (double) value / total;
    }

    private static void validateAssets(Protocol protocol, Suite suite) throws Exception {
        assertEquals("crewscope.reviewer-benchmark-protocol/v1", protocol.schemaVersion());
        assertEquals("m5-q03-reviewer-quality-baseline", protocol.protocolId());
        assertEquals("reviewer@1", protocol.template());
        assertEquals("system-prompt.md", protocol.systemPrompt());
        assertEquals("context-package-v1", protocol.promptPolicy());
        assertEquals(List.of(), protocol.skillKeys());
        assertEquals(List.of(), protocol.toolNames());
        assertEquals(1, protocol.modelRunsPerCase());
        assertEquals("deepseek", protocol.realModel().provider());
        assertEquals("deepseek-v4-flash", protocol.realModel().modelId());
        assertEquals("DeepSeek-V4-Flash-0731", protocol.realModel().modelRevision());
        assertEquals("crewscope.reviewer-quality-suite/v1", suite.schemaVersion());
        assertEquals(12, suite.cases().size());
        assertEquals(8, suite.cases().stream().filter(BenchmarkCase::defect).count());
        assertEquals(4, suite.cases().stream().filter(sample -> !sample.defect()).count());
        assertEquals(12, suite.cases().stream().map(BenchmarkCase::id).distinct().count());
        Path assets = Path.of("..", "evaluation", "m5", "reviewer-q03")
                .toAbsolutePath()
                .normalize();
        assertEquals(Files.readString(assets.resolve(protocol.systemPrompt())), SYSTEM_PROMPT);
        String schema = JSON.writeValueAsString(
                ReviewerStructuredOutputSpecs.REVIEW_FINDING_LIST.strictJsonSchema().orElseThrow());
        assertFalse(schema.contains("gateDecision"));
        assertFalse(schema.contains("reviewDecision"));
    }

    private static void writeReport(
            Path assets,
            Protocol protocol,
            Suite suite,
            String provider,
            String model,
            List<RunResult> runs,
            Aggregate aggregate) throws Exception {
        String runId = System.getProperty(
                "crewscope.m5.q03.run-id",
                "m5-q03-" + provider + '-' + model + '-' + Instant.now().toString()
                        .replace(':', '-'));
        Path repository = assets.getParent().getParent().getParent();
        Path results = repository.resolve("var/evaluation/m5-q03/results").normalize();
        Path output = results.resolve(validateReportRunId(runId)).normalize();
        if (!output.startsWith(results)) {
            throw new IllegalArgumentException("M5-Q03 report must stay inside the results directory");
        }
        if (Files.exists(output)) {
            throw new IllegalStateException("M5-Q03 report directory is append-only: " + runId);
        }
        Files.createDirectories(output);
        Report report = new Report(
                "crewscope.reviewer-quality-report/v1",
                runId,
                Instant.now().toString(),
                provider,
                model,
                protocol.realModel().modelRevision(),
                protocol.template(),
                sha256(SYSTEM_PROMPT),
                protocol.promptPolicy(),
                protocol.skillKeys(),
                protocol.toolNames(),
                sha256(Files.readString(assets.resolve("protocol.json"))),
                sha256(Files.readString(assets.resolve("suite.json"))),
                protocol.pricing(),
                runs,
                aggregate);
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("aggregate.json").toFile(), report);
    }

    private static String validateReportRunId(String runId) {
        if (runId == null || !runId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")) {
            throw new IllegalArgumentException(
                    "M5-Q03 run ID must be a safe path segment of at most 200 characters");
        }
        return runId;
    }

    private static String requiredEnvironment(String name) {
        return optionalEnvironment(name).orElseThrow(() ->
                new IllegalStateException(name + " is required for the credentialed M5-Q03 gate"));
    }

    private static Optional<String> optionalEnvironment(String name) {
        return Optional.ofNullable(System.getenv(name)).map(String::strip).filter(value -> !value.isEmpty());
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static final class UsageCapturingModel implements Model {
        private final Model delegate;
        private final AtomicReference<Usage> usage = new AtomicReference<>(Usage.none());
        private final AtomicLong calls = new AtomicLong();

        private UsageCapturingModel(Model delegate) {
            this.delegate = delegate;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.incrementAndGet();
            return delegate.stream(messages, tools, options).doOnNext(response -> {
                ChatUsage current = response.getUsage();
                if (current != null) {
                    usage.updateAndGet(previous -> previous.plus(new Usage(
                            current.getInputTokens(), current.getOutputTokens(),
                            current.getCachedTokens())));
                }
            });
        }

        @Override
        public String getModelName() {
            return delegate.getModelName();
        }

        private Usage usage() {
            return usage.get();
        }

        private long calls() {
            return calls.get();
        }
    }

    private static final class Fixture {
        private final BenchmarkCase sample;
        private final ContextPackage context;
        private final ReviewerSpecialistRequest request;

        private Fixture(BenchmarkCase sample) {
            this.sample = sample;
            WorkItemScope scope = new WorkItemScope(
                    OrganizationId.generate(), TeamId.generate(), WorkspaceId.generate(),
                    WorkProjectId.generate());
            TaskId taskId = TaskId.generate();
            TaskExecutionId executionId = TaskExecutionId.generate();
            Principal actor = principal(scope, PrincipalType.USER, "M5 Q03 owner", Optional.empty());
            Principal reviewerAgent = principal(
                    scope, PrincipalType.SPECIALIST_AGENT, "M5 Q03 reviewer",
                    Optional.of(actor.id()));
            CodingTargetSnapshotReference target = new CodingTargetSnapshotReference(
                    CodingTargetSnapshotId.generate(), 1, TaskFactHash.sha256(sample.id() + ":target"));
            DiffArtifactReference artifact = new DiffArtifactReference(
                    DiffArtifactId.generate(), TaskFactHash.sha256(sample.id() + ":diff"));
            RuntimeContentHash manifest = RuntimeContentHash.sha256(sample.id() + ":manifest");
            DiffGeneration generation = DiffGeneration.first();
            DiffPath path = new DiffPath(sample.canonicalPath());
            ReviewDiffReference diff = new ReviewDiffReference(
                    scope, taskId, executionId, 1, artifact, target,
                    new RepositoryCommitId("a".repeat(40)),
                    new RepositoryCommitId("b".repeat(40)), generation, manifest,
                    new PatchArtifactReference(
                            ArtifactId.generate(), sample.patch().getBytes(StandardCharsets.UTF_8).length,
                            RuntimeContentHash.sha256(sample.patch())),
                    List.of(path));
            CommandEvidenceReference command = new CommandEvidenceReference(
                    CommandEvidenceId.generate(), EvidenceSequence.first(),
                    TaskFactHash.sha256(sample.id() + ":command"), Optional.empty());
            AcceptanceStatus status = AcceptanceStatus.valueOf(sample.acceptanceStatus());
            ReviewTestEvidenceReference test = new ReviewTestEvidenceReference(
                    scope, taskId, executionId, 1, target, TestEvidenceId.generate(),
                    TaskFactHash.sha256(sample.id() + ":test"), generation, manifest,
                    List.of(new ReviewCommandEvidenceReference(
                            command, CommandKind.TEST, CommandTermination.EXITED,
                            Optional.of(status == AcceptanceStatus.PASSED ? 0 : 1),
                            new EvidenceSummary(status == AcceptanceStatus.PASSED
                                    ? "Acceptance passed" : "Acceptance failed"))),
                    List.of(new AcceptanceResult(
                            1, sample.acceptanceCriterion(), status, List.of(command),
                            new EvidenceSummary(status == AcceptanceStatus.PASSED
                                    ? "Acceptance passed" : "Acceptance failed"))));
            ReviewerExecutionReference reviewer = new ReviewerExecutionReference(
                    scope, taskId, executionId, AgentProfileId.generate(), 1,
                    reviewerAgent.id(), Optional.of(TeamMemberId.generate()),
                    Optional.of(TeamMemberId.generate()), ReviewerRelationship.INDEPENDENT,
                    AgentTemplateVersion.of("reviewer", 1), AgentTemplateHash.sha256(SYSTEM_PROMPT),
                    new AgentConfigurationRevision(1),
                    new AgentConfigurationHash(TaskFactHash.sha256("q03-config").value()),
                    PolicySnapshotId.generate(), 1, TaskFactHash.sha256("q03-policy"));
            ReviewSubject subject = ReviewSubject.codeChange(
                    ReviewSubjectId.generate(), scope, taskId, executionId, 1, diff, actor, NOW);
            context = ContextPackage.initial(
                    ContextPackageId.generate(), subject, diff, test,
                    List.of(ReviewDiffHunk.captured(
                            sample.canonicalPath(), sample.startLine(), sample.endLine(), sample.patch())),
                    reviewer, actor, NOW);
            ReviewRequest open = ReviewRequest.initial(
                    ReviewRequestId.generate(), context, actor, NOW);
            ReviewRequest started = open.start(context, open.version(), actor, LATER);
            request = new ReviewerSpecialistRequest(
                    buildRequest(scope, executionId, reviewer, reviewerAgent),
                    started, context, started.version(), reviewerAgent, LATER);
        }

        private ReviewerSpecialistRequest request() {
            return request;
        }

        private boolean matches(FindingEvidence evidence) {
            return evidence.location().path().value().equals(sample.canonicalPath())
                    && evidence.location().startLine() >= sample.startLine()
                    && evidence.location().endLine() <= sample.endLine()
                    && evidence.diffArtifact().equals(context.diff().artifact())
                    && evidence.diffManifestHash().equals(context.diff().manifestHash())
                    && evidence.testEvidenceId().equals(context.testEvidence().id())
                    && evidence.testEvidenceHash().equals(context.testEvidence().evidenceHash())
                    && evidence.acceptanceCriterionIndex() == 1;
        }

        private boolean hasValidEvidence(ReviewFindingCandidate candidate) {
            return !candidate.evidence().isEmpty() && candidate.evidence().stream().allMatch(this::matches);
        }

        private static TemplateAgentBuildRequest buildRequest(
                WorkItemScope scope,
                TaskExecutionId executionId,
                ReviewerExecutionReference reviewer,
                Principal reviewerAgent) {
            TemplateAgentBuildRequest build = mock(TemplateAgentBuildRequest.class);
            AgentTemplateRuntimeDefinition definition = mock(AgentTemplateRuntimeDefinition.class);
            AgentTemplateDefinition template = mock(AgentTemplateDefinition.class);
            AgentConfigurationVersion configuration = mock(AgentConfigurationVersion.class);
            AgentTemplatePolicy policy = mock(AgentTemplatePolicy.class);
            TemplateAgentSessionIdentity identity = mock(TemplateAgentSessionIdentity.class);
            String schema = JsonUtils.getJsonCodec().toJson(
                    ReviewerStructuredOutputSpecs.REVIEW_FINDING_LIST.strictJsonSchema().orElseThrow());
            when(build.definition()).thenReturn(definition);
            when(build.identity()).thenReturn(identity);
            when(definition.template()).thenReturn(template);
            when(definition.configuration()).thenReturn(configuration);
            when(definition.enabledToolNames()).thenReturn(Set.of());
            when(template.templateVersion()).thenReturn(AgentTemplateVersion.of("reviewer", 1));
            when(template.contentHash()).thenReturn(reviewer.templateHash());
            when(template.policy()).thenReturn(policy);
            when(configuration.revision()).thenReturn(reviewer.configurationRevision());
            when(configuration.configurationHash()).thenReturn(reviewer.configurationHash());
            when(policy.structuredOutputSchemaHash())
                    .thenReturn(Optional.of(AgentTemplateHash.sha256(schema)));
            when(identity.agentPrincipalId()).thenReturn(reviewerAgent.id());
            when(identity.agentProfileId()).thenReturn(reviewer.agentProfileId());
            when(identity.agentProfileVersion()).thenReturn(1L);
            when(identity.agentScopeKey()).thenReturn(AgentScopeSessionKey.forTaskExecution(
                    scope.organizationId(), reviewerAgent.id(), executionId,
                    AgentRuntimeSessionId.forTaskExecution(
                            executionId, Optional.empty(), reviewer.agentProfileId(), "reviewer")));
            return build;
        }

        private static Principal principal(
                WorkItemScope scope,
                PrincipalType type,
                String name,
                Optional<PrincipalId> owner) {
            return Principal.create(
                    PrincipalId.generate(), PrincipalScope.team(scope.organizationId(), scope.teamId()),
                    type, owner, name, Optional.empty(), PrincipalVisibility.TEAM, NOW);
        }
    }

    private record Protocol(
            String schemaVersion,
            String protocolId,
            String protocolVersion,
            String suite,
            String template,
            String systemPrompt,
            String promptPolicy,
            List<String> skillKeys,
            List<String> toolNames,
            int modelRunsPerCase,
            RealModel realModel,
            QualityGate qualityGate,
            Pricing pricing,
            Map<String, Object> archive) {}

    private record RealModel(String provider, String modelId, String modelRevision) {}

    private record QualityGate(
            double minimumStructuredOutputRate,
            double minimumDefectRecall,
            double minimumCleanSpecificity,
            double minimumEvidenceValidity,
            double minimumCategoryAccuracy,
            double minimumSeverityAccuracy,
            long maximumGateDecisionViolations) {}

    private record Pricing(
            String currency,
            String unit,
            double inputCacheHitTokenPrice,
            double inputCacheMissTokenPrice,
            double outputTokenPrice,
            String mode,
            String source,
            String effectiveAt) {}

    private record Suite(
            String schemaVersion,
            String suiteId,
            String suiteVersion,
            List<BenchmarkCase> cases) {}

    private record BenchmarkCase(
            String id,
            boolean defect,
            String canonicalPath,
            int startLine,
            int endLine,
            String patch,
            String acceptanceCriterion,
            String acceptanceStatus,
            List<String> expectedCategories,
            List<String> expectedSeverities) {}

    private record Usage(long inputTokens, long outputTokens, long cachedTokens) {
        private static Usage none() {
            return new Usage(0, 0, 0);
        }

        private Usage plus(Usage other) {
            return new Usage(
                    inputTokens + other.inputTokens,
                    outputTokens + other.outputTokens,
                    cachedTokens + other.cachedTokens);
        }
    }

    private record EvidenceProjection(
            String canonicalPath, int startLine, int endLine, boolean valid) {}

    private record RunResult(
            String caseId,
            int repetition,
            boolean structuredOutput,
            int findingCount,
            boolean defectDetected,
            int evidenceCount,
            int validEvidenceCount,
            boolean categoryMatched,
            boolean severityMatched,
            long gateDecisionViolations,
            long modelCalls,
            long inputTokens,
            long outputTokens,
            long cachedTokens,
            long latencyMillis,
            List<String> findingCategories,
            List<String> findingSeverities,
            List<EvidenceProjection> evidence,
            String errorCode) {

        private static RunResult failed(
                String caseId, Usage usage, long calls, long latency, String errorCode) {
            return new RunResult(
                    caseId, 1, false, 0, false, 0, 0, false, false, 0,
                    calls, usage.inputTokens(), usage.outputTokens(), usage.cachedTokens(),
                    latency, List.of(), List.of(), List.of(), errorCode);
        }
    }

    private record Metrics(
            double structuredOutputRate,
            double defectRecall,
            double cleanSpecificity,
            double evidenceValidity,
            double categoryAccuracy,
            double severityAccuracy,
            long gateDecisionViolations,
            long inputTokens,
            long outputTokens,
            long cachedTokens,
            long modelCalls,
            long totalLatencyMillis) {}

    private record Aggregate(Metrics metrics, double conservativeCostUsd, boolean passed) {}

    private record Report(
            String schemaVersion,
            String runId,
            String completedAt,
            String provider,
            String modelId,
            String modelRevision,
            String template,
            String systemPromptSha256,
            String promptPolicy,
            List<String> skillKeys,
            List<String> toolNames,
            String protocolSha256,
            String suiteSha256,
            Pricing pricing,
            List<RunResult> runs,
            Aggregate aggregate) {}
}
