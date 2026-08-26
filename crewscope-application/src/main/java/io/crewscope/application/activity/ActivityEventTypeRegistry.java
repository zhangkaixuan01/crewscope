package io.crewscope.application.activity;

import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable fail-closed registry of Activity-safe DomainEvent schemas. */
public final class ActivityEventTypeRegistry {

    private final Map<EventSchemaKey, ActivityEventTypeDefinition> definitions;

    public ActivityEventTypeRegistry(Collection<ActivityEventTypeDefinition> definitions) {
        Collection<ActivityEventTypeDefinition> source =
                Objects.requireNonNull(definitions, "definitions");
        Map<EventSchemaKey, ActivityEventTypeDefinition> indexed = new LinkedHashMap<>();
        for (ActivityEventTypeDefinition definition : source) {
            ActivityEventTypeDefinition required = Objects.requireNonNull(definition, "definition");
            EventSchemaKey key = new EventSchemaKey(
                    required.eventType(), required.sourceSchemaVersion());
            if (indexed.putIfAbsent(key, required) != null) {
                throw new IllegalArgumentException(
                        "Duplicate Activity EventType schema: "
                                + required.eventType() + "@" + required.sourceSchemaVersion());
            }
        }
        this.definitions = Map.copyOf(indexed);
    }

    public Optional<ActivityEventTypeDefinition> find(
            EventType eventType, SchemaVersion sourceSchemaVersion) {
        return Optional.ofNullable(definitions.get(new EventSchemaKey(
                Objects.requireNonNull(eventType, "eventType"),
                Objects.requireNonNull(sourceSchemaVersion, "sourceSchemaVersion"))));
    }

    public List<ActivityEventTypeDefinition> definitions() {
        return definitions.values().stream()
                .sorted(Comparator
                        .comparing((ActivityEventTypeDefinition value) ->
                                value.eventType().value())
                        .thenComparingInt(value -> value.sourceSchemaVersion().value()))
                .toList();
    }

    private record EventSchemaKey(EventType eventType, SchemaVersion sourceSchemaVersion) {}
}
