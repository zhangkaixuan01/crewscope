package io.crewscope.domain.shared.error;

import java.util.Map;

/** Reports a value that cannot enter the domain model. Rejected raw values stay out of details. */
public final class DomainValidationException extends DomainException {

    public DomainValidationException(String field, String reason) {
        super(new DomainError(
                DomainErrorCode.INVALID_VALUE,
                "Invalid " + requireText(field, "field") + ": " + requireText(reason, "reason"),
                Map.of("field", field.strip(), "reason", reason.strip())));
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
