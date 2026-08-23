package io.crewscope.domain.model;

import io.crewscope.domain.shared.error.DomainValidationException;

/** Auditable source label or URL for one published model price. */
public record ModelPriceSource(String value) {

    public static final int MAX_LENGTH = 500;

    public ModelPriceSource {
        if (value == null || value.isBlank() || value.strip().length() > MAX_LENGTH) {
            throw new DomainValidationException(
                    "modelPrice.source", "must be non-blank and at most 500 characters");
        }
        value = value.strip();
    }

    @Override
    public String toString() {
        return value;
    }
}
