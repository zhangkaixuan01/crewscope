package io.crewscope.infrastructure.security.invitation;

import io.crewscope.application.team.InvitationToken;
import io.crewscope.application.team.InvitationTokenDigester;
import io.crewscope.domain.team.InvitationTokenDigest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Purpose-separated HMAC-SHA256 derivation for invitation token lookup digests. */
public final class HmacSha256InvitationTokenDigester implements InvitationTokenDigester {

    private static final String ALGORITHM = "HmacSHA256";
    private static final byte[] PURPOSE =
            "crewscope:team-invitation-token:v1\0".getBytes(StandardCharsets.UTF_8);

    private final byte[] secret;

    public HmacSha256InvitationTokenDigester(String base64Secret) {
        try {
            secret = Base64.getDecoder().decode(
                    Objects.requireNonNull(base64Secret, "base64Secret"));
        } catch (IllegalArgumentException malformed) {
            throw new IllegalStateException("Invitation token HMAC key must be valid Base64");
        }
        if (secret.length < 32) {
            throw new IllegalStateException(
                    "Invitation token HMAC key must contain at least 32 bytes");
        }
    }

    @Override
    public InvitationTokenDigest digest(InvitationToken token) {
        InvitationToken required = Objects.requireNonNull(token, "token");
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            mac.update(PURPOSE);
            return InvitationTokenDigest.fromBytes(
                    mac.doFinal(required.reveal().getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("Invitation token HMAC is unavailable");
        }
    }
}
