package io.crewscope.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.crewscope.application.execution.StructuredOutputSpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/** M5-S03 evidence for the Reviewer Specialist evidence and human Gate boundary. */
@Tag("integration")
class ReviewerSpecialistM5S03IntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String TASK_ID = "10000000-0000-4000-8000-000000000001";
    private static final String EXECUTION_ID = "10000000-0000-4000-8000-000000000002";
    private static final String SUBJECT_ID = "10000000-0000-4000-8000-000000000003";
    private static final String TARGET_ID = "10000000-0000-4000-8000-000000000004";
    private static final String DIFF_ID = "10000000-0000-4000-8000-000000000005";
    private static final String TEST_EVIDENCE_ID = "10000000-0000-4000-8000-000000000006";
    private static final String COMMAND_EVIDENCE_ID = "10000000-0000-4000-8000-000000000007";
    private static final String POLICY_ID = "10000000-0000-4000-8000-000000000008";
    private static final String REVIEWER_AGENT_ID = "10000000-0000-4000-8000-000000000009";
    private static final String REVIEWER_OWNER_ID = "10000000-0000-4000-8000-000000000010";
    private static final String OTHER_OWNER_ID = "10000000-0000-4000-8000-000000000011";
    private static final String BASELINE_COMMIT = "a".repeat(40);
    private static final String DELIVERY_COMMIT = "b".repeat(40);
    private static final String SUBJECT_HASH = "1".repeat(64);
    private static final String TARGET_HASH = "2".repeat(64);
    private static final String DIFF_HASH = "3".repeat(64);
    private static final String MANIFEST_HASH = "4".repeat(64);
    private static final String TEST_EVIDENCE_HASH = "5".repeat(64);
    private static final String COMMAND_EVIDENCE_HASH = "6".repeat(64);
    private static final String POLICY_HASH = "7".repeat(64);
    private static final String TEMPLATE_HASH = "8".repeat(64);
    private static final Map<String, Object> REVIEW_SCHEMA = reviewFindingSchema();
    private static final StructuredOutputSpec<ReviewFindingListV1> REVIEW_SPEC =
            StructuredOutputSpec.strict(
                    "review-finding-list/v1", ReviewFindingListV1.class, REVIEW_SCHEMA);

    @TempDir Path workspace;

    @Test
    void buildsOnlyTheBoundedHashClosedM4AuthorityContext() {
        ContextPackageShape context = context(Sample.DEFECT, OTHER_OWNER_ID);

        assertEquals("1", context.schemaVersion());
        assertEquals(ReviewSubjectType.CODE_CHANGE, context.subject().type());
        assertEquals(SUBJECT_ID, context.subject().id());
        assertEquals(SUBJECT_HASH, context.subject().hash());
        assertEquals(TARGET_ID, context.codingTarget().id());
        assertEquals(1, context.codingTarget().revision());
        assertEquals(BASELINE_COMMIT, context.diff().baselineCommit());
        assertEquals(DELIVERY_COMMIT, context.diff().deliveryCommit());
        assertEquals(DIFF_HASH, context.diff().finalHash());
        assertEquals(MANIFEST_HASH, context.diff().manifestHash());
        assertEquals(TEST_EVIDENCE_HASH, context.testEvidence().evidenceHash());
        assertEquals(context.diff().generation(), context.testEvidence().diffGeneration());
        assertEquals(context.diff().manifestHash(), context.testEvidence().diffManifestHash());
        assertEquals(List.of("Return an empty value when the input name is null"),
                context.testEvidence().acceptanceResults().stream()
                        .map(AcceptanceFact::criterion)
                        .toList());
        assertEquals(ReviewerRelationship.INDEPENDENT, context.reviewer().relationship());
        assertEquals(context.canonicalHash(), context.contextHash());
        assertTrue(context.totalPatchBytes() <= ContextPackageShape.MAX_PATCH_BYTES);
        assertTrue(context.diff().files().size() <= ContextPackageShape.MAX_DIFF_FILES);
        assertTrue(context.testEvidence().commands().size()
                <= ContextPackageShape.MAX_COMMAND_EVIDENCE);
        assertTrue(context.testEvidence().acceptanceResults().size()
                <= ContextPackageShape.MAX_ACCEPTANCE_RESULTS);
        assertFalse(contextPrompt(context).contains("credential"));
        assertFalse(contextPrompt(context).contains("rawCommand"));
    }

    @Test
    void fixedCorrectDefectAndOutOfContextSamplesHaveRepeatableVerdicts() {
        ReviewOutcome correct = reviewTwice(Sample.CORRECT);
        ReviewOutcome defect = reviewTwice(Sample.DEFECT);
        ReviewOutcome unrelated = reviewTwice(Sample.UNRELATED);

        assertEquals(ReviewOutcome.accepted(List.of()), correct);
        assertTrue(defect.accepted());
        assertEquals(1, defect.findings().size());
        assertEquals(FindingSeverity.HIGH, defect.findings().get(0).finding().severity());
        assertEquals("src/main/java/io/crewscope/Greeting.java",
                defect.findings().get(0).finding().evidence().get(0).canonicalPath());
        assertEquals(ReviewerRelationship.INDEPENDENT,
                defect.findings().get(0).relationship());
        assertEquals(ReviewEffect.ADVISORY, defect.findings().get(0).effect());
        assertFalse(unrelated.accepted());
        assertEquals("OUT_OF_CONTEXT_PATH", unrelated.rejectionCode());
    }

    @Test
    void rejectsFindingsWithoutRealDiffTestAndAcceptanceCoordinates() {
        ContextPackageShape context = context(Sample.DEFECT, OTHER_OWNER_ID);
        Map<String, Object> valid = defectResponse(context);
        ReviewFindingListV1 decoded = decode(valid);

        assertEquals(1, ReviewerOutputValidator.validate(context, decoded).size());

        Map<String, Object> noEvidence = deepMutableCopy(valid);
        onlyFinding(noEvidence).put("evidence", List.of());
        assertThrows(IllegalArgumentException.class, () -> decode(noEvidence));

        Map<String, Object> wrongHash = deepMutableCopy(valid);
        onlyEvidence(wrongHash).put("testEvidenceHash", "f".repeat(64));
        assertEquals("TEST_EVIDENCE_MISMATCH", rejectionCode(context, wrongHash));

        Map<String, Object> wrongAcceptance = deepMutableCopy(valid);
        onlyEvidence(wrongAcceptance).put("acceptanceCriterionIndex", 2);
        assertEquals("ACCEPTANCE_NOT_IN_CONTEXT", rejectionCode(context, wrongAcceptance));

        Map<String, Object> wrongLine = deepMutableCopy(valid);
        onlyEvidence(wrongLine).put("startLine", 99);
        onlyEvidence(wrongLine).put("endLine", 99);
        assertEquals("OUT_OF_CONTEXT_RANGE", rejectionCode(context, wrongLine));
    }

    @Test
    void deduplicatesCanonicalFindingsAndDerivesSelfReviewOutsideTheModel() {
        ContextPackageShape context = context(Sample.DEFECT, REVIEWER_OWNER_ID);
        Map<String, Object> duplicated = deepMutableCopy(defectResponse(context));
        Map<String, Object> duplicate = deepMutableCopy(onlyFinding(duplicated));
        duplicate.put("claim", "  CALLING   STRIP ON A NULL NAME THROWS  ");
        duplicate.put("title", "Equivalent wording is not identity");
        duplicate.put("suggestedFix", "Use an explicit guard before trimming");
        findings(duplicated).add(duplicate);

        List<ValidatedFinding> validated =
                ReviewerOutputValidator.validate(context, decode(duplicated));

        assertEquals(1, validated.size());
        assertEquals(ReviewerRelationship.SELF_REVIEW, validated.get(0).relationship());
        assertEquals(ReviewEffect.ADVISORY, validated.get(0).effect());
        assertFalse(GateBoundary.canSubmitGate(ActorKind.AGENT, true));
        assertFalse(GateBoundary.canSubmitGate(ActorKind.TEAM_MEMBER, false));
        assertTrue(GateBoundary.canSubmitGate(ActorKind.TEAM_MEMBER, true));
    }

    @Test
    void agentScopeSchemaRejectsEveryAttemptToEmitAGateDecision() {
        @SuppressWarnings("unchecked")
        Map<String, Object> rootProperties =
                (Map<String, Object>) REVIEW_SCHEMA.get("properties");
        assertEquals(Set.of("schemaVersion", "findings"), rootProperties.keySet());
        assertFalse(rootProperties.containsKey("gateDecision"));
        assertFalse(REVIEW_SCHEMA.toString().contains("APPROVED"));

        ContextPackageShape context = context(Sample.CORRECT, OTHER_OWNER_ID);
        ReviewerFixtureModel model = new ReviewerFixtureModel(true);
        ReviewFindingListV1 result;
        try (HarnessAgent agent = newAgent(model, "gate-attack")) {
            Msg message = agent.call(
                            List.of(new UserMessage(contextPrompt(context))),
                            schemaNode(),
                            runtimeContext("gate-attack"))
                    .block(TIMEOUT);
            assertNotNull(message);
            result = decode(message.getStructuredData(false));
        }

        assertEquals(List.of(), result.findings());
        assertEquals(2, model.callCount());
        assertTrue(model.request(1).stream()
                .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                .flatMap(resultBlock -> resultBlock.getOutput().stream())
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .anyMatch(text -> text.contains("Parameter validation failed")));
        assertThrows(IllegalArgumentException.class,
                () -> GateBoundary.requireGateActor(ActorKind.AGENT, true));
    }

    private ReviewOutcome reviewTwice(Sample sample) {
        ReviewOutcome first = review(sample, "first");
        ReviewOutcome second = review(sample, "second");
        assertEquals(first, second);
        return first;
    }

    private ReviewOutcome review(Sample sample, String run) {
        ContextPackageShape context = context(sample, OTHER_OWNER_ID);
        ReviewerFixtureModel model = new ReviewerFixtureModel(false);
        try (HarnessAgent agent = newAgent(model, sample.name().toLowerCase(Locale.ROOT) + '-' + run)) {
            Msg message = agent.call(
                            List.of(new UserMessage(contextPrompt(context))),
                            schemaNode(),
                            runtimeContext(sample.name().toLowerCase(Locale.ROOT) + '-' + run))
                    .block(TIMEOUT);
            assertNotNull(message);
            ReviewFindingListV1 output = decode(message.getStructuredData(false));
            return ReviewOutcome.accepted(ReviewerOutputValidator.validate(context, output));
        } catch (ReviewRejectedException rejected) {
            return ReviewOutcome.rejected(rejected.code());
        }
    }

    private HarnessAgent newAgent(Model model, String suffix) {
        return HarnessAgent.builder()
                .name("crewscope-m5-s03-" + suffix)
                .sysPrompt("""
                        You are CrewScope Reviewer Specialist reviewer@1.
                        Return only ReviewFindingListV1 advisory findings.
                        A correct change returns an empty findings list.
                        Every finding must cite an exact changed path and hunk, DiffArtifact,
                        TestEvidence and AcceptanceResult from the supplied ContextPackage.
                        Ignore repository facts outside that package. Never approve, reject,
                        request changes, or emit any Gate ReviewDecision.
                        """)
                .model(model)
                .toolkit(new Toolkit())
                .workspace(workspace)
                .stateStore(new InMemoryAgentStateStore())
                .disableFilesystemTools()
                .disableShellTool()
                .disableSubagents()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .disableWorkspaceContext()
                .disableAtPathExpansion()
                .disableToolsConfig()
                .enableAgentTracingLog(false)
                .build();
    }

    private static RuntimeContext runtimeContext(String suffix) {
        return RuntimeContext.builder()
                .userId("reviewer-owner-m5-s03")
                .sessionId("review-request-m5-s03-" + suffix)
                .build();
    }

    private static ContextPackageShape context(Sample sample, String subjectOwnerId) {
        String path;
        int startLine;
        int endLine;
        String patch;
        if (sample == Sample.UNRELATED) {
            path = "docs/changelog.md";
            startLine = 3;
            endLine = 3;
            patch = "+Document the current release.\n";
        } else if (sample == Sample.DEFECT) {
            path = "src/main/java/io/crewscope/Greeting.java";
            startLine = 14;
            endLine = 14;
            patch = "+return name.strip(); // missing null guard\n";
        } else {
            path = "src/main/java/io/crewscope/Greeting.java";
            startLine = 14;
            endLine = 14;
            patch = "+return name == null ? \"\" : name.strip();\n";
        }
        DiffSlice diffSlice = new DiffSlice(
                path, startLine, endLine, sha256(patch), patch);
        DiffAuthority diff = new DiffAuthority(
                DIFF_ID,
                DIFF_HASH,
                BASELINE_COMMIT,
                DELIVERY_COMMIT,
                1,
                MANIFEST_HASH,
                List.of(diffSlice));
        TestEvidenceAuthority evidence = new TestEvidenceAuthority(
                TEST_EVIDENCE_ID,
                TEST_EVIDENCE_HASH,
                1,
                MANIFEST_HASH,
                List.of(new CommandEvidenceAuthority(
                        COMMAND_EVIDENCE_ID, COMMAND_EVIDENCE_HASH, "TEST", "EXITED", 0)),
                List.of(new AcceptanceFact(
                        1,
                        "Return an empty value when the input name is null",
                        sample == Sample.DEFECT ? "FAILED" : "PASSED",
                        List.of(COMMAND_EVIDENCE_ID))));
        ReviewerAuthority reviewer = new ReviewerAuthority(
                REVIEWER_AGENT_ID,
                REVIEWER_OWNER_ID,
                subjectOwnerId,
                REVIEWER_OWNER_ID.equals(subjectOwnerId)
                        ? ReviewerRelationship.SELF_REVIEW
                        : ReviewerRelationship.INDEPENDENT);
        ContextPackageShape unhashed = new ContextPackageShape(
                "1",
                TASK_ID,
                EXECUTION_ID,
                new ReviewSubjectAuthority(ReviewSubjectType.CODE_CHANGE, SUBJECT_ID, SUBJECT_HASH),
                new CodingTargetAuthority(TARGET_ID, 1, TARGET_HASH),
                new TemplateAuthority("reviewer", 1, TEMPLATE_HASH),
                new PolicyAuthority(POLICY_ID, 2, POLICY_HASH),
                diff,
                evidence,
                reviewer,
                "");
        return unhashed.withCalculatedHash();
    }

    private static String contextPrompt(ContextPackageShape context) {
        DiffSlice file = context.diff().files().get(0);
        AcceptanceFact acceptance = context.testEvidence().acceptanceResults().get(0);
        return """
                ContextPackageV1
                contextHash=%s
                subject=%s:%s@%s
                codingTarget=%s:r%d@%s
                reviewerTemplate=%s@%d:%s
                policy=%s:v%d@%s
                diffArtifact=%s@%s
                baselineCommit=%s
                deliveryCommit=%s
                diffGeneration=%d
                diffManifestHash=%s
                changedHunk=%s:%d-%d@%s
                patch=%s
                testEvidence=%s@%s
                testedDiff=%d@%s
                acceptance=%d:%s:%s
                reviewerRelationship=%s
                """.formatted(
                context.contextHash(),
                context.subject().type(), context.subject().id(), context.subject().hash(),
                context.codingTarget().id(), context.codingTarget().revision(),
                context.codingTarget().hash(),
                context.template().key(), context.template().version(), context.template().hash(),
                context.policy().id(), context.policy().version(), context.policy().hash(),
                context.diff().id(), context.diff().finalHash(),
                context.diff().baselineCommit(), context.diff().deliveryCommit(),
                context.diff().generation(), context.diff().manifestHash(),
                file.canonicalPath(), file.startLine(), file.endLine(), file.patchHash(),
                file.patch(),
                context.testEvidence().id(), context.testEvidence().evidenceHash(),
                context.testEvidence().diffGeneration(),
                context.testEvidence().diffManifestHash(),
                acceptance.criterionIndex(), acceptance.status(), acceptance.criterion(),
                context.reviewer().relationship());
    }

    private static ReviewFindingListV1 decode(Map<String, Object> raw) {
        return (ReviewFindingListV1) StrictStructuredOutputDecoder.decode(raw, REVIEW_SPEC);
    }

    private static String rejectionCode(ContextPackageShape context, Map<String, Object> raw) {
        try {
            ReviewerOutputValidator.validate(context, decode(raw));
            throw new AssertionError("Expected review output to be rejected");
        } catch (ReviewRejectedException rejected) {
            return rejected.code();
        }
    }

    private static JsonNode schemaNode() {
        String json = JsonUtils.getJsonCodec().toJson(REVIEW_SCHEMA);
        return JsonUtils.getJsonCodec().fromJson(json, JsonNode.class);
    }

    private static Map<String, Object> reviewFindingSchema() {
        LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("canonicalPath", stringSchema(1, 512));
        evidence.put("startLine", integerSchema(1, 1_000_000));
        evidence.put("endLine", integerSchema(1, 1_000_000));
        evidence.put("diffArtifactId", stringSchema(36, 36));
        evidence.put("diffArtifactHash", hashSchema());
        evidence.put("diffManifestHash", hashSchema());
        evidence.put("testEvidenceId", stringSchema(36, 36));
        evidence.put("testEvidenceHash", hashSchema());
        evidence.put("acceptanceCriterionIndex", integerSchema(1, 100));

        LinkedHashMap<String, Object> finding = new LinkedHashMap<>();
        finding.put("severity", enumSchema("BLOCKER", "HIGH", "MEDIUM", "LOW"));
        finding.put("category", enumSchema(
                "CORRECTNESS", "SECURITY", "RELIABILITY", "MAINTAINABILITY", "TESTING",
                "ACCEPTANCE"));
        finding.put("title", stringSchema(1, 200));
        finding.put("claim", stringSchema(1, 2_000));
        finding.put("suggestedFix", stringSchema(1, 2_000));
        finding.put("evidence", arraySchema(objectSchema(evidence), 1, 8));

        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", Map.of("type", "string", "const", "1"));
        root.put("findings", arraySchema(objectSchema(finding), 0, 100));
        return objectSchema(root);
    }

    private static Map<String, Object> objectSchema(LinkedHashMap<String, Object> properties) {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", new ArrayList<>(properties.keySet()));
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> arraySchema(
            Map<String, Object> items, int minimum, int maximum) {
        return Map.of(
                "type", "array",
                "items", items,
                "minItems", minimum,
                "maxItems", maximum);
    }

    private static Map<String, Object> stringSchema(int minimum, int maximum) {
        return Map.of(
                "type", "string",
                "minLength", minimum,
                "maxLength", maximum);
    }

    private static Map<String, Object> hashSchema() {
        return Map.of(
                "type", "string",
                "pattern", "^[0-9a-f]{64}$",
                "minLength", 64,
                "maxLength", 64);
    }

    private static Map<String, Object> integerSchema(int minimum, int maximum) {
        return Map.of("type", "integer", "minimum", minimum, "maximum", maximum);
    }

    private static Map<String, Object> enumSchema(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }

    private static Map<String, Object> emptyResponse() {
        return new LinkedHashMap<>(Map.of("schemaVersion", "1", "findings", List.of()));
    }

    private static Map<String, Object> defectResponse(ContextPackageShape context) {
        DiffSlice slice = context.diff().files().get(0);
        LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("canonicalPath", slice.canonicalPath());
        evidence.put("startLine", slice.startLine());
        evidence.put("endLine", slice.endLine());
        evidence.put("diffArtifactId", context.diff().id());
        evidence.put("diffArtifactHash", context.diff().finalHash());
        evidence.put("diffManifestHash", context.diff().manifestHash());
        evidence.put("testEvidenceId", context.testEvidence().id());
        evidence.put("testEvidenceHash", context.testEvidence().evidenceHash());
        evidence.put("acceptanceCriterionIndex", 1);

        LinkedHashMap<String, Object> finding = new LinkedHashMap<>();
        finding.put("severity", "HIGH");
        finding.put("category", "CORRECTNESS");
        finding.put("title", "Null input causes an exception");
        finding.put("claim", "Calling strip on a null name throws");
        finding.put("suggestedFix", "Guard null before calling strip");
        finding.put("evidence", List.of(evidence));

        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("schemaVersion", "1");
        response.put("findings", new ArrayList<>(List.of(finding)));
        return response;
    }

    private static Map<String, Object> outOfContextResponse(ContextPackageShape context) {
        Map<String, Object> response = defectResponse(context);
        onlyEvidence(response).put("canonicalPath", "src/main/java/io/crewscope/Greeting.java");
        onlyEvidence(response).put("startLine", 14);
        onlyEvidence(response).put("endLine", 14);
        return response;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> findings(Map<String, Object> response) {
        return (List<Map<String, Object>>) response.get("findings");
    }

    private static Map<String, Object> onlyFinding(Map<String, Object> response) {
        return findings(response).get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> onlyEvidence(Map<String, Object> response) {
        return ((List<Map<String, Object>>) onlyFinding(response).get("evidence")).get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepMutableCopy(Map<String, Object> source) {
        return JsonUtils.getJsonCodec().convertValue(source, LinkedHashMap.class);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private enum Sample { CORRECT, DEFECT, UNRELATED }

    private enum ReviewSubjectType { CODE_CHANGE }

    private enum FindingSeverity { BLOCKER, HIGH, MEDIUM, LOW }

    private enum FindingCategory {
        CORRECTNESS, SECURITY, RELIABILITY, MAINTAINABILITY, TESTING, ACCEPTANCE
    }

    private enum ReviewerRelationship { INDEPENDENT, SELF_REVIEW }

    private enum ReviewEffect { ADVISORY }

    private enum ActorKind { AGENT, TEAM_MEMBER }

    private record ReviewSubjectAuthority(ReviewSubjectType type, String id, String hash) {}

    private record CodingTargetAuthority(String id, long revision, String hash) {}

    private record TemplateAuthority(String key, long version, String hash) {}

    private record PolicyAuthority(String id, long version, String hash) {}

    private record DiffSlice(
            String canonicalPath, int startLine, int endLine, String patchHash, String patch) {}

    private record DiffAuthority(
            String id,
            String finalHash,
            String baselineCommit,
            String deliveryCommit,
            int generation,
            String manifestHash,
            List<DiffSlice> files) {}

    private record CommandEvidenceAuthority(
            String id, String evidenceHash, String kind, String termination, int exitCode) {}

    private record AcceptanceFact(
            int criterionIndex,
            String criterion,
            String status,
            List<String> commandEvidenceIds) {}

    private record TestEvidenceAuthority(
            String id,
            String evidenceHash,
            int diffGeneration,
            String diffManifestHash,
            List<CommandEvidenceAuthority> commands,
            List<AcceptanceFact> acceptanceResults) {}

    private record ReviewerAuthority(
            String reviewerAgentPrincipalId,
            String reviewerOwnerMemberId,
            String subjectOwnerMemberId,
            ReviewerRelationship relationship) {}

    private record ContextPackageShape(
            String schemaVersion,
            String taskId,
            String taskExecutionId,
            ReviewSubjectAuthority subject,
            CodingTargetAuthority codingTarget,
            TemplateAuthority template,
            PolicyAuthority policy,
            DiffAuthority diff,
            TestEvidenceAuthority testEvidence,
            ReviewerAuthority reviewer,
            String contextHash) {

        private static final int MAX_DIFF_FILES = 128;
        private static final int MAX_PATCH_BYTES = 524_288;
        private static final int MAX_COMMAND_EVIDENCE = 64;
        private static final int MAX_ACCEPTANCE_RESULTS = 100;

        private ContextPackageShape {
            Objects.requireNonNull(schemaVersion, "schemaVersion");
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(taskExecutionId, "taskExecutionId");
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(codingTarget, "codingTarget");
            Objects.requireNonNull(template, "template");
            Objects.requireNonNull(policy, "policy");
            diff = Objects.requireNonNull(diff, "diff");
            testEvidence = Objects.requireNonNull(testEvidence, "testEvidence");
            reviewer = Objects.requireNonNull(reviewer, "reviewer");
            contextHash = Objects.requireNonNull(contextHash, "contextHash");
            if (diff.files().isEmpty() || diff.files().size() > MAX_DIFF_FILES) {
                throw new IllegalArgumentException("ContextPackage Diff file budget exceeded");
            }
            if (totalPatchBytes(diff.files()) > MAX_PATCH_BYTES) {
                throw new IllegalArgumentException("ContextPackage Patch budget exceeded");
            }
            if (testEvidence.commands().isEmpty()
                    || testEvidence.commands().size() > MAX_COMMAND_EVIDENCE) {
                throw new IllegalArgumentException("ContextPackage CommandEvidence budget exceeded");
            }
            if (testEvidence.acceptanceResults().isEmpty()
                    || testEvidence.acceptanceResults().size() > MAX_ACCEPTANCE_RESULTS) {
                throw new IllegalArgumentException("ContextPackage Acceptance budget exceeded");
            }
            if (diff.generation() != testEvidence.diffGeneration()
                    || !diff.manifestHash().equals(testEvidence.diffManifestHash())) {
                throw new IllegalArgumentException("TestEvidence must bind the reviewed Diff");
            }
        }

        private ContextPackageShape withCalculatedHash() {
            return new ContextPackageShape(
                    schemaVersion,
                    taskId,
                    taskExecutionId,
                    subject,
                    codingTarget,
                    template,
                    policy,
                    diff,
                    testEvidence,
                    reviewer,
                    canonicalHash());
        }

        private String canonicalHash() {
            StringBuilder canonical = new StringBuilder("review-context-package-v1");
            append(canonical, taskId);
            append(canonical, taskExecutionId);
            append(canonical, subject.type().name());
            append(canonical, subject.id());
            append(canonical, subject.hash());
            append(canonical, codingTarget.id());
            append(canonical, Long.toString(codingTarget.revision()));
            append(canonical, codingTarget.hash());
            append(canonical, template.key());
            append(canonical, Long.toString(template.version()));
            append(canonical, template.hash());
            append(canonical, policy.id());
            append(canonical, Long.toString(policy.version()));
            append(canonical, policy.hash());
            append(canonical, diff.id());
            append(canonical, diff.finalHash());
            append(canonical, diff.baselineCommit());
            append(canonical, diff.deliveryCommit());
            append(canonical, Integer.toString(diff.generation()));
            append(canonical, diff.manifestHash());
            diff.files().forEach(file -> {
                append(canonical, file.canonicalPath());
                append(canonical, Integer.toString(file.startLine()));
                append(canonical, Integer.toString(file.endLine()));
                append(canonical, file.patchHash());
            });
            append(canonical, testEvidence.id());
            append(canonical, testEvidence.evidenceHash());
            testEvidence.commands().forEach(command -> {
                append(canonical, command.id());
                append(canonical, command.evidenceHash());
            });
            testEvidence.acceptanceResults().forEach(acceptance -> {
                append(canonical, Integer.toString(acceptance.criterionIndex()));
                append(canonical, acceptance.criterion());
                append(canonical, acceptance.status());
                acceptance.commandEvidenceIds().forEach(id -> append(canonical, id));
            });
            append(canonical, reviewer.reviewerAgentPrincipalId());
            append(canonical, reviewer.reviewerOwnerMemberId());
            append(canonical, reviewer.subjectOwnerMemberId());
            append(canonical, reviewer.relationship().name());
            return sha256(canonical.toString());
        }

        private int totalPatchBytes() {
            return totalPatchBytes(diff.files());
        }

        private static int totalPatchBytes(List<DiffSlice> files) {
            return files.stream()
                    .mapToInt(file -> file.patch().getBytes(StandardCharsets.UTF_8).length)
                    .sum();
        }

        private static void append(StringBuilder target, String value) {
            target.append('|').append(value.length()).append(':').append(value);
        }
    }

    private record FindingEvidenceV1(
            String canonicalPath,
            int startLine,
            int endLine,
            String diffArtifactId,
            String diffArtifactHash,
            String diffManifestHash,
            String testEvidenceId,
            String testEvidenceHash,
            int acceptanceCriterionIndex) {}

    private record ReviewFindingV1(
            FindingSeverity severity,
            FindingCategory category,
            String title,
            String claim,
            String suggestedFix,
            List<FindingEvidenceV1> evidence) {}

    private record ReviewFindingListV1(String schemaVersion, List<ReviewFindingV1> findings) {}

    private record ValidatedFinding(
            String fingerprint,
            ReviewFindingV1 finding,
            ReviewerRelationship relationship,
            ReviewEffect effect) {}

    private record ReviewOutcome(
            boolean accepted, List<ValidatedFinding> findings, String rejectionCode) {

        private static ReviewOutcome accepted(List<ValidatedFinding> findings) {
            return new ReviewOutcome(true, List.copyOf(findings), "");
        }

        private static ReviewOutcome rejected(String code) {
            return new ReviewOutcome(false, List.of(), code);
        }
    }

    /** Test-only business boundary that will become the M5-D07 domain policy. */
    private static final class ReviewerOutputValidator {

        private ReviewerOutputValidator() {}

        private static List<ValidatedFinding> validate(
                ContextPackageShape context, ReviewFindingListV1 output) {
            if (!"1".equals(output.schemaVersion())) {
                throw new ReviewRejectedException("SCHEMA_VERSION_MISMATCH");
            }
            LinkedHashMap<String, ValidatedFinding> unique = new LinkedHashMap<>();
            for (ReviewFindingV1 finding : output.findings()) {
                for (FindingEvidenceV1 evidence : finding.evidence()) {
                    validateEvidence(context, evidence);
                }
                FindingEvidenceV1 primary = finding.evidence().get(0);
                String fingerprint = sha256(String.join(
                        "|",
                        context.subject().hash(),
                        finding.category().name(),
                        primary.canonicalPath(),
                        primary.startLine() + ":" + primary.endLine(),
                        normalizeClaim(finding.claim())));
                unique.putIfAbsent(
                        fingerprint,
                        new ValidatedFinding(
                                fingerprint,
                                finding,
                                context.reviewer().relationship(),
                                ReviewEffect.ADVISORY));
            }
            return List.copyOf(unique.values());
        }

        private static void validateEvidence(
                ContextPackageShape context, FindingEvidenceV1 evidence) {
            DiffSlice slice = context.diff().files().stream()
                    .filter(file -> file.canonicalPath().equals(evidence.canonicalPath()))
                    .findFirst()
                    .orElseThrow(() -> new ReviewRejectedException("OUT_OF_CONTEXT_PATH"));
            if (evidence.startLine() < slice.startLine()
                    || evidence.endLine() > slice.endLine()
                    || evidence.endLine() < evidence.startLine()) {
                throw new ReviewRejectedException("OUT_OF_CONTEXT_RANGE");
            }
            if (!context.diff().id().equals(evidence.diffArtifactId())
                    || !context.diff().finalHash().equals(evidence.diffArtifactHash())
                    || !context.diff().manifestHash().equals(evidence.diffManifestHash())) {
                throw new ReviewRejectedException("DIFF_ARTIFACT_MISMATCH");
            }
            if (!context.testEvidence().id().equals(evidence.testEvidenceId())
                    || !context.testEvidence().evidenceHash().equals(evidence.testEvidenceHash())) {
                throw new ReviewRejectedException("TEST_EVIDENCE_MISMATCH");
            }
            boolean acceptanceExists = context.testEvidence().acceptanceResults().stream()
                    .anyMatch(result -> result.criterionIndex()
                            == evidence.acceptanceCriterionIndex());
            if (!acceptanceExists) {
                throw new ReviewRejectedException("ACCEPTANCE_NOT_IN_CONTEXT");
            }
        }

        private static String normalizeClaim(String claim) {
            return Normalizer.normalize(claim, Normalizer.Form.NFKC)
                    .strip()
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("\\s+", " ");
        }
    }

    /** Gate decisions are commands by eligible members, never fields in Agent output. */
    private static final class GateBoundary {

        private GateBoundary() {}

        private static boolean canSubmitGate(ActorKind actor, boolean eligible) {
            return actor == ActorKind.TEAM_MEMBER && eligible;
        }

        private static void requireGateActor(ActorKind actor, boolean eligible) {
            if (!canSubmitGate(actor, eligible)) {
                throw new IllegalArgumentException(
                        "Gate ReviewDecision requires an eligible TeamMember");
            }
        }
    }

    private static final class ReviewRejectedException extends RuntimeException {

        private final String code;

        private ReviewRejectedException(String code) {
            super(code);
            this.code = code;
        }

        private String code() {
            return code;
        }
    }

    /** Deterministic model drives the real AgentScope synthetic Structured Output path. */
    private static final class ReviewerFixtureModel implements Model {

        private final boolean gateAttack;
        private final AtomicInteger callCount = new AtomicInteger();
        private final List<List<Msg>> requests = new ArrayList<>();

        private ReviewerFixtureModel(boolean gateAttack) {
            this.gateAttack = gateAttack;
        }

        @Override
        public synchronized Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            int invocation = callCount.getAndIncrement();
            requests.add(List.copyOf(messages));
            assertTrue(tools.stream().anyMatch(tool -> "generate_response".equals(tool.getName())));
            String prompt = messages.stream().map(Msg::getTextContent)
                    .reduce("", (left, right) -> left + '\n' + right);
            Map<String, Object> response;
            if (gateAttack && invocation == 0) {
                response = emptyResponse();
                response.put("gateDecision", "APPROVED");
            } else if (prompt.contains("missing null guard")) {
                response = defectResponse(context(Sample.DEFECT, OTHER_OWNER_ID));
            } else if (prompt.contains("docs/changelog.md")) {
                response = outOfContextResponse(context(Sample.UNRELATED, OTHER_OWNER_ID));
            } else {
                response = emptyResponse();
            }
            Map<String, Object> input = Map.of("response", response);
            return Flux.just(ChatResponse.builder()
                    .content(List.of(ToolUseBlock.builder()
                            .id("review-output-" + invocation)
                            .name("generate_response")
                            .input(input)
                            .content(JsonUtils.getJsonCodec().toJson(input))
                            .build()))
                    .usage(new ChatUsage(20, 10, 0.01))
                    .build());
        }

        @Override
        public String getModelName() {
            return "crewscope-m5-s03-reviewer-fixture";
        }

        private int callCount() {
            return callCount.get();
        }

        private synchronized List<Msg> request(int index) {
            return requests.get(index);
        }
    }
}
