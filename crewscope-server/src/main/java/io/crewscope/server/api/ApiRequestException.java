package io.crewscope.server.api;

import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;

/** Expected HTTP contract failure created before a request reaches the application layer. */
public final class ApiRequestException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, String> details;

    public ApiRequestException(
            HttpStatus status, String code, String message, Map<String, String> details) {
        super(requireText(message, "message"));
        this.status = Objects.requireNonNull(status, "status");
        this.code = requireText(code, "code");
        this.details = Map.copyOf(Objects.requireNonNull(details, "details"));
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, String> details() {
        return details;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }
}
