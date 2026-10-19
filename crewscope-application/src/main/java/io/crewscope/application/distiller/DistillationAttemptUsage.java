package io.crewscope.application.distiller;

import io.crewscope.domain.model.ModelTokenUsage;
import java.util.Objects;

/** Token quadruple of one real Provider attempt; zeros mean "not reported". */
public record DistillationAttemptUsage(int attempt, ModelTokenUsage usage) {

    public DistillationAttemptUsage {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        usage = Objects.requireNonNull(usage, "usage");
    }
}
