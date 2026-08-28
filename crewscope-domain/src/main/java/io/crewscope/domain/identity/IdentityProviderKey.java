package io.crewscope.domain.identity;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Canonical configured authentication-provider key such as local or oidc/corporate. */
public record IdentityProviderKey(String value) {

    public static final int MAX_LENGTH = 100;
    public static final int MAX_SEGMENT_LENGTH = 64;
    private static final Pattern FORMAT =
            Pattern.compile(
                    "[a-z0-9](?:[a-z0-9._-]{0,"
                            + (MAX_SEGMENT_LENGTH - 2)
                            + "}[a-z0-9])?(?:/[a-z0-9](?:[a-z0-9._-]{0,"
                            + (MAX_SEGMENT_LENGTH - 2)
                            + "}[a-z0-9])?)*");
    private static final IdentityProviderKey LOCAL = new IdentityProviderKey("local");

    public IdentityProviderKey {
        if (value == null || value.isBlank()) {
            throw invalid("must not be blank");
        }
        value = Normalizer.normalize(value.strip(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        if (value.length() > MAX_LENGTH || !FORMAT.matcher(value).matches()) {
            throw invalid(
                    "must be a canonical provider path with at most "
                            + MAX_LENGTH
                            + " characters and "
                            + MAX_SEGMENT_LENGTH
                            + " characters per segment");
        }
    }

    public static IdentityProviderKey local() {
        return LOCAL;
    }

    public boolean isLocal() {
        return equals(LOCAL);
    }

    private static DomainValidationException invalid(String reason) {
        return new DomainValidationException("loginIdentity.provider", reason);
    }

    @Override
    public String toString() {
        return value;
    }
}
