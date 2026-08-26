package io.crewscope.server.config.application;

import io.crewscope.application.notification.NotificationReconciliationWorker;
import io.crewscope.application.notification.NotificationWorkerBatchResult;
import io.crewscope.application.observability.OperationalTelemetry;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;

/** Independent query-only wake-up loop for uncertain and expired notification claims. */
final class NotificationReconciliationScheduler {

    private final NotificationReconciliationWorker worker;
    private final AtomicBoolean polling = new AtomicBoolean();
    private final OperationalTelemetry telemetry;

    NotificationReconciliationScheduler(
            NotificationReconciliationWorker worker,
            OperationalTelemetry telemetry) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    @Scheduled(
            fixedDelayString =
                    "${crewscope.notification.worker.reconciliation-poll-interval:5s}")
    void poll() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        OperationalTelemetry.Observation observation = telemetry.start(
                OperationalTelemetry.Request.notification(
                        OperationalTelemetry.Operation.RECONCILE));
        try {
            NotificationWorkerBatchResult result = worker.runOnce();
            if (result.failedFinal() > 0) {
                observation.complete(
                        OperationalTelemetry.Outcome.FAILURE,
                        OperationalTelemetry.ErrorCode.RETRY_EXHAUSTED);
            } else if (result.uncertain() > 0 || result.fenced() > 0) {
                observation.complete(
                        OperationalTelemetry.Outcome.DEGRADED,
                        result.fenced() > 0
                                ? OperationalTelemetry.ErrorCode.FENCED
                                : OperationalTelemetry.ErrorCode.UNKNOWN);
            } else if (result.retryScheduled() > 0) {
                observation.complete(
                        OperationalTelemetry.Outcome.RETRY,
                        OperationalTelemetry.ErrorCode.UNAVAILABLE);
            } else {
                observation.succeed();
            }
        } catch (RuntimeException failure) {
            observation.fail(OperationalTelemetry.ErrorCode.INTERNAL);
            throw failure;
        } finally {
            polling.set(false);
        }
    }
}
