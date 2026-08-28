package io.crewscope.domain.identity;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Exact immutable provider subject; normalization is deliberately forbidden. */
public final class LoginIdentitySubject {

    public static final int MAX_CODE_POINTS = 500;
    public static final int MAX_UTF8_BYTES = 1_024;

    private final String value;

    public LoginIdentitySubject(String value) {
        if (value == null || value.isBlank()) {
            throw invalid("must not be blank");
        }
        if (!value.equals(value.strip())) {
            throw invalid("must not contain surrounding whitespace");
        }
        int codePoints = value.codePointCount(0, value.length());
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (codePoints > MAX_CODE_POINTS || bytes > MAX_UTF8_BYTES) {
            throw invalid("exceeds the provider subject budget");
        }
        if (value.codePoints().anyMatch(AccountTextPolicy::isUnsafe)) {
            throw invalid("must contain safe Unicode text");
        }
        this.value = value;
    }

    public static LoginIdentitySubject local(UserAccountId accountId) {
        return new LoginIdentitySubject(Objects.requireNonNull(accountId, "accountId").toString());
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof LoginIdentitySubject subject && value.equals(subject.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "[REDACTED]";
    }

    private static DomainValidationException invalid(String reason) {
        return new DomainValidationException("loginIdentity.subject", reason);
    }
}
