package io.crewscope.domain.task;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;

/** Exact version reference used at a model, Tool, checkpoint or recovery boundary. */
public record SafetyEnforcementOverlayReference(
        SafetyEnforcementOverlayId id, long version, TaskFactHash overlayHash) {
    public SafetyEnforcementOverlayReference {
        id = Objects.requireNonNull(id, "id");
        if (version < 1) {
            throw new DomainValidationException(
                    "safetyEnforcementOverlay.version", "must be positive");
        }
        overlayHash = Objects.requireNonNull(overlayHash, "overlayHash");
    }
}
