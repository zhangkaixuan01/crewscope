package io.crewscope.server.config.application;

import io.crewscope.application.observability.OperationalTelemetry;
import io.crewscope.application.retrieval.KnowledgeIndexWorker;
import io.crewscope.application.retrieval.KnowledgeIndexWorkerRunResult;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Non-overlapping wake-up loop for the knowledge index; durable job rows remain the
 * scheduling source of truth. A fenced write surfaces as {@code DEGRADED/FENCED} —
 * counted, never hidden — because the new claim owner will redo the work.
 */
final class KnowledgeIndexWorkerScheduler {

    private final KnowledgeIndexWorker worker;
    private final OperationalTelemetry telemetry;
    private final AtomicBoolean polling = new AtomicBoolean();

    KnowledgeIndexWorkerScheduler(KnowledgeIndexWorker worker, OperationalTelemetry telemetry) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    @Scheduled(fixedDelayString = "${crewscope.knowledge.index.worker.poll-interval:1s}")
    void poll() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        OperationalTelemetry.Observation observation = telemetry.start(
                OperationalTelemetry.Request.knowledgeIndex());
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
            KnowledgeIndexWorkerRunResult result) {
        if (result.failedJobs() > 0) {
            observation.complete(
                    OperationalTelemetry.Outcome.FAILURE,
                    OperationalTelemetry.ErrorCode.INTERNAL);
        } else if (result.fencedWrites() > 0) {
            observation.complete(
                    OperationalTelemetry.Outcome.DEGRADED,
                    OperationalTelemetry.ErrorCode.FENCED);
        } else {
            observation.succeed();
        }
    }
}
