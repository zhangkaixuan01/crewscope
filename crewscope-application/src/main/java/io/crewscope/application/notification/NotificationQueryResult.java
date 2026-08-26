package io.crewscope.application.notification;

import io.crewscope.domain.notification.NotificationFailureCode;
import java.util.Objects;
import java.util.Optional;

/** Normalized query-only recovery result for one stable Provider idempotency key. */
public record NotificationQueryResult(
        Kind kind,
        Optional<String> providerReference,
        Optional<String> providerMessageId,
        Optional<NotificationFailureCode> failureCode,
        String evidenceCode) {

    public enum Kind { FOUND, NOT_FOUND, RETRYABLE, UNKNOWN, FAILED_FINAL }

    public NotificationQueryResult {
        kind = Objects.requireNonNull(kind, "kind");
        providerReference = Objects.requireNonNull(providerReference, "providerReference");
        providerMessageId = Objects.requireNonNull(providerMessageId, "providerMessageId");
        failureCode = Objects.requireNonNull(failureCode, "failureCode");
        evidenceCode = NotificationSendResult.requireEvidence(evidenceCode);
        boolean found = kind == Kind.FOUND;
        if (found != (providerReference.isPresent() && providerMessageId.isPresent())
                || (kind == Kind.FAILED_FINAL) != failureCode.isPresent()) {
            throw new IllegalArgumentException("Notification query result shape is invalid");
        }
        if (!found && (providerReference.isPresent() || providerMessageId.isPresent())) {
            throw new IllegalArgumentException("Only a found query may expose receipt coordinates");
        }
        providerReference.ifPresent(value -> NotificationSendResult.requireProviderCoordinate(
                value, "providerReference"));
        providerMessageId.ifPresent(value -> NotificationSendResult.requireProviderCoordinate(
                value, "providerMessageId"));
    }

    public static NotificationQueryResult found(
            String providerReference, String providerMessageId, String evidenceCode) {
        return new NotificationQueryResult(
                Kind.FOUND, Optional.of(providerReference), Optional.of(providerMessageId),
                Optional.empty(), evidenceCode);
    }

    public static NotificationQueryResult notFound(String evidenceCode) {
        return simple(Kind.NOT_FOUND, Optional.empty(), evidenceCode);
    }

    public static NotificationQueryResult retryable(String evidenceCode) {
        return simple(Kind.RETRYABLE, Optional.empty(), evidenceCode);
    }

    public static NotificationQueryResult unknown(String evidenceCode) {
        return simple(Kind.UNKNOWN, Optional.empty(), evidenceCode);
    }

    public static NotificationQueryResult failed(
            NotificationFailureCode failureCode, String evidenceCode) {
        return simple(Kind.FAILED_FINAL, Optional.of(failureCode), evidenceCode);
    }

    private static NotificationQueryResult simple(
            Kind kind, Optional<NotificationFailureCode> failure, String evidence) {
        return new NotificationQueryResult(
                kind, Optional.empty(), Optional.empty(), failure, evidence);
    }

    @Override
    public String toString() {
        return "NotificationQueryResult[kind=" + kind + ", providerCoordinates=REDACTED, "
                + "failureCode=" + failureCode + ", evidenceCode=" + evidenceCode + ']';
    }
}
