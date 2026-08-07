package io.crewscope.infrastructure.credential;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** JSONB codec restricted to deterministic non-null string metadata. */
final class CredentialMetadataJsonCodec {

    private final ObjectMapper objectMapper;

    CredentialMetadataJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    String encode(Map<String, String> metadata) {
        ObjectNode root = objectMapper.createObjectNode();
        new TreeMap<>(Objects.requireNonNull(metadata, "metadata"))
                .forEach(root::put);
        return objectMapper.writeValueAsString(root);
    }

    Map<String, String> decode(String document) {
        try {
            JsonNode root = objectMapper.readTree(document);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("Credential metadata must be a JSON object");
            }
            TreeMap<String, String> result = new TreeMap<>();
            root.properties().forEach(entry -> {
                if (!entry.getValue().isString()) {
                    throw new IllegalArgumentException("Credential metadata values must be strings");
                }
                result.put(entry.getKey(), entry.getValue().stringValue());
            });
            return Map.copyOf(result);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Credential metadata is invalid", exception);
        }
    }
}
