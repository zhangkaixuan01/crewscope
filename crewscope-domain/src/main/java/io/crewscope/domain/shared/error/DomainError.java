package io.crewscope.domain.shared.error;

import java.util.Map;
import java.util.Objects;

/** Safe, immutable domain error payload shared by API, event and Agent tool boundaries. */
public record DomainError(
        DomainErrorCode code, String message, Map<String, String> details) {

    public DomainError {
        code = Objects.requireNonNull(code, "code");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        message = message.strip();
        details = Map.copyOf(Objects.requireNonNull(details, "details"));
    }

    public DomainErrorCategory category() {
        return code.category();
    }
}
