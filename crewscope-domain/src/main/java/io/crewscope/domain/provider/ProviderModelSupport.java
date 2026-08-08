package io.crewscope.domain.provider;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Objects;
import java.util.regex.Pattern;

/** Package guards shared by Provider registry and authorization aggregates. */
final class ProviderModelSupport {

    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9-]{0,99}");
    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z._+-]{0,63}");

    private ProviderModelSupport() {}

    static String key(String value, String field) {
        if (value == null || !KEY.matcher(value.strip()).matches()) {
            throw new DomainValidationException(field, "must be a lowercase stable key");
        }
        return value.strip();
    }

    static String version(String value, String field) {
        if (value == null || !VERSION.matcher(value.strip()).matches()) {
            throw new DomainValidationException(field, "must be a stable version string");
        }
        return value.strip();
    }

    static String text(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.strip().length() > maxLength) {
            throw new DomainValidationException(field, "must be non-blank and within its limit");
        }
        return value.strip();
    }

    static long nonNegativeVersion(long value, String field) {
        if (value < 0) {
            throw new DomainValidationException(field, "must not be negative");
        }
        return value;
    }

    static PrincipalId activeActor(
            Principal actor, OrganizationId organizationId, String field) {
        Principal required = Objects.requireNonNull(actor, "actor");
        if (!required.canAct()
                || !required.scope().organizationId().equals(organizationId)) {
            throw new DomainValidationException(
                    field, "must be an active Principal in the Provider Organization");
        }
        return required.id();
    }
}
