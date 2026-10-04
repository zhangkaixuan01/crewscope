package io.crewscope.application.skill.output;

import io.crewscope.application.execution.StructuredOutputSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Frozen additionalProperties=false schema used for every skill-distiller@1 model call. */
public final class SkillDistillerStructuredOutputSpecs {

    public static final StructuredOutputSpec<DistilledSkillDraftV1> DISTILLED_SKILL =
            StructuredOutputSpec.strict(
                    DistilledSkillDraftV1.SCHEMA_VERSION, DistilledSkillDraftV1.class, schema());

    private SkillDistillerStructuredOutputSpecs() {}

    private static Map<String, Object> schema() {
        // Bounds mirror the JSON-text twin frozen in SkillDistillerTemplate.OUTPUT_SCHEMA
        // (description 1-200, body 1-65536), so the decode side and the model prompt side
        // cannot drift apart.
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("description", string(1, 200));
        root.put("body", string(1, 65_536));
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
}
