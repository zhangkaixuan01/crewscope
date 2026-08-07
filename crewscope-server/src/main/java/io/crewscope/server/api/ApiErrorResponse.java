package io.crewscope.server.api;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Stable `/api/v1` error envelope containing only client-safe failure details. */
public record ApiErrorResponse(
        String code,
        String message,
        UUID correlationId,
        boolean retryable,
        Long currentVersion,
        Map<String, String> details) {

    public ApiErrorResponse {
        code = requireText(code, "code");
        message = requireText(message, "message");
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        details = Map.copyOf(Objects.requireNonNull(details, "details"));
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }
}
