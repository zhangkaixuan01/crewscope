package io.crewscope.infrastructure.runtime;

import io.crewscope.application.task.ClaimTokenGenerator;
import io.crewscope.domain.task.ClaimToken;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/** CSPRNG-backed generator for 256-bit base64url Claim secrets. */
public final class SecureClaimTokenGenerator implements ClaimTokenGenerator {

    private final SecureRandom secureRandom;

    public SecureClaimTokenGenerator() {
        this(new SecureRandom());
    }

    SecureClaimTokenGenerator(SecureRandom secureRandom) {
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
    }

    @Override
    public ClaimToken generate() {
        byte[] secret = new byte[32];
        secureRandom.nextBytes(secret);
        return new ClaimToken(Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
    }
}
