package io.crewscope.server.config.application;

import io.crewscope.application.notification.NotificationWorker;
import io.crewscope.application.notification.NotificationWorkerBatchResult;
import io.crewscope.application.observability.OperationalTelemetry;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;

/** Non-overlapping wake-up loop; durable Delivery rows remain the scheduling source of truth. */
final class NotificationWorkerScheduler {

    private final NotificationWorker worker;
    private final AtomicBoolean polling = new AtomicBoolean();
    private final OperationalTelemetry telemetry;

    NotificationWorkerScheduler(NotificationWorker worker, OperationalTelemetry telemetry) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    @Scheduled(fixedDelayString = "${crewscope.notification.worker.poll-interval:1s}")
    void poll() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        OperationalTelemetry.Observation observation = telemetry.start(
                OperationalTelemetry.Request.notification(
                        OperationalTelemetry.Operation.DISPATCH));
        try {
            complete(observation, worker.runOnce());
        } catch (RuntimeException failure) {
            observation.fail(OperationalTelemetry.ErrorCode.INTERNAL);
            throw failure;
        } finally {
            polling.set(false);
        }
    }

    private static void complete(
            OperationalTelemetry.Observation observation,
            NotificationWorkerBatchResult result) {
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
        } else if (result.invalidated() > 0) {
            observation.complete(
                    OperationalTelemetry.Outcome.REJECTED,
                    OperationalTelemetry.ErrorCode.AUTHORIZATION_DRIFT);
        } else {
            observation.succeed();
        }
    }
}
