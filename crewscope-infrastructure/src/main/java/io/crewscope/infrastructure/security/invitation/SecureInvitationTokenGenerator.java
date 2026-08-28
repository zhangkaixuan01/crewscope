package io.crewscope.infrastructure.security.invitation;

import io.crewscope.application.team.InvitationToken;
import io.crewscope.application.team.InvitationTokenGenerator;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/** CSPRNG-backed generator for canonical 256-bit invitation bearer secrets. */
public final class SecureInvitationTokenGenerator implements InvitationTokenGenerator {

    private final SecureRandom random;

    public SecureInvitationTokenGenerator() {
        this(new SecureRandom());
    }

    public SecureInvitationTokenGenerator(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public InvitationToken generate() {
        byte[] entropy = new byte[InvitationToken.ENTROPY_BYTES];
        random.nextBytes(entropy);
        return new InvitationToken(
                Base64.getUrlEncoder().withoutPadding().encodeToString(entropy));
    }
}
