package io.crewscope.domain.retrieval;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;

/**
 * How many candidates of one prompt layer were trimmed and why. Layer names reuse
 * {@link ManifestSourceType}: the four source types are the four budgeted layers.
 */
public record TrimRecord(ManifestSourceType layer, int trimmedCount, String reason) {

    public TrimRecord {
        Objects.requireNonNull(layer, "layer");
        if (trimmedCount < 1) {
            throw new DomainValidationException(
                    "trimRecord.trimmedCount", "must be positive");
        }
        if (reason == null || reason.isBlank()) {
            throw new DomainValidationException("trimRecord.reason", "must not be blank");
        }
        reason = reason.strip();
    }
}
