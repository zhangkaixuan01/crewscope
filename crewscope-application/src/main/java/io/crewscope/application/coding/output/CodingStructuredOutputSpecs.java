package io.crewscope.application.coding.output;

import static io.crewscope.application.coding.output.CodingOutputPatterns.CANONICAL_UUID;
import static io.crewscope.application.coding.output.CodingOutputPatterns.REPOSITORY_PATH;
import static io.crewscope.application.coding.output.CodingOutputPatterns.SHA_256;

import io.crewscope.application.execution.StructuredOutputSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable strict schemas consumed directly by AgentScope's JsonNode structured-output overload. */
public final class CodingStructuredOutputSpecs {

    public static final StructuredOutputSpec<RepositoryAnalysisV1> REPOSITORY_ANALYSIS =
            StructuredOutputSpec.strict("repository-analysis/v1", RepositoryAnalysisV1.class,
                    repositoryAnalysisSchema());
    public static final StructuredOutputSpec<DiffManifestV1> DIFF_MANIFEST =
            StructuredOutputSpec.strict("diff-manifest/v1", DiffManifestV1.class,
                    diffManifestSchema());
    public static final StructuredOutputSpec<TestEvidenceV1> TEST_EVIDENCE =
            StructuredOutputSpec.strict("test-evidence/v1", TestEvidenceV1.class,
                    testEvidenceSchema());
    public static final StructuredOutputSpec<CodeChangeResultV1> CODE_CHANGE_RESULT =
            StructuredOutputSpec.strict("code-change-result/v1", CodeChangeResultV1.class,
                    codeChangeResultSchema());

    private CodingStructuredOutputSpecs() {}

    private static Map<String, Object> repositoryAnalysisSchema() {
        return object(properties(
                "schemaVersion", constant("1"),
                "codingTargetSnapshotId", string(CANONICAL_UUID, null),
                "codingTargetRevision", integer(1L, null),
                "codingTargetHash", string(SHA_256, null),
                "modules", array(string(null, 200), 0, 100),
                "buildEntries", array(string(REPOSITORY_PATH, 1_024), 0, 50),
                "relevantPaths", array(string(REPOSITORY_PATH, 1_024), 1, 500),
                "risks", array(string(null, 1_000), 0, 50),
                "plan", array(string(null, 1_000), 1, 100)));
    }

    private static Map<String, Object> diffManifestSchema() {
        Map<String, Object> file = object(properties(
                "path", string(REPOSITORY_PATH, 1_024),
                "oldPath", optionalPathString(),
                "kind", enumeration("ADDED", "MODIFIED", "DELETED", "RENAMED", "COPIED", "TYPE_CHANGED"),
                "additions", integer(0L, null),
                "deletions", integer(0L, null),
                "binary", bool(),
                "patchTruncated", bool(),
                "patchSha256", string(SHA_256, null)));
        return object(properties(
                "schemaVersion", constant("1"),
                "executionWorkspaceId", string(CANONICAL_UUID, null),
                "workspaceFingerprint", string(SHA_256, null),
                "codingTargetSnapshotId", string(CANONICAL_UUID, null),
                "codingTargetRevision", integer(1L, null),
                "codingTargetHash", string(SHA_256, null),
                "diffGeneration", integer(1L, null),
                "manifestHash", string(SHA_256, null),
                "fileCount", integer(0L, 10_000L),
                "additions", integer(0L, null),
                "deletions", integer(0L, null),
                "files", array(file, 0, 10_000)));
    }

    private static Map<String, Object> testEvidenceSchema() {
        Map<String, Object> command = commandReferenceSchema();
        Map<String, Object> statistics = object(properties(
                "total", integer(0L, null),
                "passed", integer(0L, null),
                "failed", integer(0L, null),
                "errors", integer(0L, null),
                "skipped", integer(0L, null)));
        Map<String, Object> acceptance = object(properties(
                "criterionIndex", integer(1L, null),
                "criterion", string(null, 2_000),
                "status", enumeration("PASSED", "FAILED", "NOT_EVALUATED"),
                "evidence", array(command, 0, 100),
                "summaryHash", string(SHA_256, null)));
        return object(properties(
                "schemaVersion", constant("1"),
                "testEvidenceId", string(CANONICAL_UUID, null),
                "evidenceHash", string(SHA_256, null),
                "executionWorkspaceId", string(CANONICAL_UUID, null),
                "workspaceFingerprint", string(SHA_256, null),
                "codingTargetSnapshotId", string(CANONICAL_UUID, null),
                "codingTargetRevision", integer(1L, null),
                "codingTargetHash", string(SHA_256, null),
                "diffGeneration", integer(1L, null),
                "diffManifestHash", string(SHA_256, null),
                "workspacePolicyId", string(CANONICAL_UUID, null),
                "workspacePolicyHash", string(SHA_256, null),
                "evidenceSequence", integer(1L, null),
                "commands", array(command, 1, 100),
                "statistics", statistics,
                "acceptanceResults", array(acceptance, 1, 100),
                "summaryHash", string(SHA_256, null)));
    }

    private static Map<String, Object> codeChangeResultSchema() {
        return object(properties(
                "schemaVersion", constant("1"),
                "executionWorkspaceId", string(CANONICAL_UUID, null),
                "workspaceFingerprint", string(SHA_256, null),
                "codingTargetSnapshotId", string(CANONICAL_UUID, null),
                "codingTargetRevision", integer(1L, null),
                "codingTargetHash", string(SHA_256, null),
                "repositoryAnalysisHash", string(SHA_256, null),
                "diffArtifactId", string(CANONICAL_UUID, null),
                "diffArtifactHash", string(SHA_256, null),
                "testEvidenceId", string(CANONICAL_UUID, null),
                "testEvidenceHash", string(SHA_256, null),
                "changeSummary", array(string(null, 1_000), 1, 100),
                "limitations", array(string(null, 1_000), 0, 50),
                "risks", array(string(null, 1_000), 0, 50)));
    }

    private static Map<String, Object> commandReferenceSchema() {
        return object(properties(
                "commandEvidenceId", string(CANONICAL_UUID, null),
                "sequence", integer(1L, null),
                "evidenceHash", string(SHA_256, null)));
    }

    private static LinkedHashMap<String, Object> properties(Object... values) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }

    private static Map<String, Object> object(LinkedHashMap<String, Object> properties) {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", new ArrayList<>(properties.keySet()));
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> string(String pattern, Integer maxLength) {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("minLength", 1);
        if (maxLength != null) {
            schema.put("maxLength", maxLength);
        }
        if (pattern != null) {
            schema.put("pattern", pattern);
        }
        return schema;
    }

    private static Map<String, Object> optionalPathString() {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("minLength", 0);
        schema.put("maxLength", 1_024);
        schema.put("pattern", "|" + REPOSITORY_PATH);
        return schema;
    }

    private static Map<String, Object> constant(String value) {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("const", value);
        return schema;
    }

    private static Map<String, Object> enumeration(String... values) {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("enum", List.of(values));
        return schema;
    }

    private static Map<String, Object> integer(Long minimum, Long maximum) {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "integer");
        if (minimum != null) {
            schema.put("minimum", minimum);
        }
        if (maximum != null) {
            schema.put("maximum", maximum);
        }
        return schema;
    }

    private static Map<String, Object> bool() {
        return Map.of("type", "boolean");
    }

    private static Map<String, Object> array(
            Map<String, Object> items, int minimum, int maximum) {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", items);
        schema.put("minItems", minimum);
        schema.put("maxItems", maximum);
        return schema;
    }
}
