package io.crewscope.infrastructure.persistence.action;

import io.crewscope.domain.action.ActionAuthoritySnapshot;
import io.crewscope.domain.coding.DiffGeneration;
import io.crewscope.domain.coding.DiffPath;
import io.crewscope.domain.coding.PatchArtifactReference;
import io.crewscope.domain.review.ContextPackageReference;
import io.crewscope.domain.review.ReviewSubjectType;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.task.RuntimeContentHash;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Explicit non-secret JSONB supplement for Action authority fields without dedicated columns. */
@Component
public final class ActionAuthorityJsonCodec {

    private static final long SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;

    public ActionAuthorityJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    String encode(ActionAuthoritySnapshot authority) {
        ActionAuthoritySnapshot value = Objects.requireNonNull(authority, "authority");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("reviewSubjectType", value.reviewDecision()
                .reviewRequest().subject().type().name());
        root.put("contextPackageVersion", value.reviewDecision()
                .reviewRequest().contextPackage().version());
        var diff = value.diff();
        root.put("diffGeneration", diff.generation().value());
        root.put("diffManifestHash", diff.manifestHash().value());
        root.put("patchArtifactId", diff.patchArtifact().artifactId().toString());
        root.put("patchSizeBytes", diff.patchArtifact().sizeBytes());
        root.put("patchSha256", diff.patchArtifact().patchSha256().value());
        root.put("changedPaths", diff.changedPaths().stream().map(DiffPath::value).toList());
        return objectMapper.writeValueAsString(root);
    }

    Supplement decode(String json) {
        Map<String, Object> root = readMap(json);
        if (number(root, "schemaVersion") != SCHEMA_VERSION) {
            throw new IllegalStateException("Unsupported Action authority snapshot schema");
        }
        return new Supplement(
                ReviewSubjectType.valueOf(text(root, "reviewSubjectType")),
                number(root, "contextPackageVersion"),
                new DiffGeneration(number(root, "diffGeneration")),
                new RuntimeContentHash(text(root, "diffManifestHash")),
                new PatchArtifactReference(
                        new ArtifactId(UUID.fromString(text(root, "patchArtifactId"))),
                        number(root, "patchSizeBytes"),
                        new RuntimeContentHash(text(root, "patchSha256"))),
                stringList(root, "changedPaths").stream().map(DiffPath::new).toList());
    }

    String parameters(String kind) {
        return objectMapper.writeValueAsString(Map.of(
                "schemaVersion", SCHEMA_VERSION,
                "actionKind", Objects.requireNonNull(kind, "kind")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String value) {
        try {
            Object decoded = objectMapper.readValue(value, Map.class);
            if (!(decoded instanceof Map<?, ?> source)) {
                throw new IllegalStateException("Action authority snapshot must be an object");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            source.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Invalid Action authority snapshot JSON", exception);
        }
    }

    private static String text(Map<String, Object> value, String key) {
        Object raw = value.get(key);
        if (!(raw instanceof String result) || result.isBlank()) {
            throw new IllegalStateException(key + " must be non-blank text");
        }
        return result;
    }

    private static long number(Map<String, Object> value, String key) {
        Object raw = value.get(key);
        if (!(raw instanceof Number result)) {
            throw new IllegalStateException(key + " must be numeric");
        }
        return result.longValue();
    }

    private static List<String> stringList(Map<String, Object> value, String key) {
        Object raw = value.get(key);
        if (!(raw instanceof List<?> result)) {
            throw new IllegalStateException(key + " must be an array");
        }
        return result.stream().map(item -> Objects.toString(item, null)).toList();
    }

    record Supplement(
            ReviewSubjectType subjectType,
            long contextPackageVersion,
            DiffGeneration diffGeneration,
            RuntimeContentHash diffManifestHash,
            PatchArtifactReference patchArtifact,
            List<DiffPath> changedPaths) {

        Supplement {
            Objects.requireNonNull(subjectType, "subjectType");
            if (contextPackageVersion < 1) {
                throw new IllegalStateException("ContextPackage version must be positive");
            }
            Objects.requireNonNull(diffGeneration, "diffGeneration");
            Objects.requireNonNull(diffManifestHash, "diffManifestHash");
            Objects.requireNonNull(patchArtifact, "patchArtifact");
            changedPaths = List.copyOf(Objects.requireNonNull(changedPaths, "changedPaths"));
        }

        ContextPackageReference context(
                io.crewscope.domain.review.ContextPackageId id,
                io.crewscope.domain.task.TaskFactHash hash) {
            return new ContextPackageReference(id, contextPackageVersion, hash);
        }
    }
}
