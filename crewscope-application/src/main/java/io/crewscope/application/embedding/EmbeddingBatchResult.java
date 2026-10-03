package io.crewscope.application.embedding;

import io.crewscope.domain.model.ModelCatalogCoordinate;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.retrieval.EmbeddingModelRevision;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Delivered embedding batch with the exact model identity that produced it: the revision
 * discriminator guarantees later index generations never mix vectors from a different
 * catalog revision of the embedding model (S01 §3.1).
 */
public record EmbeddingBatchResult(
        List<float[]> vectors,
        EmbeddingModelRevision model,
        ModelCatalogCoordinate coordinate,
        ModelConnectionId connectionId,
        long connectionVersion) {

    public EmbeddingBatchResult {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(connectionId, "connectionId");
        if (connectionVersion < 0) {
            throw new IllegalArgumentException("connectionVersion must not be negative");
        }
        vectors = defensiveCopy(Objects.requireNonNull(vectors, "vectors"));
        if (vectors.isEmpty()) {
            throw new IllegalArgumentException("vectors must contain at least one embedding");
        }
        for (float[] vector : vectors) {
            if (vector.length != model.dimension()) {
                throw new IllegalArgumentException(
                        "every vector must carry the revision dimension");
            }
        }
    }

    private static List<float[]> defensiveCopy(List<float[]> values) {
        List<float[]> copy = new ArrayList<>(values.size());
        for (float[] vector : values) {
            Objects.requireNonNull(vector, "vector");
            copy.add(vector.clone());
        }
        return List.copyOf(copy);
    }
}
