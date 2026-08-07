package io.crewscope.application.event.json;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Package-private JSON tree helpers that keep both envelope codecs contract-identical. */
final class EventEnvelopeJsonSupport {

    private final ObjectMapper objectMapper;

    EventEnvelopeJsonSupport(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    ObjectNode objectNode() {
        return objectMapper.createObjectNode();
    }

    JsonNode nullNode() {
        return objectMapper.nullNode();
    }

    String write(ObjectNode root) {
        try {
            return objectMapper.writeValueAsString(root);
        } catch (RuntimeException exception) {
            throw new EventEnvelopeJsonException("Unable to encode event envelope JSON", exception);
        }
    }

    ObjectNode readObject(String json) {
        if (json == null || json.isBlank()) {
            throw new EventEnvelopeJsonException("Event envelope JSON must not be blank");
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isObject()) {
                throw new EventEnvelopeJsonException("Event envelope JSON root must be an object");
            }
            return (ObjectNode) root;
        } catch (EventEnvelopeJsonException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new EventEnvelopeJsonException("Unable to decode event envelope JSON", exception);
        }
    }

    ObjectNode payloadNode(Object payload) {
        try {
            JsonNode node = objectMapper.valueToTree(Objects.requireNonNull(payload, "payload"));
            if (!node.isObject()) {
                throw new EventEnvelopeJsonException("Event envelope payload must encode as an object");
            }
            return (ObjectNode) node;
        } catch (EventEnvelopeJsonException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new EventEnvelopeJsonException("Unable to encode event envelope payload", exception);
        }
    }

    <T> T readPayload(ObjectNode root, Class<T> payloadType) {
        Objects.requireNonNull(payloadType, "payloadType");
        JsonNode payload = required(root, "payload");
        if (!payload.isObject()) {
            throw invalidField("payload", "must be an object");
        }
        try {
            return objectMapper.treeToValue(payload, payloadType);
        } catch (RuntimeException exception) {
            throw new EventEnvelopeJsonException("Unable to decode event envelope payload", exception);
        }
    }

    String requiredText(ObjectNode root, String field) {
        JsonNode node = required(root, field);
        if (!node.isString() || node.stringValue().isBlank()) {
            throw invalidField(field, "must be non-blank text");
        }
        return node.stringValue();
    }

    Optional<String> optionalText(ObjectNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        if (!node.isString()) {
            throw invalidField(field, "must be text or null");
        }
        return Optional.of(node.stringValue());
    }

    UUID requiredUuid(ObjectNode root, String field) {
        return parseUuid(requiredText(root, field), field);
    }

    Optional<UUID> optionalUuid(ObjectNode root, String field) {
        return optionalText(root, field).map(value -> parseUuid(value, field));
    }

    long requiredLong(ObjectNode root, String field) {
        JsonNode node = required(root, field);
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw invalidField(field, "must be a 64-bit integer");
        }
        return node.longValue();
    }

    Optional<Long> optionalLong(ObjectNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw invalidField(field, "must be a 64-bit integer or null");
        }
        return Optional.of(node.longValue());
    }

    <T> Optional<T> optionalMappedText(
            ObjectNode root, String field, Function<String, T> mapper) {
        try {
            return optionalText(root, field).map(mapper);
        } catch (EventEnvelopeJsonException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalidField(field, "has an invalid value", exception);
        }
    }

    <E extends Enum<E>> E requiredEnum(ObjectNode root, String field, Class<E> enumType) {
        try {
            return Enum.valueOf(enumType, requiredText(root, field));
        } catch (EventEnvelopeJsonException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalidField(field, "has an unsupported value", exception);
        }
    }

    void putNullable(ObjectNode root, String field, Optional<?> value) {
        Objects.requireNonNull(value, field);
        if (value.isPresent()) {
            Object present = value.orElseThrow();
            root.put(field, present.toString());
        } else {
            root.set(field, nullNode());
        }
    }

    private JsonNode required(ObjectNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            throw invalidField(field, "is required");
        }
        return node;
    }

    private UUID parseUuid(String value, String field) {
        try {
            String candidate = value.strip();
            UUID parsed = UUID.fromString(candidate);
            if (!parsed.toString().equalsIgnoreCase(candidate) || AggregateId.NIL_UUID.equals(parsed)) {
                throw new IllegalArgumentException("not canonical");
            }
            return parsed;
        } catch (RuntimeException exception) {
            throw invalidField(field, "must be a canonical non-nil UUID", exception);
        }
    }

    private EventEnvelopeJsonException invalidField(String field, String reason) {
        return new EventEnvelopeJsonException("Invalid event envelope field '" + field + "': " + reason);
    }

    private EventEnvelopeJsonException invalidField(
            String field, String reason, RuntimeException cause) {
        return new EventEnvelopeJsonException(
                "Invalid event envelope field '" + field + "': " + reason, cause);
    }
}
