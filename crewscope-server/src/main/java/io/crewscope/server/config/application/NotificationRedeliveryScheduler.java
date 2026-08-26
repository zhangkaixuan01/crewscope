package io.crewscope.server.config.application;

import io.crewscope.application.notification.NotificationRedeliveryWorker;
import io.crewscope.application.observability.OperationalTelemetry;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;

/** Non-overlapping bridge from audited recovery schedules to new durable Delivery rows. */
final class NotificationRedeliveryScheduler {

    private final NotificationRedeliveryWorker worker;
    private final AtomicBoolean polling = new AtomicBoolean();
    private final OperationalTelemetry telemetry;

    NotificationRedeliveryScheduler(
            NotificationRedeliveryWorker worker,
            OperationalTelemetry telemetry) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    @Scheduled(fixedDelayString = "${crewscope.notification.worker.redelivery-poll-interval:2s}")
    void poll() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        OperationalTelemetry.Observation observation = telemetry.start(
                OperationalTelemetry.Request.notification(
                        OperationalTelemetry.Operation.REDELIVER));
        try {
            worker.runOnce();
            observation.succeed();
        } catch (RuntimeException failure) {
            observation.fail(OperationalTelemetry.ErrorCode.INTERNAL);
            throw failure;
        } finally {
            polling.set(false);
        }
    }
}
