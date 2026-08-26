package io.crewscope.integration.provider.collaboration;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Maps only status and bounded headers; raw Provider bodies are deliberately absent. */
final class LarkErrorNormalizer {

    private LarkErrorNormalizer() {}

    static LarkProviderException normalize(int status, HttpHeaders headers) {
        Objects.requireNonNull(headers, "headers");
        LarkProviderErrorCode code = switch (status) {
            case 401 -> LarkProviderErrorCode.AUTHENTICATION_REQUIRED;
            case 403 -> LarkProviderErrorCode.PERMISSION_DENIED;
            case 404 -> LarkProviderErrorCode.RESOURCE_UNAVAILABLE;
            case 429 -> LarkProviderErrorCode.RATE_LIMITED;
            default -> status >= 500
                    ? LarkProviderErrorCode.PROVIDER_UNAVAILABLE
                    : LarkProviderErrorCode.INVALID_RESPONSE;
        };
        Optional<Duration> retryAfter = code == LarkProviderErrorCode.RATE_LIMITED
                ? headers.firstValue("Retry-After").flatMap(LarkErrorNormalizer::parseRetryAfter)
                : Optional.empty();
        String evidence = switch (code) {
            case AUTHENTICATION_REQUIRED -> "LARK_HTTP_AUTHENTICATION_REQUIRED";
            case PERMISSION_DENIED -> "LARK_HTTP_PERMISSION_DENIED";
            case RESOURCE_UNAVAILABLE -> "LARK_HTTP_RESOURCE_UNAVAILABLE";
            case RATE_LIMITED -> "LARK_HTTP_RATE_LIMITED";
            case PROVIDER_UNAVAILABLE -> "LARK_HTTP_PROVIDER_UNAVAILABLE";
            default -> "LARK_HTTP_INVALID_RESPONSE";
        };
        return new LarkProviderException(
                code,
                "Lark request failed with a safe Provider status category",
                retryAfter,
                evidence);
    }

    static LarkProviderException transportFailure(boolean possiblyWritten) {
        return LarkProviderException.of(
                possiblyWritten
                        ? LarkProviderErrorCode.UNKNOWN_DELIVERY
                        : LarkProviderErrorCode.PROVIDER_UNAVAILABLE,
                possiblyWritten
                        ? "Lark write result is unknown after a transport failure"
                        : "Lark read request is unavailable after a transport failure",
                possiblyWritten ? "LARK_WRITE_RESULT_UNKNOWN" : "LARK_TRANSPORT_UNAVAILABLE");
    }

    static LarkProviderException cancelled() {
        return LarkProviderException.of(
                LarkProviderErrorCode.CANCELLED,
                "Lark request was cancelled",
                "LARK_REQUEST_CANCELLED");
    }

    private static Optional<Duration> parseRetryAfter(String value) {
        try {
            long seconds = Long.parseLong(value.strip());
            return seconds > 0 && seconds <= 300
                    ? Optional.of(Duration.ofSeconds(seconds))
                    : Optional.empty();
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }
}
