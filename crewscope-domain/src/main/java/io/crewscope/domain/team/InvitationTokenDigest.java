package io.crewscope.domain.team;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/** Fixed SHA-256/HMAC-sized invitation token digest whose string representation is always redacted. */
public final class InvitationTokenDigest {

    public static final int BYTE_LENGTH = 32;
    public static final int HEX_LENGTH = BYTE_LENGTH * 2;
    private static final HexFormat HEX = HexFormat.of();

    private final String encodedValue;

    private InvitationTokenDigest(String encodedValue) {
        this.encodedValue = requireEncodedValue(encodedValue);
    }

    public static InvitationTokenDigest fromHex(String encodedValue) {
        return new InvitationTokenDigest(encodedValue);
    }

    public static InvitationTokenDigest fromBytes(byte[] digest) {
        byte[] required = Objects.requireNonNull(digest, "digest").clone();
        if (required.length != BYTE_LENGTH) {
            throw new DomainValidationException(
                    "teamInvitation.tokenDigest", "must contain exactly 32 bytes");
        }
        return new InvitationTokenDigest(HEX.formatHex(required));
    }

    /** Returns the digest encoding only for persistence and indexed lookup adapters. */
    public String valueForPersistence() {
        return encodedValue;
    }

    /** Compares already-derived digests without an early-exit string comparison. */
    public boolean matches(InvitationTokenDigest presented) {
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                encodedValue.getBytes(StandardCharsets.US_ASCII),
                presented.encodedValue.getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof InvitationTokenDigest digest && matches(digest);
    }

    @Override
    public int hashCode() {
        return encodedValue.hashCode();
    }

    @Override
    public String toString() {
        return "[REDACTED]";
    }

    private static String requireEncodedValue(String value) {
        if (value == null
                || value.length() != HEX_LENGTH
                || value.chars().anyMatch(character -> !isLowerHex(character))) {
            throw new DomainValidationException(
                    "teamInvitation.tokenDigest",
                    "must be a 64-character lowercase hexadecimal digest");
        }
        return value;
    }

    private static boolean isLowerHex(int value) {
        return value >= '0' && value <= '9' || value >= 'a' && value <= 'f';
    }
}
