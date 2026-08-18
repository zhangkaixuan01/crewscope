package io.crewscope.application.execution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Versioned Java output type requested from a runtime without exposing framework schemas. */
public record StructuredOutputSpec<T>(
        String schemaId,
        Class<T> javaType,
        Optional<Map<String, Object>> strictJsonSchema) {

    private static final Pattern SCHEMA_ID =
            Pattern.compile("[a-z][a-z0-9-]{1,63}/v[1-9][0-9]{0,3}");

    public StructuredOutputSpec(String schemaId, Class<T> javaType) {
        this(schemaId, javaType, Optional.empty());
    }

    public static <T> StructuredOutputSpec<T> strict(
            String schemaId, Class<T> javaType, Map<String, Object> jsonSchema) {
        return new StructuredOutputSpec<>(schemaId, javaType, Optional.of(jsonSchema));
    }

    public StructuredOutputSpec {
        schemaId = Objects.requireNonNull(schemaId, "schemaId").strip();
        javaType = Objects.requireNonNull(javaType, "javaType");
        if (!SCHEMA_ID.matcher(schemaId).matches()) {
            throw new IllegalArgumentException(
                    "schemaId must use a stable name/version form such as task-intent/v1");
        }
        if (javaType.isPrimitive() || javaType == Object.class) {
            throw new IllegalArgumentException("javaType must be a concrete structured value type");
        }
        strictJsonSchema = Objects.requireNonNull(strictJsonSchema, "strictJsonSchema")
                .map(StructuredOutputSpec::immutableSchema);
        strictJsonSchema.ifPresent(schema -> validateObjectSchema(schema, "$"));
    }

    public T requireValue(Object value) {
        return javaType.cast(Objects.requireNonNull(value, "value"));
    }

    private static Map<String, Object> immutableSchema(Map<String, Object> value) {
        return Collections.unmodifiableMap(copyMap(Objects.requireNonNull(value, "jsonSchema")));
    }

    private static Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!(key instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException("JSON Schema keys must be non-blank strings");
            }
            result.put(text, copyValue(value));
        });
        return result;
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return Collections.unmodifiableMap(copyMap(map));
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(copyValue(item)));
            return Collections.unmodifiableList(copy);
        }
        if (value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean) {
            return value;
        }
        throw new IllegalArgumentException("JSON Schema values must be JSON-compatible");
    }

    private static void validateObjectSchema(Map<String, Object> schema, String location) {
        if (!"object".equals(schema.get("type"))) {
            throw new IllegalArgumentException(location + " must be an object JSON Schema");
        }
        if (!Boolean.FALSE.equals(schema.get("additionalProperties"))) {
            throw new IllegalArgumentException(location + " must reject additional properties");
        }
        if (!(schema.get("properties") instanceof Map<?, ?> properties)
                || !(schema.get("required") instanceof List<?> required)
                || !required.equals(new ArrayList<>(properties.keySet()))) {
            throw new IllegalArgumentException(
                    location + " must require every property in declaration order");
        }
        properties.forEach((name, child) -> validateChildSchema(child, location + "." + name));
    }

    private static void validateChildSchema(Object candidate, String location) {
        if (!(candidate instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(location + " must be a JSON Schema object");
        }
        Map<String, Object> schema = copyMap(raw);
        Object type = schema.get("type");
        if (!(type instanceof String name)) {
            throw new IllegalArgumentException(location + " must declare one supported type");
        }
        switch (name) {
            case "object" -> validateObjectSchema(schema, location);
            case "array" -> validateChildSchema(schema.get("items"), location + "[]");
            case "string", "integer", "boolean" -> {
                // Supported leaf types are validated against their constraints by the decoder.
            }
            default -> throw new IllegalArgumentException(
                    location + " uses unsupported JSON Schema type " + name);
        }
    }
}
