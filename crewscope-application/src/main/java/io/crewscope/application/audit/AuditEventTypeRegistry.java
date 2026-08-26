package io.crewscope.application.audit;

import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Exact EventType and payload-version registry for the M6 safe Audit projection. */
public final class AuditEventTypeRegistry {

    private final Map<Coordinate, AuditEventTypeDefinition> definitions;

    public AuditEventTypeRegistry(Collection<AuditEventTypeDefinition> definitions) {
        HashMap<Coordinate, AuditEventTypeDefinition> indexed = new HashMap<>();
        for (AuditEventTypeDefinition definition :
                Objects.requireNonNull(definitions, "definitions")) {
            AuditEventTypeDefinition required = Objects.requireNonNull(definition, "definition");
            Coordinate coordinate =
                    new Coordinate(required.eventType(), required.sourceSchemaVersion());
            if (indexed.put(coordinate, required) != null) {
                throw new IllegalArgumentException(
                        "Audit event type and schema coordinates must be unique");
            }
        }
        this.definitions = Map.copyOf(indexed);
    }

    public Optional<AuditEventTypeDefinition> find(
            EventType eventType, SchemaVersion schemaVersion) {
        return Optional.ofNullable(definitions.get(new Coordinate(
                Objects.requireNonNull(eventType, "eventType"),
                Objects.requireNonNull(schemaVersion, "schemaVersion"))));
    }

    public int size() {
        return definitions.size();
    }

    private record Coordinate(EventType eventType, SchemaVersion schemaVersion) {}
}
