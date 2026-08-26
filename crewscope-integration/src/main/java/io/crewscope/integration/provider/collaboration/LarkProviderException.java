package io.crewscope.integration.provider.collaboration;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Safe Connector failure that never retains the endpoint, response body or credential values. */
public final class LarkProviderException extends RuntimeException {

    private final LarkProviderErrorCode code;
    private final Optional<Duration> retryAfter;
    private final String evidenceCode;

    public LarkProviderException(
            LarkProviderErrorCode code,
            String safeMessage,
            Optional<Duration> retryAfter,
            String evidenceCode) {
        super(requireMessage(safeMessage));
        this.code = Objects.requireNonNull(code, "code");
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
        this.retryAfter.ifPresent(value -> {
            if (value.isZero() || value.isNegative() || value.compareTo(Duration.ofMinutes(5)) > 0) {
                throw new IllegalArgumentException("Lark Retry-After must be within (0, 5m]");
            }
        });
        if (evidenceCode == null || !evidenceCode.matches("[A-Z][A-Z0-9_]{2,63}")) {
            throw new IllegalArgumentException("Lark evidence code is invalid");
        }
        this.evidenceCode = evidenceCode;
    }

    public LarkProviderErrorCode code() {
        return code;
    }

    public boolean retryable() {
        return code.retryable();
    }

    public Optional<Duration> retryAfter() {
        return retryAfter;
    }

    public String evidenceCode() {
        return evidenceCode;
    }

    @Override
    public String toString() {
        return "LarkProviderException[code=" + code + ", retryable=" + retryable()
                + ", retryAfter=" + retryAfter + ", evidenceCode=" + evidenceCode + ']';
    }

    static LarkProviderException of(
            LarkProviderErrorCode code, String message, String evidenceCode) {
        return new LarkProviderException(code, message, Optional.empty(), evidenceCode);
    }

    private static String requireMessage(String value) {
        if (value == null || value.isBlank() || value.length() > 300
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Lark safe message is invalid");
        }
        return value;
    }
}
