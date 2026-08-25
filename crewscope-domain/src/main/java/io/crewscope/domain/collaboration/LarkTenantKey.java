package io.crewscope.domain.collaboration;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.regex.Pattern;

/** Exact Lark tenant_key returned by the authenticated tenant query. */
public record LarkTenantKey(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");

    public LarkTenantKey {
        if (value == null || !FORMAT.matcher(value.strip()).matches()) {
            throw new DomainValidationException("larkTenantKey", "has an invalid shape");
        }
        value = value.strip();
    }

    @Override
    public String toString() {
        return "LarkTenantKey[REDACTED]";
    }
}
