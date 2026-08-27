package io.crewscope.server.api;

import io.crewscope.application.notification.NotificationDeliveryCursor;
import io.crewscope.application.notification.NotificationDeliveryFilter;
import io.crewscope.domain.notification.NotificationDeliveryId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;

/** HMAC Cursor binding a delivery keyset position to the exact Team and normalized filters. */
public final class NotificationDeliveryCursorCodec {

    private static final String DOMAIN = "crewscope:notification-delivery-cursor:v1";
    private final TeamActivityCursorKeyRing keys;

    public NotificationDeliveryCursorCodec(TeamActivityCursorKeyRing keys) {
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    public String encode(
            NotificationDeliveryCursor cursor,
            OrganizationId organizationId,
            TeamId teamId,
            NotificationDeliveryFilter filter) {
        String body = String.join("|", "1", keys.currentKeyId(), organizationId.toString(),
                teamId.toString(), fingerprint(filter), cursor.updatedAt().toString(),
                cursor.deliveryId().toString());
        return token(body, sign(body, keys.currentKey()));
    }

    public NotificationDeliveryCursor decode(
            String token,
            OrganizationId organizationId,
            TeamId teamId,
            NotificationDeliveryFilter filter) {
        Parsed parsed = parse(token);
        String[] values = parsed.body().split("\\|", -1);
        if (values.length != 7 || !"1".equals(values[0])) throw invalid();
        byte[] key = keys.key(values[1]);
        if (key == null || !MessageDigest.isEqual(parsed.signature(), sign(parsed.body(), key))
                || !organizationId.toString().equals(values[2])
                || !teamId.toString().equals(values[3])
                || !fingerprint(filter).equals(values[4])) throw invalid();
        try {
            return new NotificationDeliveryCursor(
                    UtcTimestamp.from(Instant.parse(values[5])),
                    new NotificationDeliveryId(UUID.fromString(values[6])));
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private static String fingerprint(NotificationDeliveryFilter filter) {
        String value = filter.statuses().stream().map(Enum::name).sorted()
                        .collect(java.util.stream.Collectors.joining(","))
                + "|" + filter.itemTypes().stream().map(Enum::name).sorted()
                        .collect(java.util.stream.Collectors.joining(","))
                + "|" + filter.recipientMemberId().map(Object::toString).orElse("");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static Parsed parse(String value) {
        if (value == null || value.isBlank() || value.length() > 1024) throw invalid();
        try {
            String joined = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
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
                "Cursor is invalid or belongs to another notification delivery query",
                Map.of("parameter", "after"));
    }

    private record Parsed(String body, byte[] signature) {}
}
