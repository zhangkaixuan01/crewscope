package io.crewscope.application.inbox;

import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable allowlist that matches Inbox events by EventType and SchemaVersion together. */
public final class InboxEventTypeRegistry {

    private final Map<Coordinate, InboxEventTypeDefinition> definitions;

    public InboxEventTypeRegistry(List<InboxEventTypeDefinition> definitions) {
        LinkedHashMap<Coordinate, InboxEventTypeDefinition> registered = new LinkedHashMap<>();
        for (InboxEventTypeDefinition definition : List.copyOf(
                Objects.requireNonNull(definitions, "definitions"))) {
            InboxEventTypeDefinition value = Objects.requireNonNull(definition, "definition");
            Coordinate coordinate = new Coordinate(value.eventType(), value.schemaVersion());
            if (registered.putIfAbsent(coordinate, value) != null) {
                throw new IllegalArgumentException(
                        "Duplicate Inbox event coordinate: " + coordinate);
            }
        }
        this.definitions = Map.copyOf(registered);
    }

    public Optional<InboxEventTypeDefinition> find(
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
