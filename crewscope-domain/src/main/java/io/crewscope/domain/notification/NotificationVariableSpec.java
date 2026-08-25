package io.crewscope.domain.notification;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Exact validation contract for one fixed-template variable. */
public record NotificationVariableSpec(
        String name,
        NotificationVariableType type,
        int maximumLength,
        Set<TrustedNotificationOrigin> trustedOrigins) {

    private static final Pattern NAME = Pattern.compile("[a-z][a-zA-Z0-9]{0,63}");

    public NotificationVariableSpec {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new DomainValidationException(
                    "notificationVariable.name", "must use a stable lower-camel identifier");
        }
        type = Objects.requireNonNull(type, "type");
        if (maximumLength < 1 || maximumLength > 4_000) {
            throw new DomainValidationException(
                    "notificationVariable.maximumLength", "must be between 1 and 4000");
        }
        trustedOrigins = Set.copyOf(Objects.requireNonNull(trustedOrigins, "trustedOrigins"));
        if ((type == NotificationVariableType.TRUSTED_LINK) != !trustedOrigins.isEmpty()) {
            throw new DomainValidationException(
                    "notificationVariable.trustedOrigins",
                    "must exist exactly for TRUSTED_LINK variables");
        }
    }

    public static NotificationVariableSpec text(String name, int maximumLength) {
        return new NotificationVariableSpec(
                name, NotificationVariableType.TEXT, maximumLength, Set.of());
    }

    public static NotificationVariableSpec trustedLink(
            String name, int maximumLength, Set<TrustedNotificationOrigin> origins) {
        return new NotificationVariableSpec(
                name, NotificationVariableType.TRUSTED_LINK, maximumLength, origins);
    }

    void validate(String value) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new DomainValidationException(
                    "notificationVariables." + name, "must be non-blank and within its length limit");
        }
        if (value.codePoints().anyMatch(NotificationVariableSpec::isForbiddenControl)) {
            throw new DomainValidationException(
                    "notificationVariables." + name, "must not contain control characters");
        }
        if (type == NotificationVariableType.TRUSTED_LINK
                && trustedOrigins.stream().noneMatch(origin -> origin.supports(value))) {
            throw new DomainValidationException(
                    "notificationVariables." + name, "must use a trusted HTTPS origin");
        }
    }

    private static boolean isForbiddenControl(int codePoint) {
        return Character.isISOControl(codePoint)
                || Character.getType(codePoint) == Character.FORMAT;
    }
}
