package io.crewscope.domain.identity;

import io.crewscope.domain.shared.error.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Frozen derivation rules (ADR-036) turning one trusted external subject into the account
 * coordinates of its first CrewScope login. Every fallback is deterministic: usernames never
 * use random entropy, so identical claims always derive identical identities, and an unusable
 * preferred_username degrades to the subject digest instead of failing the login.
 */
public record ExternalAccountIdentity(
        String registrationId,
        String subject,
        Optional<String> name,
        Optional<String> preferredUsername,
        Optional<String> email,
        boolean emailVerified) {

    private static final int DERIVED_USERNAME_HASH_LENGTH = 12;
    private static final int MAX_DERIVATION_ATTEMPTS = 3;
    /** RFC 2606 reserved domain: synthesized addresses can never collide with a real mailbox. */
    private static final String UNVERIFIED_EMAIL_DOMAIN = "@sso.oidc.invalid";

    public ExternalAccountIdentity {
        registrationId = requireText(registrationId, "registrationId", 64);
        subject = requireText(subject, "subject", 255);
        name = requireClaim(name, "name");
        preferredUsername = requireClaim(preferredUsername, "preferredUsername");
        email = requireClaim(email, "email");
    }

    /** The provider key this identity persists under: {@code oidc/<registrationId>}. */
    public IdentityProviderKey provider() {
        return new IdentityProviderKey("oidc/" + registrationId);
    }

    /** The trusted identity key: provider plus the immutable subject claim. */
    public LoginIdentityKey identityKey() {
        return new LoginIdentityKey(provider(), new LoginIdentitySubject(subject));
    }

    /** Display name follows the first non-empty claim; the subject is the final backstop. */
    public String displayName() {
        return name.or(() -> preferredUsername)
                .or(() -> email)
                .filter(value -> !value.isBlank())
                .orElse(subject);
    }

    /** Only an IdP-verified email is adopted; otherwise the derived username synthesizes one. */
    public String email(Username derivedUsername) {
        if (emailVerified) {
            Optional<String> verified = email.filter(value -> !value.isBlank());
            if (verified.isPresent()) {
                return verified.orElseThrow();
            }
        }
        return derivedUsername.displayValue() + UNVERIFIED_EMAIL_DOMAIN;
    }

    /**
     * Username of derivation attempt {@code 0..3}: attempt 0 prefers a usable
     * {@code preferred_username}; every other attempt re-derives deterministically from the
     * provider, subject and attempt ordinal.
     */
    public Username username(int attempt) {
        if (attempt < 0 || attempt > MAX_DERIVATION_ATTEMPTS) {
            throw new IllegalArgumentException("derivation attempt is out of range");
        }
        if (attempt == 0) {
            Optional<Username> preferred = preferredUsername
                    .filter(value -> !value.isBlank())
                    .flatMap(ExternalAccountIdentity::tryUsername);
            if (preferred.isPresent()) {
                return preferred.orElseThrow();
            }
        }
        return new Username("sso-" + digestHex(
                "oidc/" + registrationId + "|" + subject + "|" + attempt,
                DERIVED_USERNAME_HASH_LENGTH));
    }

    /** Highest attempt index; exhausting it fails closed at the caller. */
    public static int maximumDerivationAttempts() {
        return MAX_DERIVATION_ATTEMPTS;
    }

    private static Optional<Username> tryUsername(String candidate) {
        try {
            return Optional.of(new Username(candidate));
        } catch (DomainException | IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private static String digestHex(String preimage, int characters) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(preimage.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, characters / 2 + characters % 2)
                    .substring(0, characters);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static String requireText(String value, String field, int maxLength) {
        String required = Objects.requireNonNull(value, field).strip();
        if (required.isEmpty() || required.length() > maxLength) {
            throw new IllegalArgumentException(field + " is blank or too long");
        }
        return required;
    }

    private static Optional<String> requireClaim(Optional<String> value, String field) {
        Optional<String> required = Objects.requireNonNull(value, field);
        required.ifPresent(claimed -> {
            if (claimed.length() > 256 || claimed.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException(field + " is too long or contains control characters");
            }
        });
        return required;
    }
}
