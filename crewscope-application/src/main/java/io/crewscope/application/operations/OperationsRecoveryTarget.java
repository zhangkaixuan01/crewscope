package io.crewscope.application.operations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Closed set of strongly versioned recovery targets; no arbitrary resource name is accepted. */
public sealed interface OperationsRecoveryTarget
        permits OutboxDeadLetterRecoveryTarget,
                ProjectionDeadLetterRecoveryTarget,
                NotificationDeliveryRecoveryTarget {

    OperationsRecoveryAction action();

    List<String> fingerprintCoordinates();

    String confirmationToken();

    default String referenceHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String coordinate : fingerprintCoordinates()) {
                byte[] bytes = coordinate.getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
