package io.crewscope.infrastructure.event;

import io.crewscope.application.event.publication.EventTransport;
import io.crewscope.application.observability.OperationalTelemetry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/** Claims one batch, publishes outside a transaction, and conditionally acknowledges each lease. */
public class PollingOutboxPublisher {

    private static final String TRANSPORT_FAILURE = "TRANSPORT_FAILURE";

    private final String workerId;
    private final OutboxClaimStore claimStore;
    private final EventTransport eventTransport;
    private final OutboxDeliveryPolicy policy;
    private final Clock clock;
    private final Executor executor;
    private final OperationalTelemetry telemetry;

    public PollingOutboxPublisher(
            String workerId,
            OutboxClaimStore claimStore,
            EventTransport eventTransport,
            OutboxDeliveryPolicy policy,
            Clock clock,
            Executor executor) {
        this(workerId, claimStore, eventTransport, policy, clock, executor,
                OperationalTelemetry.noop());
    }

    public PollingOutboxPublisher(
            String workerId,
            OutboxClaimStore claimStore,
            EventTransport eventTransport,
            OutboxDeliveryPolicy policy,
            Clock clock,
            Executor executor,
            OperationalTelemetry telemetry) {
        this.workerId = requireWorkerId(workerId);
        this.claimStore = Objects.requireNonNull(claimStore, "claimStore");
        this.eventTransport = Objects.requireNonNull(eventTransport, "eventTransport");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    /** Executes one bounded polling cycle and waits for all claimed publications to settle. */
    public OutboxPublicationBatchResult publishAvailable() {
        OperationalTelemetry.Observation observation = telemetry.start(
                OperationalTelemetry.Request.outbox());
        try {
            List<ClaimedOutboxEvent> claimed = claimStore.claimAvailable(
                    workerId, clock.instant(), policy);
            if (claimed.isEmpty()) {
                observation.succeed();
                return OutboxPublicationBatchResult.empty();
            }

            AtomicInteger delivered = new AtomicInteger();
            AtomicInteger failed = new AtomicInteger();
            AtomicInteger unconfirmed = new AtomicInteger();
            List<CompletableFuture<Void>> publications = new ArrayList<>(claimed.size());
            for (ClaimedOutboxEvent event : claimed) {
                publications.add(CompletableFuture.runAsync(
                        () -> publishOne(event, delivered, failed, unconfirmed), executor));
            }
            CompletableFuture.allOf(publications.toArray(CompletableFuture[]::new)).join();
            OutboxPublicationBatchResult result = new OutboxPublicationBatchResult(
                    claimed.size(), delivered.get(), failed.get(), unconfirmed.get());
            completeObservation(observation, result);
            return result;
        } catch (RuntimeException failure) {
            observation.fail(OperationalTelemetry.ErrorCode.INTERNAL);
            throw failure;
        }
    }

    private static void completeObservation(
            OperationalTelemetry.Observation observation,
            OutboxPublicationBatchResult result) {
        if (result.failed() > 0) {
            observation.complete(
                    OperationalTelemetry.Outcome.RETRY,
                    OperationalTelemetry.ErrorCode.TRANSPORT_FAILURE);
        } else if (result.unconfirmed() > 0) {
            observation.complete(
                    OperationalTelemetry.Outcome.DEGRADED,
                    OperationalTelemetry.ErrorCode.ACK_UNCONFIRMED);
        } else {
            observation.succeed();
        }
    }

    private void publishOne(
            ClaimedOutboxEvent event,
            AtomicInteger delivered,
            AtomicInteger failed,
            AtomicInteger unconfirmed) {
        try {
            eventTransport.publish(event.publication());
        } catch (RuntimeException ignored) {
            Instant failedAt = clock.instant();
            if (claimStore.markFailed(
                    event.outboxId(),
                    event.claimToken(),
                    failedAt,
                    TRANSPORT_FAILURE,
                    policy)) {
                failed.incrementAndGet();
            } else {
                unconfirmed.incrementAndGet();
            }
            return;
        }
        // A failed acknowledgement intentionally leaves the lease recoverable for at-least-once delivery.
        Instant completedAt = clock.instant();
        if (claimStore.markDelivered(event.outboxId(), event.claimToken(), completedAt)) {
            delivered.incrementAndGet();
        } else {
            unconfirmed.incrementAndGet();
        }
    }

    private static String requireWorkerId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        String normalized = value.strip();
        if (normalized.length() > OutboxClaimStore.MAX_WORKER_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "workerId must contain at most "
                            + OutboxClaimStore.MAX_WORKER_ID_LENGTH
                            + " characters");
        }
        return normalized;
    }
}
