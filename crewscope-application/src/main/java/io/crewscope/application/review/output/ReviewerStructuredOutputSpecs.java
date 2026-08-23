package io.crewscope.application.review.output;

import io.crewscope.application.execution.StructuredOutputSpec;
import io.crewscope.domain.review.FindingCategory;
import io.crewscope.domain.review.FindingSeverity;
import io.crewscope.domain.review.ReviewFindingCandidate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Frozen additionalProperties=false schema used for every reviewer@1 model call. */
public final class ReviewerStructuredOutputSpecs {

    private static final String SHA_256 = "^[0-9a-f]{64}$";
    private static final String UUID =
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$";

    public static final StructuredOutputSpec<ReviewFindingListV1> REVIEW_FINDING_LIST =
            StructuredOutputSpec.strict(
                    "review-finding-list/v1", ReviewFindingListV1.class, schema());

    private ReviewerStructuredOutputSpecs() {}

    private static Map<String, Object> schema() {
        LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("canonicalPath", string(1, 512));
        evidence.put("startLine", integer(1, 1_000_000));
        evidence.put("endLine", integer(1, 1_000_000));
        evidence.put("diffArtifactId", pattern(UUID));
        evidence.put("diffArtifactHash", pattern(SHA_256));
        evidence.put("diffManifestHash", pattern(SHA_256));
        evidence.put("testEvidenceId", pattern(UUID));
        evidence.put("testEvidenceHash", pattern(SHA_256));
        evidence.put("acceptanceCriterionIndex", integer(1, 100));

        LinkedHashMap<String, Object> finding = new LinkedHashMap<>();
        finding.put("severity", enumeration(Arrays.stream(FindingSeverity.values())
                .map(Enum::name).toList()));
        finding.put("category", enumeration(Arrays.stream(FindingCategory.values())
                .map(Enum::name).toList()));
        finding.put("title", string(1, ReviewFindingCandidate.MAX_TITLE_LENGTH));
        finding.put("claim", string(1, ReviewFindingCandidate.MAX_CLAIM_LENGTH));
        finding.put("suggestedFix", string(1, ReviewFindingCandidate.MAX_SUGGESTED_FIX_LENGTH));
        finding.put("evidence", array(object(evidence), 1, ReviewFindingCandidate.MAX_EVIDENCE));

        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", constant(ReviewFindingListV1.SCHEMA_VERSION));
        root.put("findings", array(object(finding), 0, ReviewFindingListV1.MAX_FINDINGS));
        return object(root);
    }

    private static Map<String, Object> object(LinkedHashMap<String, Object> properties) {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("properties", properties);
        schema.put("required", List.copyOf(properties.keySet()));
        return schema;
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

    private static Map<String, Object> string(int minimum, int maximum) {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("minLength", minimum);
        schema.put("maxLength", maximum);
        return schema;
    }

    private static Map<String, Object> pattern(String pattern) {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("pattern", pattern);
        return schema;
    }

    private static Map<String, Object> constant(String value) {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("const", value);
        return schema;
    }

    private static Map<String, Object> enumeration(List<String> values) {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("enum", values);
        return schema;
    }

    private static Map<String, Object> integer(int minimum, int maximum) {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "integer");
        schema.put("minimum", minimum);
        schema.put("maximum", maximum);
        return schema;
    }
}
