package io.crewscope.infrastructure.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.event.publication.DomainEventConsumer;
import io.crewscope.application.event.publication.EventPublication;
import io.crewscope.application.event.publication.EventTransport;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Exercises Outbox claims, delivery recovery and consumer receipts against PostgreSQL. */
@SpringBootTest(
        classes = OutboxPublisherIntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true"
        })
class OutboxPublisherIntegrationTest extends AbstractPostgresRedisContainerIntegrationTest {

    private static final Instant BASE_TIME = Instant.parse("2026-08-07T01:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(10);
    private static final OutboxDeliveryPolicy POLICY = new OutboxDeliveryPolicy(
            100, 3, 100, LEASE, Duration.ofSeconds(1), Duration.ofSeconds(8));

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JdbcOutboxClaimStore claimStore;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID organizationId;

    @BeforeEach
    void resetData() {
        jdbcTemplate.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        organizationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Outbox Org', 'ACTIVE')",
                organizationId);
    }

    @Test
    void competingPublishersNeverPublishTheSameClaim() throws Exception {
        for (int index = 0; index < 20; index++) {
            seedEvent("partition-" + index, BASE_TIME.plusMillis(index), index);
        }
        MutableClock clock = new MutableClock(BASE_TIME.plusSeconds(1));
        List<UUID> published = new CopyOnWriteArrayList<>();
        EventTransport transport = publication -> published.add(publication.eventId());
        PollingOutboxPublisher first = publisher("publisher-a", transport, clock);
        PollingOutboxPublisher second = publisher("publisher-b", transport, clock);

        ExecutorService competitors = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<OutboxPublicationBatchResult> firstResult = competitors.submit(() -> {
                start.await();
                return first.publishAvailable();
            });
            Future<OutboxPublicationBatchResult> secondResult = competitors.submit(() -> {
                start.await();
                return second.publishAvailable();
            });
            start.countDown();
            int delivered = firstResult.get().delivered() + secondResult.get().delivered();

            assertEquals(20, delivered);
            assertEquals(20, published.size());
            assertEquals(20, new HashSet<>(published).size());
            assertEquals(20, countByStatus("DELIVERED"));
        } finally {
            competitors.shutdownNow();
        }
    }

    @Test
    void publishesOnlyTheOldestActiveEventInOnePartition() {
        UUID first = seedEvent("ordered", BASE_TIME.plusMillis(1), 0);
        UUID second = seedEvent("ordered", BASE_TIME, 1);
        MutableClock clock = new MutableClock(BASE_TIME.plusSeconds(1));
        List<EventPublication> publications = new ArrayList<>();
        PollingOutboxPublisher publisher = publisher(
                "ordered-publisher", publications::add, clock);

        assertEquals(1, publisher.publishAvailable().delivered());
        assertEquals(List.of(first), publications.stream().map(EventPublication::eventId).toList());
        JsonNode envelope = objectMapper.readTree(publications.get(0).eventJson());
        assertEquals(
                List.of(
                        "eventId",
                        "eventType",
                        "schemaVersion",
                        "organizationId",
                        "teamId",
                        "workspaceId",
                        "aggregateType",
                        "aggregateId",
                        "aggregateVersion",
                        "actorType",
                        "actorId",
                        "correlationId",
                        "causationId",
                        "idempotencyKey",
                        "occurredAt",
                        "payload"),
                new ArrayList<>(envelope.propertyNames()));
        assertTrue(envelope.get("teamId").isNull());
        assertEquals(0, envelope.get("payload").get("sequence").intValue());
        assertEquals(1, publisher.publishAvailable().delivered());
        assertEquals(
                List.of(first, second),
                publications.stream().map(EventPublication::eventId).toList());
    }

    @Test
    void publishesDifferentPartitionsConcurrentlyWithinTheConfiguredBound() throws Exception {
        seedEvent("parallel-a", BASE_TIME, 0);
        seedEvent("parallel-b", BASE_TIME.plusMillis(1), 0);
        MutableClock clock = new MutableClock(BASE_TIME.plusSeconds(1));
        CountDownLatch entered = new CountDownLatch(2);
        ExecutorService deliveryExecutor = Executors.newFixedThreadPool(2);
        try {
            PollingOutboxPublisher publisher = new PollingOutboxPublisher(
                    "parallel-publisher",
                    claimStore,
                    publication -> {
                        entered.countDown();
                        try {
                            if (!entered.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Publications did not overlap");
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Publication was interrupted", exception);
                        }
                    },
                    new OutboxDeliveryPolicy(
                            10, 3, 2, LEASE, Duration.ofSeconds(1), Duration.ofSeconds(8)),
                    clock,
                    deliveryExecutor);

            assertEquals(2, publisher.publishAvailable().delivered());
            assertEquals(0, entered.getCount());
        } finally {
            deliveryExecutor.shutdownNow();
        }
    }

    @Test
    void retriesWithCappedExponentialBackoffThenMovesToDeadLetter() {
        seedEvent("failure", BASE_TIME, 0);
        MutableClock clock = new MutableClock(BASE_TIME.plusSeconds(1));
        AtomicInteger attempts = new AtomicInteger();
        PollingOutboxPublisher publisher = publisher("failing-publisher", publication -> {
            attempts.incrementAndGet();
            throw new SimulatedTransportFailure();
        }, clock);

        assertEquals(1, publisher.publishAvailable().failed());
        assertOutbox("PENDING", 1, clock.instant().plusSeconds(1), "TRANSPORT_FAILURE");
        assertEquals(0, publisher.publishAvailable().claimed());

        clock.advance(Duration.ofSeconds(1));
        assertEquals(1, publisher.publishAvailable().failed());
        assertOutbox("PENDING", 2, clock.instant().plusSeconds(2), "TRANSPORT_FAILURE");

        clock.advance(Duration.ofSeconds(2));
        assertEquals(1, publisher.publishAvailable().failed());
        assertOutbox("DEAD_LETTER", 3, null, "TRANSPORT_FAILURE");
        assertEquals(3, attempts.get());
        assertEquals(0, publisher.publishAvailable().claimed());
    }

    @Test
    void recoversExpiredLeaseAfterBackoffAndRejectsTheOldClaimToken() {
        seedEvent("crash", BASE_TIME, 0);
        Instant firstClaimTime = BASE_TIME.plusSeconds(1);
        ClaimedOutboxEvent abandoned = claimStore
                .claimAvailable("crashed-worker", firstClaimTime, POLICY)
                .get(0);

        Instant expired = firstClaimTime.plus(LEASE);
        assertTrue(claimStore.claimAvailable("recovery-worker", expired, POLICY).isEmpty());
        assertOutbox("PENDING", 1, expired.plusSeconds(1), "CLAIM_EXPIRED");

        ClaimedOutboxEvent recovered = claimStore
                .claimAvailable("recovery-worker", expired.plusSeconds(1), POLICY)
                .get(0);
        assertNotEquals(abandoned.claimToken(), recovered.claimToken());
        assertFalse(claimStore.markDelivered(
                abandoned.outboxId(), abandoned.claimToken(), expired.plusSeconds(2)));
        assertTrue(claimStore.markDelivered(
                recovered.outboxId(), recovered.claimToken(), expired.plusSeconds(2)));
        assertEquals(1, countByStatus("DELIVERED"));
    }

    @Test
    void duplicateConsumerDeliveryProducesOneEffectiveSideEffect() {
        UUID eventId = seedEvent("consumer", BASE_TIME, 0);
        EventPublication event = publication(eventId);
        AtomicInteger effects = new AtomicInteger();
        DomainEventConsumer consumer = consumer("audit-projection", effects, false);
        IdempotentEventDispatcher dispatcher = new IdempotentEventDispatcher(
                jdbcTemplate, transactionManager, Clock.fixed(BASE_TIME, ZoneOffset.UTC));

        assertTrue(dispatcher.dispatch(consumer, event));
        assertFalse(dispatcher.dispatch(consumer, event));
        assertEquals(1, effects.get());
        assertEquals(1, receiptCount("audit-projection", eventId));
    }

    @Test
    void failedConsumerRollsBackItsReceiptAndCanBeRetried() {
        UUID eventId = seedEvent("consumer-rollback", BASE_TIME, 0);
        EventPublication event = publication(eventId);
        AtomicInteger effects = new AtomicInteger();
        IdempotentEventDispatcher dispatcher = new IdempotentEventDispatcher(
                jdbcTemplate, transactionManager, Clock.fixed(BASE_TIME, ZoneOffset.UTC));
        DomainEventConsumer failingConsumer = new DomainEventConsumer() {
            @Override
            public String consumerName() {
                return "retryable-projection";
            }

            @Override
            public void consume(EventPublication publication) {
                jdbcTemplate.update(
                        "UPDATE crewscope.organization SET name = 'Must Roll Back' WHERE id = ?",
                        organizationId);
                throw new SimulatedConsumerFailure();
            }
        };

        assertThrows(
                SimulatedConsumerFailure.class,
                () -> dispatcher.dispatch(failingConsumer, event));
        assertEquals(0, receiptCount("retryable-projection", eventId));
        assertEquals("Outbox Org", jdbcTemplate.queryForObject(
                "SELECT name FROM crewscope.organization WHERE id = ?",
                String.class,
                organizationId));
        assertTrue(dispatcher.dispatch(
                consumer("retryable-projection", effects, false), event));
        assertEquals(1, receiptCount("retryable-projection", eventId));
    }

    private PollingOutboxPublisher publisher(
            String workerId, EventTransport transport, Clock clock) {
        return new PollingOutboxPublisher(
                workerId, claimStore, transport, POLICY, clock, Runnable::run);
    }

    private UUID seedEvent(String partition, Instant occurredAt, long aggregateVersion) {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.domain_event (
                    event_id, event_type, schema_version,
                    organization_id, subject_type, subject_id, aggregate_version,
                    actor_type, correlation_id, occurred_at, payload
                ) VALUES (?, 'WORK_ITEM_CREATED', '1', ?, 'WORK_ITEM', ?, ?,
                    'SERVICE', ?, ?, CAST(? AS JSONB))
                """,
                eventId,
                organizationId,
                aggregateId,
                aggregateVersion,
                UUID.randomUUID(),
                occurredAt.atOffset(ZoneOffset.UTC),
                "{\"sequence\":" + aggregateVersion + "}");
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.outbox_event (
                    id, domain_event_id, topic, partition_key,
                    delivery_status, retry_count, created_at, version, updated_at
                ) VALUES (?, ?, ?, ?, 'PENDING', 0, ?, 0, ?)
                """,
                UUID.randomUUID(),
                eventId,
                PendingOutboxEvent.DOMAIN_EVENTS_TOPIC,
                partition,
                occurredAt.atOffset(ZoneOffset.UTC),
                occurredAt.atOffset(ZoneOffset.UTC));
        return eventId;
    }

    private EventPublication publication(UUID eventId) {
        return new EventPublication(
                UUID.randomUUID(),
                eventId,
                PendingOutboxEvent.DOMAIN_EVENTS_TOPIC,
                "consumer",
                1,
                io.crewscope.domain.shared.time.UtcTimestamp.from(BASE_TIME),
                "{\"eventId\":\"" + eventId + "\"}");
    }

    private static DomainEventConsumer consumer(
            String name, AtomicInteger effects, boolean fail) {
        return new DomainEventConsumer() {
            @Override
            public String consumerName() {
                return name;
            }

            @Override
            public void consume(EventPublication publication) {
                if (fail) {
                    throw new SimulatedConsumerFailure();
                }
                effects.incrementAndGet();
            }
        };
    }

    private int countByStatus(String status) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crewscope.outbox_event WHERE delivery_status = ?",
                Integer.class,
                status);
    }

    private int receiptCount(String consumerName, UUID eventId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM crewscope.event_consumer_receipt
                WHERE consumer_name = ? AND domain_event_id = ?
                """,
                Integer.class,
                consumerName,
                eventId);
    }

    private void assertOutbox(
            String status, int retryCount, Instant nextDeliveryAt, String errorCode) {
        var row = jdbcTemplate.queryForMap(
                """
                SELECT delivery_status, retry_count, next_delivery_at, last_error_code
                FROM crewscope.outbox_event
                """);
        assertEquals(status, row.get("delivery_status"));
        assertEquals(retryCount, ((Number) row.get("retry_count")).intValue());
        assertEquals(errorCode, row.get("last_error_code"));
        if (nextDeliveryAt == null) {
            assertEquals(null, row.get("next_delivery_at"));
        } else {
            assertEquals(
                    nextDeliveryAt,
                    ((java.sql.Timestamp) row.get("next_delivery_at")).toInstant());
        }
    }

    private static final class MutableClock extends Clock {

        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    private static final class SimulatedTransportFailure extends RuntimeException {}

    private static final class SimulatedConsumerFailure extends RuntimeException {}

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(JdbcOutboxClaimStore.class)
    static class TestApplication {}
}
