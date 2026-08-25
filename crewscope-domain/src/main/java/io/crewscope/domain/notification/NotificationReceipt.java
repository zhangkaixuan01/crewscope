package io.crewscope.domain.notification;

import io.crewscope.domain.action.ActionDigest;
import io.crewscope.domain.action.PlannedActionId;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;
import java.util.Optional;

/** Immutable terminal receipt containing only stable codes and hashed provider evidence. */
public record NotificationReceipt(
        NotificationReceiptId id,
        NotificationDeliveryId deliveryId,
        PlannedActionId actionId,
        ActionDigest actionDigest,
        NotificationDeduplicationKey deduplicationKey,
        NotificationReceiptResult result,
        Optional<NotificationFailureCode> failureCode,
        Optional<NotificationProviderReceiptReference> providerReference,
        Optional<TaskFactHash> providerMessageHash,
        String evidenceCode,
        UtcTimestamp receivedAt) {

    public NotificationReceipt {
        id = Objects.requireNonNull(id, "id");
        deliveryId = Objects.requireNonNull(deliveryId, "deliveryId");
        actionId = Objects.requireNonNull(actionId, "actionId");
        actionDigest = Objects.requireNonNull(actionDigest, "actionDigest");
        deduplicationKey = Objects.requireNonNull(deduplicationKey, "deduplicationKey");
        result = Objects.requireNonNull(result, "result");
        failureCode = Objects.requireNonNull(failureCode, "failureCode");
        providerReference = Objects.requireNonNull(providerReference, "providerReference");
        providerMessageHash = Objects.requireNonNull(providerMessageHash, "providerMessageHash");
        receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        if (evidenceCode == null || !evidenceCode.matches("[A-Z][A-Z0-9_]{2,63}")) {
            throw new DomainValidationException(
                    "notificationReceipt.evidenceCode", "must be a stable non-sensitive code");
        }
        boolean accepted = result == NotificationReceiptResult.ACCEPTED;
        boolean failed = result == NotificationReceiptResult.FAILED_FINAL;
        if (accepted != (providerReference.isPresent() && providerMessageHash.isPresent())
                || failed != failureCode.isPresent()) {
            throw new DomainValidationException(
                    "notificationReceipt.result", "does not match its immutable evidence shape");
        }
        if (!accepted && (providerReference.isPresent() || providerMessageHash.isPresent())) {
            throw new DomainValidationException(
                    "notificationReceipt.providerReference", "is reserved for accepted delivery");
        }
    }

    public static NotificationReceipt accepted(
            NotificationReceiptId id,
            NotificationDelivery delivery,
            NotificationPlannedAction action,
            String providerReference,
            String providerMessageId,
            String evidenceCode,
            UtcTimestamp receivedAt) {
        requireBinding(delivery, action);
        return new NotificationReceipt(
                id,
                delivery.id(),
                action.id(),
                action.digest(),
                action.authority().deduplicationKey(),
                NotificationReceiptResult.ACCEPTED,
                Optional.empty(),
                Optional.of(NotificationProviderReceiptReference.hashed(providerReference)),
                Optional.of(TaskFactHash.sha256(
                        Objects.requireNonNull(providerMessageId, "providerMessageId"))),
                evidenceCode,
                receivedAt);
    }

    public static NotificationReceipt failed(
            NotificationReceiptId id,
            NotificationDelivery delivery,
            NotificationPlannedAction action,
            NotificationFailureCode failureCode,
            String evidenceCode,
            UtcTimestamp receivedAt) {
        requireBinding(delivery, action);
        return terminal(
                id, delivery, action, NotificationReceiptResult.FAILED_FINAL,
                Optional.of(Objects.requireNonNull(failureCode, "failureCode")), evidenceCode,
                receivedAt);
    }

    public static NotificationReceipt invalidated(
            NotificationReceiptId id,
            NotificationDelivery delivery,
            NotificationPlannedAction action,
            NotificationInvalidationReason reason,
            UtcTimestamp receivedAt) {
        Objects.requireNonNull(reason, "reason");
        requireBinding(delivery, action);
        return terminal(
                id, delivery, action, NotificationReceiptResult.INVALIDATED, Optional.empty(),
                "AUTHORIZATION_" + reason.name(), receivedAt);
    }

    public static NotificationReceipt cancelled(
            NotificationReceiptId id,
            NotificationDelivery delivery,
            NotificationPlannedAction action,
            UtcTimestamp receivedAt) {
        requireBinding(delivery, action);
        return terminal(
                id, delivery, action, NotificationReceiptResult.CANCELLED, Optional.empty(),
                "NO_PROVIDER_WRITE", receivedAt);
    }

    private static NotificationReceipt terminal(
            NotificationReceiptId id,
            NotificationDelivery delivery,
            NotificationPlannedAction action,
            NotificationReceiptResult result,
            Optional<NotificationFailureCode> failureCode,
            String evidenceCode,
            UtcTimestamp receivedAt) {
        return new NotificationReceipt(
                id,
                delivery.id(),
                action.id(),
                action.digest(),
                action.authority().deduplicationKey(),
                result,
                failureCode,
                Optional.empty(),
                Optional.empty(),
                evidenceCode,
                receivedAt);
    }

    private static void requireBinding(
            NotificationDelivery delivery, NotificationPlannedAction action) {
        NotificationDelivery requiredDelivery = Objects.requireNonNull(delivery, "delivery");
        NotificationPlannedAction requiredAction = Objects.requireNonNull(action, "action");
        if (!requiredDelivery.actionId().equals(requiredAction.id())
                || !requiredDelivery.actionDigest().equals(requiredAction.digest())
                || !requiredDelivery.deduplicationKey().equals(
                        requiredAction.authority().deduplicationKey())) {
            throw new DomainValidationException(
                    "notificationReceipt.actionId", "must bind the exact action and delivery");
        }
    }
}
