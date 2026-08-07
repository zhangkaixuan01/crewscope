package io.crewscope.application.command;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/** SHA-256 fingerprint of a normalized application command without retaining its request body. */
public record CommandRequestHash(String value) {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public CommandRequestHash {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException("command request hash must be lowercase SHA-256");
        }
    }

    /** Hashes length-prefixed normalized fields so boundaries cannot produce ambiguous inputs. */
    public static CommandRequestHash sha256(String commandType, String... normalizedFields) {
        MessageDigest digest = sha256Digest();
        update(digest, requireText(commandType, "commandType"));
        for (String field : Objects.requireNonNull(normalizedFields, "normalizedFields")) {
            update(digest, Objects.requireNonNull(field, "normalizedField"));
        }
        return new CommandRequestHash(HexFormat.of().formatHex(digest.digest()));
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }

    @Override
    public String toString() {
        return value;
    }
}
