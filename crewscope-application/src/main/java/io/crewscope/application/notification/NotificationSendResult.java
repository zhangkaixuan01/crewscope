package io.crewscope.application.notification;

import io.crewscope.domain.notification.NotificationFailureCode;
import java.util.Objects;
import java.util.Optional;

/** Normalized write result; UNKNOWN is reserved for a possibly accepted Provider request. */
public record NotificationSendResult(
        Kind kind,
        Optional<String> providerReference,
        Optional<String> providerMessageId,
        Optional<NotificationFailureCode> failureCode,
        String evidenceCode) {

    public enum Kind { ACCEPTED, RETRYABLE, UNKNOWN, FAILED_FINAL }

    public NotificationSendResult {
        kind = Objects.requireNonNull(kind, "kind");
        providerReference = Objects.requireNonNull(providerReference, "providerReference");
        providerMessageId = Objects.requireNonNull(providerMessageId, "providerMessageId");
        failureCode = Objects.requireNonNull(failureCode, "failureCode");
        evidenceCode = requireEvidence(evidenceCode);
        boolean accepted = kind == Kind.ACCEPTED;
        if (accepted != (providerReference.isPresent() && providerMessageId.isPresent())
                || (kind == Kind.FAILED_FINAL) != failureCode.isPresent()) {
            throw new IllegalArgumentException("Notification send result shape is invalid");
        }
        if (!accepted && (providerReference.isPresent() || providerMessageId.isPresent())) {
            throw new IllegalArgumentException("Only accepted sends may expose receipt coordinates");
        }
        providerReference.ifPresent(value -> requireProviderCoordinate(value, "providerReference"));
        providerMessageId.ifPresent(value -> requireProviderCoordinate(value, "providerMessageId"));
    }

    public static NotificationSendResult accepted(
            String providerReference, String providerMessageId, String evidenceCode) {
        return new NotificationSendResult(
                Kind.ACCEPTED, Optional.of(providerReference), Optional.of(providerMessageId),
                Optional.empty(), evidenceCode);
    }

    public static NotificationSendResult retryable(String evidenceCode) {
        return simple(Kind.RETRYABLE, Optional.empty(), evidenceCode);
    }

    public static NotificationSendResult unknown(String evidenceCode) {
        return simple(Kind.UNKNOWN, Optional.empty(), evidenceCode);
    }

    public static NotificationSendResult failed(
            NotificationFailureCode failureCode, String evidenceCode) {
        return simple(Kind.FAILED_FINAL, Optional.of(failureCode), evidenceCode);
    }

    private static NotificationSendResult simple(
            Kind kind, Optional<NotificationFailureCode> failure, String evidence) {
        return new NotificationSendResult(
                kind, Optional.empty(), Optional.empty(), failure, evidence);
    }

    static String requireEvidence(String value) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_]{2,63}")) {
            throw new IllegalArgumentException("Evidence code must be stable and non-sensitive");
        }
        return value;
    }

    static String requireProviderCoordinate(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 512
                || value.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException(field + " is outside its safe in-memory bounds");
        }
        return value;
    }

    @Override
    public String toString() {
        return "NotificationSendResult[kind=" + kind + ", providerCoordinates=REDACTED, "
                + "failureCode=" + failureCode + ", evidenceCode=" + evidenceCode + ']';
    }
}
