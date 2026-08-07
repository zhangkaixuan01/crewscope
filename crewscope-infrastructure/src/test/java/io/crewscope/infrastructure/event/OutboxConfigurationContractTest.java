package io.crewscope.infrastructure.event;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import io.crewscope.application.event.publication.EventTransport;
import java.time.Clock;
import java.time.Duration;
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
