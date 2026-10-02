package io.crewscope.domain.retrieval;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Locale;
import java.util.regex.Pattern;

/** Lowercase hexadecimal git commit id (SHA-1 or SHA-256) anchoring one index build. */
public record SourceCommit(String value) {

    private static final Pattern HEX_OID = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

    public SourceCommit {
        if (value == null) {
            throw new DomainValidationException("sourceCommit", "must not be null");
        }
        value = value.strip().toLowerCase(Locale.ROOT);
        if (!HEX_OID.matcher(value).matches()) {
            throw new DomainValidationException(
                    "sourceCommit", "must be a 40- or 64-character lowercase hex commit id");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
