package io.crewscope.server.api;

import io.crewscope.application.command.IdempotencyKey;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/** Strict parsers and formatters for command concurrency headers. */
public final class ApiHeaders {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String IDEMPOTENCY_REPLAYED = "Idempotency-Replayed";
    public static final String IF_MATCH = "If-Match";

    private static final Pattern STRONG_VERSION_ETAG = Pattern.compile("\"(0|[1-9][0-9]*)\"");

    private ApiHeaders() {}

    /** Requires one normalized public command idempotency key. */
    public static IdempotencyKey requireIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            throw invalidRequest(IDEMPOTENCY_KEY, "is required");
        }
        try {
            return IdempotencyKey.from(value);
        } catch (IllegalArgumentException exception) {
            throw invalidRequest(IDEMPOTENCY_KEY, "has an invalid format");
        }
    }

    /** Requires a single strong ETag containing one non-negative aggregate version. */
    public static long requireIfMatch(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiRequestException(
                    HttpStatus.PRECONDITION_REQUIRED,
                    "precondition_required",
                    "If-Match is required",
                    Map.of("header", IF_MATCH));
        }
        Matcher matcher = STRONG_VERSION_ETAG.matcher(value);
        if (!matcher.matches()) {
            throw invalidIfMatch();
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw invalidIfMatch();
        }
    }

    /** Formats a committed aggregate version as a strong HTTP ETag. */
    public static String versionEtag(long version) {
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        return "\"" + version + "\"";
    }

    private static ApiRequestException invalidRequest(String header, String reason) {
        return new ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Invalid " + header + " header",
                Map.of("header", header, "reason", reason));
    }

    private static ApiRequestException invalidIfMatch() {
        return new ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "invalid_if_match",
                "If-Match must contain one strong non-negative version ETag",
                Map.of("header", IF_MATCH));
    }
}
