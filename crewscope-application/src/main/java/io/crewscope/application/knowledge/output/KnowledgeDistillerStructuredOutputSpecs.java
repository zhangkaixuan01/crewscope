package io.crewscope.application.knowledge.output;

import io.crewscope.application.execution.StructuredOutputSpec;
import io.crewscope.domain.knowledge.KnowledgeCategory;
import io.crewscope.domain.knowledge.KnowledgeEntryVersion;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Frozen additionalProperties=false schema used for every knowledge-distiller@1 model call. */
public final class KnowledgeDistillerStructuredOutputSpecs {

    public static final StructuredOutputSpec<DistilledDraftV1> DISTILLED_DRAFT =
            StructuredOutputSpec.strict(
                    DistilledDraftV1.SCHEMA_VERSION, DistilledDraftV1.class, schema());

    private KnowledgeDistillerStructuredOutputSpecs() {}

    private static Map<String, Object> schema() {
        // Bounds come from the domain version contract so the decode side and the model
        // prompt side cannot drift apart; KnowledgeDistillerSchemaContractTest pins the
        // JSON-text twin in KnowledgeDistillerTemplate to this map.
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("title", string(1, KnowledgeEntryVersion.MAX_TITLE_LENGTH));
        root.put("content", string(1, KnowledgeEntryVersion.MAX_CONTENT_LENGTH));
        root.put("suggestedCategory", enumeration(Arrays.stream(KnowledgeCategory.values())
                .map(Enum::name).toList()));
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

    private static Map<String, Object> string(int minimum, int maximum) {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("minLength", minimum);
        schema.put("maxLength", maximum);
        return schema;
    }

    private static Map<String, Object> enumeration(List<String> values) {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("enum", values);
        return schema;
    }
}
