package io.crewscope.infrastructure.artifact;

import io.crewscope.application.artifact.ArtifactDataClassification;
import io.crewscope.application.artifact.ArtifactDescriptor;
import io.crewscope.application.artifact.ArtifactEncryption;
import io.crewscope.application.artifact.ArtifactProducer;
import io.crewscope.application.artifact.ArtifactScope;
import io.crewscope.application.artifact.ArtifactTombstone;
import io.crewscope.application.artifact.ArtifactTombstoneReason;
import io.crewscope.application.artifact.ArtifactVisibility;
import io.crewscope.application.artifact.Sha256Hash;
import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Stable JSON Sidecar codec that keeps filesystem metadata independent from Java serialization. */
final class FilesystemArtifactMetadataJsonCodec {

    private static final int SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;

    FilesystemArtifactMetadataJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    byte[] encode(ArtifactDescriptor descriptor) {
        ArtifactDescriptor source = Objects.requireNonNull(descriptor, "descriptor");
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("artifactId", source.artifactId().toString());
        root.put("organizationId", source.scope().organizationId().toString());
        putOptional(root, "teamId", source.scope().teamId().map(TeamId::toString));
        putOptional(root, "workspaceId", source.scope().workspaceId().map(WorkspaceId::toString));
        root.put("contentType", source.contentType());
        root.put("size", source.size());
        root.put("sha256", source.sha256().toString());
        root.put("dataClassification", source.dataClassification().name());
        root.put("visibility", source.visibility().name());
        root.put("storageUri", source.storageUri().toString());
        root.put("encryption", source.encryption().name());
        root.put("createdAt", source.createdAt().toString());
        putOptional(root, "retentionUntil", source.retentionUntil().map(UtcTimestamp::toString));

        ObjectNode producer = objectMapper.createObjectNode();
        producer.put("principalId", source.producer().principalId().toString());
        putOptional(producer, "taskExecutionId", source.producer().taskExecutionId().map(UUID::toString));
        putOptional(producer, "stepExecutionId", source.producer().stepExecutionId().map(UUID::toString));
        putOptional(producer, "agentRunId", source.producer().agentRunId().map(UUID::toString));
        putOptional(producer, "traceId", source.producer().traceId());
        root.set("producer", producer);

        if (source.tombstone().isEmpty()) {
            root.set("tombstone", objectMapper.nullNode());
        } else {
            ArtifactTombstone value = source.tombstone().orElseThrow();
            ObjectNode tombstone = objectMapper.createObjectNode();
            tombstone.put("reason", value.reason().name());
            putOptional(tombstone, "detail", value.detail());
            tombstone.put("tombstonedBy", value.tombstonedBy().toString());
            tombstone.put("tombstonedAt", value.tombstonedAt().toString());
            root.set("tombstone", tombstone);
        }
        return objectMapper.writeValueAsBytes(root);
    }

    ArtifactDescriptor decode(byte[] document) {
        byte[] source = Objects.requireNonNull(document, "document");
        try {
            JsonNode root = objectMapper.readTree(source);
            if (root == null || !root.isObject()) {
                throw invalid("Descriptor root must be a JSON object");
            }
            if (nonNegativeLong(root, "schemaVersion") != SCHEMA_VERSION) {
                throw invalid("Unsupported Descriptor schemaVersion");
            }
            ArtifactScope scope = new ArtifactScope(
                    OrganizationId.from(text(root, "organizationId")),
                    optionalText(root, "teamId").map(TeamId::from),
                    optionalText(root, "workspaceId").map(WorkspaceId::from));
            JsonNode producerNode = object(root, "producer");
            ArtifactProducer producer = new ArtifactProducer(
                    PrincipalId.from(text(producerNode, "principalId")),
                    optionalUuid(producerNode, "taskExecutionId"),
                    optionalUuid(producerNode, "stepExecutionId"),
                    optionalUuid(producerNode, "agentRunId"),
                    optionalText(producerNode, "traceId"));
            return new ArtifactDescriptor(
                    ArtifactId.from(text(root, "artifactId")),
                    scope,
                    text(root, "contentType"),
                    nonNegativeLong(root, "size"),
                    new Sha256Hash(text(root, "sha256")),
                    enumeration(root, "dataClassification", ArtifactDataClassification.class),
                    enumeration(root, "visibility", ArtifactVisibility.class),
                    URI.create(text(root, "storageUri")),
                    enumeration(root, "encryption", ArtifactEncryption.class),
                    producer,
                    UtcTimestamp.parse(text(root, "createdAt")),
                    optionalText(root, "retentionUntil").map(UtcTimestamp::parse),
                    decodeTombstone(root.get("tombstone")));
        } catch (InvalidArtifactMetadataException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new InvalidArtifactMetadataException(
                    "Descriptor violates the filesystem Artifact contract", exception);
        }
    }

    private Optional<ArtifactTombstone> decodeTombstone(JsonNode node) {
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        if (!node.isObject()) {
            throw invalid("tombstone must be an object or null");
        }
        return Optional.of(new ArtifactTombstone(
                enumeration(node, "reason", ArtifactTombstoneReason.class),
                optionalText(node, "detail"),
                PrincipalId.from(text(node, "tombstonedBy")),
                UtcTimestamp.parse(text(node, "tombstonedAt"))));
    }

    private void putOptional(ObjectNode node, String field, Optional<String> value) {
        Optional<String> optional = Objects.requireNonNull(value, field);
        if (optional.isPresent()) {
            node.put(field, optional.orElseThrow());
        } else {
            node.set(field, objectMapper.nullNode());
        }
    }

    private static JsonNode object(JsonNode root, String field) {
        JsonNode value = required(root, field);
        if (!value.isObject()) {
            throw invalid(field + " must be a JSON object");
        }
        return value;
    }

    private static JsonNode required(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            throw invalid(field + " is required");
        }
        return value;
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = required(root, field);
        if (!value.isString() || value.stringValue().isBlank()) {
            throw invalid(field + " must be non-blank text");
        }
        return value.stringValue();
    }

    private static Optional<String> optionalText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isString() || value.stringValue().isBlank()) {
            throw invalid(field + " must be non-blank text or null");
        }
        return Optional.of(value.stringValue());
    }

    private static Optional<UUID> optionalUuid(JsonNode root, String field) {
        return optionalText(root, field).map(value -> {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equalsIgnoreCase(value) || AggregateId.NIL_UUID.equals(parsed)) {
                throw invalid(field + " must be a canonical non-nil UUID");
            }
            return parsed;
        });
    }

    private static long nonNegativeLong(JsonNode root, String field) {
        JsonNode value = required(root, field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw invalid(field + " must be a non-negative integer");
        }
        return value.longValue();
    }

    private static <T extends Enum<T>> T enumeration(
            JsonNode root, String field, Class<T> enumType) {
        try {
            return Enum.valueOf(enumType, text(root, field));
        } catch (IllegalArgumentException exception) {
            throw new InvalidArtifactMetadataException(field + " has an unsupported value", exception);
        }
    }

    private static InvalidArtifactMetadataException invalid(String message) {
        return new InvalidArtifactMetadataException(message);
    }
}
