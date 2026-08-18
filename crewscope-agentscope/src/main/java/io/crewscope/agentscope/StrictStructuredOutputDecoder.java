package io.crewscope.agentscope;

import io.agentscope.core.util.JsonUtils;
import io.crewscope.application.execution.StructuredOutputSpec;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates raw model metadata before AgentScope's permissive Jackson conversion. */
final class StrictStructuredOutputDecoder {

    private StrictStructuredOutputDecoder() {}

    static Object decode(Map<String, Object> raw, StructuredOutputSpec<?> spec) {
        Map<String, Object> value = Objects.requireNonNull(raw, "raw");
        StructuredOutputSpec<?> requiredSpec = Objects.requireNonNull(spec, "spec");
        requiredSpec.strictJsonSchema().ifPresent(schema -> validate(value, schema, "$"));
        return JsonUtils.getJsonCodec().convertValue(value, requiredSpec.javaType());
    }

    @SuppressWarnings("unchecked")
    private static void validate(Object value, Map<String, Object> schema, String location) {
        String type = (String) schema.get("type");
        switch (type) {
            case "object" -> {
                if (!(value instanceof Map<?, ?> object)) {
                    throw invalid(location, "must be an object");
                }
                Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
                List<String> required = (List<String>) schema.get("required");
                Set<?> actual = object.keySet();
                if (!actual.equals(new HashSet<>(properties.keySet()))) {
                    Set<String> missing = new HashSet<>(required);
                    missing.removeAll(actual);
                    Set<Object> unknown = new HashSet<>(actual);
                    unknown.removeAll(properties.keySet());
                    throw invalid(location,
                            "object fields do not match schema; missing=" + missing
                                    + ", unknown=" + unknown);
                }
                properties.forEach((name, child) -> validate(
                        object.get(name), (Map<String, Object>) child, location + "." + name));
            }
            case "array" -> {
                if (!(value instanceof List<?> list)) {
                    throw invalid(location, "must be an array");
                }
                requireRange(list.size(), schema, "minItems", "maxItems", location);
                Map<String, Object> items = (Map<String, Object>) schema.get("items");
                for (int index = 0; index < list.size(); index++) {
                    validate(list.get(index), items, location + "[" + index + "]");
                }
            }
            case "string" -> {
                if (!(value instanceof String text)) {
                    throw invalid(location, "must be a string");
                }
                requireRange(text.length(), schema, "minLength", "maxLength", location);
                if (schema.containsKey("const") && !schema.get("const").equals(text)) {
                    throw invalid(location, "must equal the schema constant");
                }
                if (schema.get("enum") instanceof List<?> values && !values.contains(text)) {
                    throw invalid(location, "must be one of the schema enum values");
                }
                if (schema.get("pattern") instanceof String pattern
                        && !Pattern.compile(pattern).matcher(text).matches()) {
                    throw invalid(location, "must match the schema pattern");
                }
            }
            case "integer" -> {
                if (!(value instanceof Number number)) {
                    throw invalid(location, "must be an integer");
                }
                BigDecimal decimal;
                try {
                    decimal = new BigDecimal(number.toString());
                    decimal.toBigIntegerExact();
                } catch (RuntimeException exception) {
                    throw invalid(location, "must be an integer");
                }
                if (schema.get("minimum") instanceof Number minimum
                        && decimal.compareTo(new BigDecimal(minimum.toString())) < 0) {
                    throw invalid(location, "is below the schema minimum");
                }
                if (schema.get("maximum") instanceof Number maximum
                        && decimal.compareTo(new BigDecimal(maximum.toString())) > 0) {
                    throw invalid(location, "exceeds the schema maximum");
                }
            }
            case "boolean" -> {
                if (!(value instanceof Boolean)) {
                    throw invalid(location, "must be a boolean");
                }
            }
            default -> throw invalid(location, "uses an unsupported schema type");
        }
    }

    private static void requireRange(
            long actual,
            Map<String, Object> schema,
            String minimumKey,
            String maximumKey,
            String location) {
        if (schema.get(minimumKey) instanceof Number minimum
                && actual < minimum.longValue()) {
            throw invalid(location, "is below " + minimumKey);
        }
        if (schema.get(maximumKey) instanceof Number maximum
                && actual > maximum.longValue()) {
            throw invalid(location, "exceeds " + maximumKey);
        }
    }

    private static IllegalArgumentException invalid(String location, String message) {
        return new IllegalArgumentException(location + ": " + message);
    }
}
