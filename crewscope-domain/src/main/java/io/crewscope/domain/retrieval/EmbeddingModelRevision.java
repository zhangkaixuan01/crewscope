package io.crewscope.domain.retrieval;

import io.crewscope.domain.shared.error.DomainValidationException;

/**
 * Identity of the embedding model, output dimension and training revision that produced
 * one index generation's vectors. Vectors of different revisions or dimensions are never
 * mixed inside one generation (M10-S01 §3.1).
 */
public record EmbeddingModelRevision(String modelKey, int dimension, long revision) {

    public EmbeddingModelRevision {
        if (modelKey == null || modelKey.isBlank()) {
            throw new DomainValidationException(
                    "embeddingModelRevision.modelKey", "must not be blank");
        }
        modelKey = modelKey.strip();
        if (dimension < 1) {
            throw new DomainValidationException(
                    "embeddingModelRevision.dimension", "must be positive");
        }
        if (revision < 1) {
            throw new DomainValidationException(
                    "embeddingModelRevision.revision", "must be positive");
        }
    }
}
