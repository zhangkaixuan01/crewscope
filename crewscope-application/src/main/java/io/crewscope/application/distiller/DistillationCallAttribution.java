package io.crewscope.application.distiller;

import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.model.ModelProviderKey;
import java.util.List;
import java.util.Objects;

/** Stable coordinates of a resolved Distiller call plus one usage entry per real attempt. */
public record DistillationCallAttribution(
        ModelProviderKey providerKey,
        ModelId modelId,
        ModelConnectionId connectionId,
        long connectionVersion,
        List<DistillationAttemptUsage> attempts) {

    public DistillationCallAttribution {
        providerKey = Objects.requireNonNull(providerKey, "providerKey");
        modelId = Objects.requireNonNull(modelId, "modelId");
        connectionId = Objects.requireNonNull(connectionId, "connectionId");
        if (connectionVersion < 0) {
            throw new IllegalArgumentException("connectionVersion must not be negative");
        }
        attempts = List.copyOf(Objects.requireNonNull(attempts, "attempts"));
        if (attempts.isEmpty()) {
            throw new IllegalArgumentException("attempts must not be empty");
        }
    }
}
