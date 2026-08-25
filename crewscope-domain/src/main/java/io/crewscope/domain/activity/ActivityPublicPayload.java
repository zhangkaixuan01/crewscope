package io.crewscope.domain.activity;

import java.util.Map;
import java.util.Objects;

/** Schema-bound immutable public fields exposed by an Activity event. */
public final class ActivityPublicPayload {

    private final ActivityPayloadSchemaRef schema;
    private final Map<String, String> values;

    ActivityPublicPayload(ActivityPayloadSchemaRef schema, Map<String, String> values) {
        this.schema = Objects.requireNonNull(schema, "schema");
        this.values = Map.copyOf(Objects.requireNonNull(values, "values"));
    }

    public ActivityPayloadSchemaRef schema() {
        return schema;
    }

    public Map<String, String> values() {
        return values;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof ActivityPublicPayload payload
                        && schema.equals(payload.schema)
                        && values.equals(payload.values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schema, values);
    }

    @Override
    public String toString() {
        return "ActivityPublicPayload[schema=" + schema + ", values=" + values + "]";
    }
}
