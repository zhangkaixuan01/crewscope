package io.crewscope.domain.collaboration;

import io.crewscope.domain.shared.error.DomainValidationException;

/** Bounded opaque version returned by the exact Lark identity query. */
public record LarkProviderVersion(String value) {

    public LarkProviderVersion {
        if (value == null || value.isBlank() || value.strip().length() > 200) {
            throw new DomainValidationException(
                    "larkProviderVersion", "must contain 1 to 200 characters");
        }
        value = value.strip();
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new DomainValidationException(
                    "larkProviderVersion", "must not contain control characters");
        }
    }

    @Override
    public String toString() {
        return "LarkProviderVersion[REDACTED]";
    }
}
