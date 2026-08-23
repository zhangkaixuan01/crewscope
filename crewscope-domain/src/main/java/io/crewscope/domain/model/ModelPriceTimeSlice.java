package io.crewscope.domain.model;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Derived half-open interval of one immutable model price revision. */
public record ModelPriceTimeSlice(
        ModelPriceRevision priceRevision, Optional<UtcTimestamp> effectiveUntil) {

    public ModelPriceTimeSlice {
        priceRevision = Objects.requireNonNull(priceRevision, "priceRevision");
        effectiveUntil = Objects.requireNonNull(effectiveUntil, "effectiveUntil");
        UtcTimestamp effectiveFrom = priceRevision.effectiveFrom();
        if (effectiveUntil
                .filter(value -> value.compareTo(effectiveFrom) <= 0)
                .isPresent()) {
            throw new DomainValidationException(
                    "modelPrice.effectiveUntil", "must be after effectiveFrom");
        }
    }

    public boolean contains(UtcTimestamp timestamp) {
        UtcTimestamp required = Objects.requireNonNull(timestamp, "timestamp");
        return priceRevision.effectiveFrom().compareTo(required) <= 0
                && effectiveUntil.map(value -> required.compareTo(value) < 0).orElse(true);
    }
}
