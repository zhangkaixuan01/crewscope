package io.crewscope.domain.audit;

import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import java.util.Map;
import java.util.Objects;

/** Immutable, schema-approved Audit summary that contains no original event Payload. */
public final class AuditRedactedSummary {

    private final EventType eventType;
    private final SchemaVersion sourceSchemaVersion;
    private final AuditEventCategory category;
    private final Map<String, String> values;

    AuditRedactedSummary(
            EventType eventType,
            SchemaVersion sourceSchemaVersion,
            AuditEventCategory category,
            Map<String, String> values) {
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.sourceSchemaVersion = Objects.requireNonNull(
                sourceSchemaVersion, "sourceSchemaVersion");
        this.category = Objects.requireNonNull(category, "category");
        this.values = Map.copyOf(Objects.requireNonNull(values, "values"));
    }

    public EventType eventType() {
        return eventType;
    }

    public SchemaVersion sourceSchemaVersion() {
        return sourceSchemaVersion;
    }

    public AuditEventCategory category() {
        return category;
    }

    public Map<String, String> values() {
        return values;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof AuditRedactedSummary value
                        && eventType.equals(value.eventType)
                        && sourceSchemaVersion.equals(value.sourceSchemaVersion)
                        && category == value.category
                        && values.equals(value.values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventType, sourceSchemaVersion, category, values);
    }
}
