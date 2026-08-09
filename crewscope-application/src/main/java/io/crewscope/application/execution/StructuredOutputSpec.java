package io.crewscope.application.execution;

import java.util.Objects;
import java.util.regex.Pattern;

/** Versioned Java output type requested from a runtime without exposing framework schemas. */
public record StructuredOutputSpec<T>(String schemaId, Class<T> javaType) {

    private static final Pattern SCHEMA_ID =
            Pattern.compile("[a-z][a-z0-9-]{1,63}/v[1-9][0-9]{0,3}");

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
    }

    public T requireValue(Object value) {
        return javaType.cast(Objects.requireNonNull(value, "value"));
    }
}
