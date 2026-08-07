package io.crewscope.domain.identity;

import io.crewscope.domain.shared.error.DomainValidationException;

/** Canonical identity-provider and subject pair for Bootstrap or OIDC mapping. */
public record ExternalIdentity(String provider, String subject) {

    public static final int MAX_PROVIDER_LENGTH = 100;
    public static final int MAX_SUBJECT_LENGTH = 500;

    public ExternalIdentity {
        provider = requireText(provider, "principal.externalIdentity.provider", MAX_PROVIDER_LENGTH);
        subject = requireText(subject, "principal.externalIdentity.subject", MAX_SUBJECT_LENGTH);
    }

    private static String requireText(String value, String field, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException(field, "must not be blank");
        }
        String normalized = value.strip();
        if (normalized.length() > maximumLength) {
            throw new DomainValidationException(
                    field, "must contain at most " + maximumLength + " characters");
        }
        return normalized;
    }
}
