package io.crewscope.server.api;

import io.crewscope.application.collaboration.LarkMemberMappingCursor;
import io.crewscope.domain.collaboration.LarkMemberMappingId;
import io.crewscope.domain.collaboration.LarkMemberMappingStatus;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;

/** HMAC Cursor binding mapping position to the exact Team and status filter. */
public final class LarkMappingCursorCodec {

    private static final String DOMAIN = "crewscope:lark-mapping-cursor:v1";
    private final TeamActivityCursorKeyRing keys;

    public LarkMappingCursorCodec(TeamActivityCursorKeyRing keys) {
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    public String encode(
            LarkMemberMappingCursor cursor,
            OrganizationId organizationId,
            TeamId teamId,
            Optional<LarkMemberMappingStatus> status) {
        String body = String.join("|", "1", keys.currentKeyId(), organizationId.toString(),
                teamId.toString(), status.map(Enum::name).orElse("*"),
                cursor.updatedAt().toString(), cursor.mappingId().toString());
        return token(body, sign(body, keys.currentKey()));
    }

    public LarkMemberMappingCursor decode(
            String token,
            OrganizationId organizationId,
            TeamId teamId,
            Optional<LarkMemberMappingStatus> status) {
        Parsed parsed = parse(token);
        String[] values = parsed.body().split("\\|", -1);
        if (values.length != 7 || !"1".equals(values[0])) throw invalid();
        byte[] key = keys.key(values[1]);
        if (key == null || !MessageDigest.isEqual(parsed.signature(), sign(parsed.body(), key))
                || !organizationId.toString().equals(values[2])
                || !teamId.toString().equals(values[3])
                || !status.map(Enum::name).orElse("*").equals(values[4])) throw invalid();
        try {
            return new LarkMemberMappingCursor(
                    UtcTimestamp.from(Instant.parse(values[5])),
                    new LarkMemberMappingId(UUID.fromString(values[6])));
        } catch (RuntimeException failure) {
            // UUID and timestamp parsing remain inside the safe cursor error boundary.
            throw invalid();
        }
    }

    private Parsed parse(String value) {
        if (value == null || value.isBlank() || value.length() > 1024) throw invalid();
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            String joined = new String(decoded, StandardCharsets.UTF_8);
            int separator = joined.lastIndexOf('.');
            if (separator < 1) throw invalid();
            return new Parsed(joined.substring(0, separator),
                    Base64.getUrlDecoder().decode(joined.substring(separator + 1)));
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private static String token(String body, byte[] signature) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (body + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature))
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sign(String body, byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(DOMAIN.getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("HmacSHA256 is unavailable", failure);
        }
    }

    private static ApiRequestException invalid() {
        return new ApiRequestException(HttpStatus.BAD_REQUEST, "invalid_cursor",
                "Cursor is invalid or belongs to another Lark mapping query",
                Map.of("parameter", "after"));
    }

    private record Parsed(String body, byte[] signature) {}
}
