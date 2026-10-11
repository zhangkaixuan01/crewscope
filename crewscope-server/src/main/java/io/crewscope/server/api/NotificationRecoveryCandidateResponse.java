package io.crewscope.server.api;

/** Notification delivery retry coordinates with the exact strong confirmation phrase. */
public record NotificationRecoveryCandidateResponse(
        String type,
        String action,
        String deliveryId,
        long expectedVersion,
        String referenceHash,
        String confirmation) implements RecoveryCandidateResponse {}
