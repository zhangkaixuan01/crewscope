package io.crewscope.infrastructure.workspace.repository;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-protected opaque cursor bound to Workspace, epoch, sequence and generation. */
final class WorkspaceDiffCursorCodec {

    private static final int KEY_BYTES = 32;
    private final SecretKeySpec key;

    WorkspaceDiffCursorCodec(byte[] secret) {
        byte[] copied = Objects.requireNonNull(secret, "secret").clone();
        if (copied.length < KEY_BYTES) {
            throw new IllegalArgumentException("Diff cursor secret must contain at least 32 bytes");
        }
        this.key = new SecretKeySpec(copied, "HmacSHA256");
    }

    String encode(Cursor cursor) {
        byte[] payload = Objects.requireNonNull(cursor, "cursor")
                .payload()
                .getBytes(StandardCharsets.UTF_8);
        return base64(payload) + "." + base64(mac(payload));
    }

    Cursor decode(String token) {
        try {
            String value = Objects.requireNonNull(token, "token");
            String[] parts = value.split("\\.", -1);
            if (parts.length != 2 || value.length() > 1_024) {
                throw invalidCursor();
            }
            byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
            byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
            // Reject non-canonical Base64URL aliases as well as invalid MACs. Without this
            // check, changing unused bits in the final character can preserve decoded bytes.
            if (!parts[0].equals(base64(payload))
                    || !parts[1].equals(base64(signature))
                    || !MessageDigest.isEqual(signature, mac(payload))) {
                throw invalidCursor();
            }
            return Cursor.parse(new String(payload, StandardCharsets.UTF_8));
        } catch (WorkspaceDiffException failure) {
            throw failure;
        } catch (RuntimeException invalid) {
            throw invalidCursor();
        }
    }

    private byte[] mac(byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(value);
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("HmacSHA256 is unavailable", unavailable);
        }
    }

    private static String base64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static WorkspaceDiffException invalidCursor() {
        return new WorkspaceDiffException(
                WorkspaceDiffError.INVALID_CURSOR, "Diff cursor is invalid");
    }

    record Cursor(UUID workspaceId, UUID epoch, long sequence, long generation) {

        Cursor {
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(epoch, "epoch");
            if (sequence < 1 || generation < 1) {
                throw invalidCursor();
            }
        }

        String payload() {
            return "1|" + workspaceId + "|" + epoch + "|" + sequence + "|" + generation;
        }

        static Cursor parse(String value) {
            String[] fields = value.split("\\|", -1);
            if (fields.length != 5 || !"1".equals(fields[0])) {
                throw invalidCursor();
            }
            return new Cursor(
                    UUID.fromString(fields[1]),
                    UUID.fromString(fields[2]),
                    Long.parseLong(fields[3]),
                    Long.parseLong(fields[4]));
        }
    }
}
