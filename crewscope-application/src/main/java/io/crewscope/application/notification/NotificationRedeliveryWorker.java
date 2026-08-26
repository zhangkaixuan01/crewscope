package io.crewscope.application.notification;

import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.TimeProvider;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Converts audited manual recovery schedules into idempotent new Notification Deliveries. */
public final class NotificationRedeliveryWorker {

    private final NotificationRecoveryScheduleRepository schedules;
    private final NotificationPlanningApplicationService planning;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;
    private final NotificationWorkerId workerId;
    private final Duration leaseDuration;
    private final int batchSize;

    public NotificationRedeliveryWorker(
            NotificationRecoveryScheduleRepository schedules,
            NotificationPlanningApplicationService planning,
            TransactionExecutor transactions,
            TimeProvider timeProvider,
            NotificationWorkerId workerId,
            Duration leaseDuration,
            int batchSize) {
        this.schedules = Objects.requireNonNull(schedules, "schedules");
        this.planning = Objects.requireNonNull(planning, "planning");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.compareTo(Duration.ofSeconds(5)) < 0
                || leaseDuration.compareTo(Duration.ofMinutes(10)) > 0
                || batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("Notification redelivery Worker settings are invalid");
        }
        this.batchSize = batchSize;
    }

    public int runOnce() {
        int completed = 0;
        for (OrganizationId organization : schedules.findOrganizations(timeProvider.now(), batchSize)) {
            completed += runOnce(organization);
        }
        return completed;
    }

    public int runOnce(OrganizationId organizationId) {
        int completed = 0;
        for (int index = 0; index < batchSize; index++) {
            Optional<NotificationRecoveryClaim> claimed = transactions.required(
                    () -> schedules.claim(
                            organizationId, workerId, timeProvider.now(), leaseDuration));
            if (claimed.isEmpty()) {
                break;
            }
            NotificationRecoveryClaim claim = claimed.orElseThrow();
            // The command ID makes plan creation replay-safe if completion acknowledgement is lost.
            NotificationRedeliveryRecord redelivery = planning.redeliverScheduled(
                    claim.commandId(), claim.organizationId(), claim.originalDeliveryId(),
                    claim.expectedDeliveryVersion());
            transactions.required(() -> {
                schedules.complete(claim, redelivery.plan().delivery().id(), timeProvider.now());
                return null;
            });
            completed++;
        }
        return completed;
    }
}
