package io.crewscope.application.team;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Base64;
import java.util.Objects;

/** One-time 256-bit invitation bearer secret that is redacted from every string representation. */
public final class InvitationToken {

    public static final int ENTROPY_BYTES = 32;
    public static final int ENCODED_LENGTH = 43;

    private final String value;

    public InvitationToken(String value) {
        this.value = requireCanonicalToken(value);
    }

    /** Exposes plaintext only to the trusted invitation-creation response boundary. */
    public String reveal() {
        return value;
    }

    @Override
    public String toString() {
        return "[REDACTED]";
    }

    private static String requireCanonicalToken(String value) {
        String required = Objects.requireNonNull(value, "invitationToken");
        if (required.length() != ENCODED_LENGTH
                || !required.matches("[A-Za-z0-9_-]{43}")) {
            throw invalid();
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(required);
            String canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(decoded);
            if (decoded.length != ENTROPY_BYTES || !canonical.equals(required)) {
                throw invalid();
            }
            return required;
        } catch (IllegalArgumentException malformed) {
            throw invalid();
        }
    }

    private static DomainValidationException invalid() {
        return new DomainValidationException(
                "teamInvitation.token", "must be a canonical 256-bit base64url secret");
    }
}
