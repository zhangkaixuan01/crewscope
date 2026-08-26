package io.crewscope.infrastructure.event;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.event.publication.EventTransport;
import io.crewscope.application.observability.OperationalTelemetry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Verifies fail-fast bounds shared by publisher configuration and PostgreSQL persistence. */
class OutboxConfigurationContractTest {

    @Test
    void requiresDatabaseRepresentableLeaseAndBackoffDurations() {
        Duration subMillisecond = Duration.ofNanos(999_999);

        assertThrows(
                IllegalArgumentException.class,
                () -> policy(subMillisecond, Duration.ofMillis(1), Duration.ofMillis(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> policy(Duration.ofMillis(1), subMillisecond, Duration.ofMillis(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> policy(Duration.ofMillis(1), Duration.ofMillis(1), subMillisecond));

        OutboxDeliveryPolicy minimum = assertDoesNotThrow(() -> policy(
                Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofMillis(1)));
        assertEquals(1, minimum.claimLease().toMillis());
    }

    @Test
    void rejectsWorkerIdBeforeTheFirstDatabaseClaim() {
        assertDoesNotThrow(() -> publisher("w".repeat(OutboxClaimStore.MAX_WORKER_ID_LENGTH)));
        assertThrows(
                IllegalArgumentException.class,
                () -> publisher("w".repeat(OutboxClaimStore.MAX_WORKER_ID_LENGTH + 1)));
    }

    @Test
    void reportsAnEmptySuccessfulPollThroughTheM6OperationalBoundary() {
        OutboxClaimStore claims = mock(OutboxClaimStore.class);
        when(claims.claimAvailable(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        AtomicReference<OperationalTelemetry.Request> request = new AtomicReference<>();
        AtomicReference<OperationalTelemetry.Outcome> outcome = new AtomicReference<>();
        OperationalTelemetry telemetry = observed -> {
            request.set(observed);
            return (completed, ignored) -> outcome.set(completed);
        };
        PollingOutboxPublisher publisher = new PollingOutboxPublisher(
                "worker",
                claims,
                mock(EventTransport.class),
                policy(Duration.ofSeconds(1), Duration.ofMillis(1), Duration.ofSeconds(1)),
                Clock.systemUTC(),
                Runnable::run,
                telemetry);

        assertEquals(OutboxPublicationBatchResult.empty(), publisher.publishAvailable());
        assertEquals(OperationalTelemetry.Type.OUTBOX, request.get().type());
        assertEquals(OperationalTelemetry.Operation.PUBLISH, request.get().operation());
        assertEquals(OperationalTelemetry.Outcome.SUCCESS, outcome.get());
    }

    private static OutboxDeliveryPolicy policy(
            Duration lease, Duration initialBackoff, Duration maximumBackoff) {
        return new OutboxDeliveryPolicy(
                10, 3, 2, lease, initialBackoff, maximumBackoff);
    }

    private static PollingOutboxPublisher publisher(String workerId) {
        return new PollingOutboxPublisher(
                workerId,
                mock(OutboxClaimStore.class),
                mock(EventTransport.class),
                policy(Duration.ofSeconds(1), Duration.ofMillis(1), Duration.ofSeconds(1)),
                Clock.systemUTC(),
                Runnable::run);
    }
}
