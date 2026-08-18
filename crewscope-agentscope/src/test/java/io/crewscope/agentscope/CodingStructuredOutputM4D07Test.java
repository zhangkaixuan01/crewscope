package io.crewscope.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.util.JsonSchemaUtils;
import io.crewscope.application.coding.output.CodeChangeResultV1;
import io.crewscope.application.coding.output.CodingStructuredOutputSpecs;
import io.crewscope.application.coding.output.RepositoryAnalysisV1;
import io.crewscope.application.execution.StructuredOutputSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CodingStructuredOutputM4D07Test {

    @Test
    void agentScopeSeesEveryAnnotatedRecordComponentAsRequired() {
        Map<String, Object> generated =
                JsonSchemaUtils.generateSchemaFromClass(RepositoryAnalysisV1.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) generated.get("properties");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) generated.get("required");

        assertEquals(properties.keySet(), new java.util.LinkedHashSet<>(required));
        assertTrue(required.contains("schemaVersion"));
    }

    @Test
    void agentScopeJacksonConversionRejectsUnknownFields() {
        LinkedHashMap<String, Object> output = new LinkedHashMap<>();
        output.put("schemaVersion", "1");
        output.put("executionWorkspaceId", "00000000-0000-4000-8000-000000000001");
        output.put("workspaceFingerprint", "a".repeat(64));
        output.put("codingTargetSnapshotId", "00000000-0000-4000-8000-000000000002");
        output.put("codingTargetRevision", 1);
        output.put("codingTargetHash", "b".repeat(64));
        output.put("repositoryAnalysisHash", "c".repeat(64));
        output.put("diffArtifactId", "00000000-0000-4000-8000-000000000003");
        output.put("diffArtifactHash", "d".repeat(64));
        output.put("testEvidenceId", "00000000-0000-4000-8000-000000000004");
        output.put("testEvidenceHash", "e".repeat(64));
        output.put("changeSummary", List.of("Changed one file"));
        output.put("limitations", List.of());
        output.put("risks", List.of());

        CodeChangeResultV1 converted = (CodeChangeResultV1) StrictStructuredOutputDecoder.decode(
                output, CodingStructuredOutputSpecs.CODE_CHANGE_RESULT);
        assertEquals("1", converted.schemaVersion());
        output.remove("risks");
        assertThrows(RuntimeException.class,
                () -> StrictStructuredOutputDecoder.decode(
                        output, CodingStructuredOutputSpecs.CODE_CHANGE_RESULT));
        output.put("risks", List.of());
        output.put("succeeded", true);
        assertThrows(RuntimeException.class,
                () -> StrictStructuredOutputDecoder.decode(
                        output, CodingStructuredOutputSpecs.CODE_CHANGE_RESULT));
    }

    @Test
    void strictSpecRejectsOpenOrPartiallyRequiredObjectSchemas() {
        Map<String, Object> property = Map.of("type", "string");
        Map<String, Object> open = Map.of(
                "type", "object",
                "properties", Map.of("value", property),
                "required", List.of("value"),
                "additionalProperties", true);
        Map<String, Object> partial = Map.of(
                "type", "object",
                "properties", Map.of("value", property),
                "required", List.of(),
                "additionalProperties", false);
        Map<String, Object> unsupported = Map.of(
                "type", "object",
                "properties", Map.of("value", Map.of("type", "number")),
                "required", List.of("value"),
                "additionalProperties", false);

        assertThrows(IllegalArgumentException.class,
                () -> StructuredOutputSpec.strict("open-output/v1", StringHolder.class, open));
        assertThrows(IllegalArgumentException.class,
                () -> StructuredOutputSpec.strict("partial-output/v1", StringHolder.class, partial));
        assertThrows(IllegalArgumentException.class,
                () -> StructuredOutputSpec.strict(
                        "unsupported-output/v1", StringHolder.class, unsupported));
        assertTrue(CodingStructuredOutputSpecs.CODE_CHANGE_RESULT.strictJsonSchema().isPresent());
    }

    private record StringHolder(String value) {}
}
