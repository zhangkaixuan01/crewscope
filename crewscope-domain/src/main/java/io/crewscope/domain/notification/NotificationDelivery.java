package io.crewscope.domain.notification;

import io.crewscope.domain.action.ActionDigest;
import io.crewscope.domain.action.PlannedActionId;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Optimistically versioned notification state machine with explicit uncertainty recovery. */
public final class NotificationDelivery {

    private final NotificationDeliveryId id;
    private final PlannedActionId actionId;
    private final ActionDigest actionDigest;
    private final NotificationDeduplicationKey deduplicationKey;
    private final Optional<NotificationDeliveryId> redeliveryOf;
    private final NotificationDeliveryStatus status;
    private final int attemptCount;
    private final Optional<UtcTimestamp> nextAttemptAt;
    private final Optional<NotificationInvalidationReason> invalidationReason;
    private final Optional<NotificationReceipt> receipt;
    private final UtcTimestamp createdAt;
    private final UtcTimestamp updatedAt;
    private final long version;

    private NotificationDelivery(
            NotificationDeliveryId id,
            PlannedActionId actionId,
            ActionDigest actionDigest,
            NotificationDeduplicationKey deduplicationKey,
            Optional<NotificationDeliveryId> redeliveryOf,
            NotificationDeliveryStatus status,
            int attemptCount,
            Optional<UtcTimestamp> nextAttemptAt,
            Optional<NotificationInvalidationReason> invalidationReason,
            Optional<NotificationReceipt> receipt,
            UtcTimestamp createdAt,
            UtcTimestamp updatedAt,
            long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.actionId = Objects.requireNonNull(actionId, "actionId");
        this.actionDigest = Objects.requireNonNull(actionDigest, "actionDigest");
        this.deduplicationKey = Objects.requireNonNull(deduplicationKey, "deduplicationKey");
        this.redeliveryOf = Objects.requireNonNull(redeliveryOf, "redeliveryOf");
        this.status = Objects.requireNonNull(status, "status");
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        this.invalidationReason = Objects.requireNonNull(invalidationReason, "invalidationReason");
        this.receipt = Objects.requireNonNull(receipt, "receipt");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (attemptCount < 0 || version < 0 || updatedAt.compareTo(createdAt) < 0) {
            throw new DomainValidationException(
                    "notificationDelivery", "contains an invalid counter, version or timestamp");
        }
        this.attemptCount = attemptCount;
        this.version = version;
        validateShape();
    }

    public static NotificationDelivery ready(
            NotificationPlannedAction action, UtcTimestamp createdAt) {
        NotificationPlannedAction required = Objects.requireNonNull(action, "action");
        if (required.status() != NotificationPlannedActionStatus.PLANNED) {
            throw new DomainValidationException(
                    "notificationDelivery.action", "must be PLANNED");
        }
        return new NotificationDelivery(
                NotificationDeliveryId.fromDeduplicationKey(
                        required.authority().deduplicationKey()),
                required.id(),
                required.digest(),
                required.authority().deduplicationKey(),
                required.redeliveryOf(),
                NotificationDeliveryStatus.READY,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                createdAt,
                createdAt,
                0);
    }

    /** Rebuilds a persisted delivery while preserving state-machine shape validation. */
    public static NotificationDelivery reconstitute(
            NotificationDeliveryId id,
            PlannedActionId actionId,
            ActionDigest actionDigest,
            NotificationDeduplicationKey deduplicationKey,
            Optional<NotificationDeliveryId> redeliveryOf,
            NotificationDeliveryStatus status,
            int attemptCount,
            Optional<UtcTimestamp> nextAttemptAt,
            Optional<NotificationInvalidationReason> invalidationReason,
            Optional<NotificationReceipt> receipt,
            UtcTimestamp createdAt,
            UtcTimestamp updatedAt,
            long version) {
        if (!NotificationDeliveryId.fromDeduplicationKey(deduplicationKey).equals(id)) {
            throw new DomainValidationException(
                    "notificationDelivery.id", "does not match its deduplication key");
        }
        NotificationDelivery delivery = new NotificationDelivery(
                id, actionId, actionDigest, deduplicationKey, redeliveryOf, status, attemptCount,
                nextAttemptAt, invalidationReason, receipt, createdAt, updatedAt, version);
        receipt.ifPresent(value -> {
            if (!delivery.actionId.equals(value.actionId())
                    || !delivery.actionDigest.equals(value.actionDigest())
                    || !delivery.deduplicationKey.equals(value.deduplicationKey())) {
                throw new DomainValidationException(
                        "notificationDelivery.receipt", "must bind the exact persisted delivery");
            }
        });
        return delivery;
    }

    public NotificationDelivery start(
            long expectedVersion, NotificationPlannedAction action, UtcTimestamp now) {
        requireVersionAndAction(expectedVersion, action);
        requireStatus(NotificationDeliveryStatus.READY, NotificationDeliveryStatus.RETRY_WAIT);
        UtcTimestamp requiredNow = Objects.requireNonNull(now, "now");
        if (nextAttemptAt.filter(readyAt -> readyAt.compareTo(requiredNow) > 0).isPresent()
                || requiredNow.compareTo(action.notBefore()) < 0
                || requiredNow.compareTo(action.validUntil()) >= 0) {
            throw new IllegalStateException("Notification delivery is not currently executable");
        }
        return transition(
                NotificationDeliveryStatus.RUNNING, attemptCount + 1, Optional.empty(),
                Optional.empty(), Optional.empty(), requiredNow);
    }

    public NotificationDelivery retryWait(
            long expectedVersion, UtcTimestamp retryAt, UtcTimestamp now) {
        requireVersion(expectedVersion);
        requireStatus(NotificationDeliveryStatus.RUNNING, NotificationDeliveryStatus.RECONCILING);
        UtcTimestamp requiredNow = Objects.requireNonNull(now, "now");
        UtcTimestamp requiredRetryAt = Objects.requireNonNull(retryAt, "retryAt");
        if (requiredRetryAt.compareTo(requiredNow) <= 0) {
            throw new DomainValidationException(
                    "notificationDelivery.nextAttemptAt", "must be in the future");
        }
        return transition(
                NotificationDeliveryStatus.RETRY_WAIT, attemptCount, Optional.of(requiredRetryAt),
                Optional.empty(), Optional.empty(), requiredNow);
    }

    /** Timeout or response loss keeps the side effect uncertain until query reconciliation. */
    public NotificationDelivery markUnknown(long expectedVersion, UtcTimestamp now) {
        requireVersion(expectedVersion);
        requireStatus(NotificationDeliveryStatus.RUNNING);
        return transition(
                NotificationDeliveryStatus.UNKNOWN, attemptCount, Optional.empty(),
                Optional.empty(), Optional.empty(), now);
    }

    public NotificationDelivery beginReconciliation(long expectedVersion, UtcTimestamp now) {
        requireVersion(expectedVersion);
        requireStatus(NotificationDeliveryStatus.UNKNOWN);
        return transition(
                NotificationDeliveryStatus.RECONCILING, attemptCount, Optional.empty(),
                Optional.empty(), Optional.empty(), now);
    }

    /** Keeps an uncertain result query-only when the Provider query itself is inconclusive. */
    public NotificationDelivery deferReconciliation(long expectedVersion, UtcTimestamp now) {
        requireVersion(expectedVersion);
        requireStatus(NotificationDeliveryStatus.RECONCILING);
        return transition(
                NotificationDeliveryStatus.UNKNOWN, attemptCount, Optional.empty(),
                Optional.empty(), Optional.empty(), now);
    }

    /** Re-fences an expired query claim without permitting another external write. */
    public NotificationDelivery reclaimReconciliation(long expectedVersion, UtcTimestamp now) {
        requireVersion(expectedVersion);
        requireStatus(NotificationDeliveryStatus.RECONCILING);
        return transition(
                NotificationDeliveryStatus.RECONCILING, attemptCount, Optional.empty(),
                Optional.empty(), Optional.empty(), now);
    }

    public NotificationDelivery succeed(
            long expectedVersion, NotificationReceipt acceptedReceipt) {
        requireVersion(expectedVersion);
        requireStatus(NotificationDeliveryStatus.RUNNING, NotificationDeliveryStatus.RECONCILING);
        NotificationReceipt required = requireReceipt(
                acceptedReceipt, NotificationReceiptResult.ACCEPTED);
        return transition(
                NotificationDeliveryStatus.SUCCEEDED, attemptCount, Optional.empty(),
                Optional.empty(), Optional.of(required), required.receivedAt());
    }

    public NotificationDelivery failFinal(
            long expectedVersion, NotificationReceipt failedReceipt) {
        requireVersion(expectedVersion);
        requireStatus(NotificationDeliveryStatus.RUNNING, NotificationDeliveryStatus.RECONCILING);
        NotificationReceipt required = requireReceipt(
                failedReceipt, NotificationReceiptResult.FAILED_FINAL);
        return transition(
                NotificationDeliveryStatus.FAILED_FINAL, attemptCount, Optional.empty(),
                Optional.empty(), Optional.of(required), required.receivedAt());
    }

    public NotificationDelivery invalidate(
            long expectedVersion,
            NotificationInvalidationReason reason,
            NotificationReceipt invalidatedReceipt) {
        requireVersion(expectedVersion);
        if (status.terminal()) {
            throw new IllegalStateException("Terminal notification delivery is immutable");
        }
        NotificationInvalidationReason requiredReason = Objects.requireNonNull(reason, "reason");
        NotificationReceipt requiredReceipt = requireReceipt(
                invalidatedReceipt, NotificationReceiptResult.INVALIDATED);
        return transition(
                NotificationDeliveryStatus.INVALIDATED, attemptCount, Optional.empty(),
                Optional.of(requiredReason), Optional.of(requiredReceipt), requiredReceipt.receivedAt());
    }

    public NotificationDelivery cancel(
            long expectedVersion, NotificationReceipt cancelledReceipt) {
        requireVersion(expectedVersion);
        requireStatus(NotificationDeliveryStatus.READY, NotificationDeliveryStatus.RETRY_WAIT);
        NotificationReceipt required = requireReceipt(
                cancelledReceipt, NotificationReceiptResult.CANCELLED);
        return transition(
                NotificationDeliveryStatus.CANCELLED, attemptCount, Optional.empty(),
                Optional.empty(), Optional.of(required), required.receivedAt());
    }

    private NotificationDelivery transition(
            NotificationDeliveryStatus nextStatus,
            int nextAttemptCount,
            Optional<UtcTimestamp> nextAttempt,
            Optional<NotificationInvalidationReason> nextReason,
            Optional<NotificationReceipt> nextReceipt,
            UtcTimestamp now) {
        return new NotificationDelivery(
                id, actionId, actionDigest, deduplicationKey, redeliveryOf, nextStatus,
                nextAttemptCount, nextAttempt, nextReason, nextReceipt, createdAt,
                Objects.requireNonNull(now, "now"), version + 1);
    }

    private NotificationReceipt requireReceipt(
            NotificationReceipt value, NotificationReceiptResult expectedResult) {
        NotificationReceipt required = Objects.requireNonNull(value, "receipt");
        if (!id.equals(required.deliveryId())
                || !actionId.equals(required.actionId())
                || !actionDigest.equals(required.actionDigest())
                || !deduplicationKey.equals(required.deduplicationKey())
                || required.result() != expectedResult) {
            throw new DomainValidationException(
                    "notificationDelivery.receipt", "must bind the exact delivery terminal result");
        }
        return required;
    }

    private void requireVersionAndAction(long expectedVersion, NotificationPlannedAction action) {
        requireVersion(expectedVersion);
        NotificationPlannedAction required = Objects.requireNonNull(action, "action");
        if (!actionId.equals(required.id())
                || !actionDigest.equals(required.digest())
                || required.status() != NotificationPlannedActionStatus.PLANNED) {
            throw new DomainValidationException(
                    "notificationDelivery.action", "must be the exact current planned action");
        }
    }

    private void requireVersion(long expectedVersion) {
        if (expectedVersion != version) {
            throw new IllegalStateException("Notification delivery version conflict");
        }
    }

    private void requireStatus(NotificationDeliveryStatus... allowed) {
        for (NotificationDeliveryStatus candidate : allowed) {
            if (status == candidate) {
                return;
            }
        }
        throw new IllegalStateException("Notification delivery transition is not allowed from " + status);
    }

    private void validateShape() {
        if ((status == NotificationDeliveryStatus.RETRY_WAIT) != nextAttemptAt.isPresent()
                || (status == NotificationDeliveryStatus.INVALIDATED) != invalidationReason.isPresent()
                || status.terminal() != receipt.isPresent()) {
            throw new DomainValidationException(
                    "notificationDelivery.status", "does not match its state-specific fields");
        }
        receipt.ifPresent(value -> {
            if (!id.equals(value.deliveryId())) {
                throw new DomainValidationException(
                        "notificationDelivery.receipt", "must belong to this delivery");
            }
        });
    }

    public NotificationDeliveryId id() { return id; }
    public PlannedActionId actionId() { return actionId; }
    public ActionDigest actionDigest() { return actionDigest; }
    public NotificationDeduplicationKey deduplicationKey() { return deduplicationKey; }
    public Optional<NotificationDeliveryId> redeliveryOf() { return redeliveryOf; }
    public NotificationDeliveryStatus status() { return status; }
    public int attemptCount() { return attemptCount; }
    public Optional<UtcTimestamp> nextAttemptAt() { return nextAttemptAt; }
    public Optional<NotificationInvalidationReason> invalidationReason() { return invalidationReason; }
    public Optional<NotificationReceipt> receipt() { return receipt; }
    public UtcTimestamp createdAt() { return createdAt; }
    public UtcTimestamp updatedAt() { return updatedAt; }
    public long version() { return version; }
}
