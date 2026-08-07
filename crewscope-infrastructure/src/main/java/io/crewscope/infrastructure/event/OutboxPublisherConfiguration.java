package io.crewscope.infrastructure.event;

import io.crewscope.application.event.publication.DomainEventConsumer;
import io.crewscope.application.event.publication.EventTransport;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Production wiring for the bounded polling publisher and M0 in-process consumer transport. */
@Configuration(proxyBeanMethods = false)
public class OutboxPublisherConfiguration {

    @Bean
    OutboxDeliveryPolicy outboxDeliveryPolicy(
            @Value("${crewscope.outbox.batch-size:100}") int batchSize,
            @Value("${crewscope.outbox.max-attempts:8}") int maxAttempts,
            @Value("${crewscope.outbox.parallelism:4}") int parallelism,
            @Value("${crewscope.outbox.claim-lease:30s}") Duration claimLease,
            @Value("${crewscope.outbox.initial-backoff:1s}") Duration initialBackoff,
            @Value("${crewscope.outbox.maximum-backoff:5m}") Duration maximumBackoff) {
        return new OutboxDeliveryPolicy(
                batchSize,
                maxAttempts,
                parallelism,
                claimLease,
                initialBackoff,
                maximumBackoff);
    }

    @Bean
    IdempotentEventDispatcher idempotentEventDispatcher(
            JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        return new IdempotentEventDispatcher(
                jdbcTemplate, transactionManager, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(EventTransport.class)
    EventTransport inProcessEventTransport(
            ObjectProvider<DomainEventConsumer> consumers,
            IdempotentEventDispatcher dispatcher) {
        List<DomainEventConsumer> orderedConsumers = consumers.orderedStream().toList();
        return new InProcessEventTransport(orderedConsumers, dispatcher);
    }

    @Bean(name = "outboxPublicationExecutor", destroyMethod = "shutdown")
    ExecutorService outboxPublicationExecutor(OutboxDeliveryPolicy policy) {
        AtomicInteger sequence = new AtomicInteger();
        return Executors.newFixedThreadPool(policy.parallelism(), runnable -> {
            Thread thread = new Thread(
                    runnable, "crewscope-outbox-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    PollingOutboxPublisher pollingOutboxPublisher(
            OutboxClaimStore claimStore,
            EventTransport eventTransport,
            OutboxDeliveryPolicy policy,
            @Qualifier("outboxPublicationExecutor") ExecutorService executor,
            @Value("${crewscope.outbox.worker-id:}") String configuredWorkerId) {
        String workerId = configuredWorkerId.isBlank()
                ? "crewscope-" + UUID.randomUUID()
                : configuredWorkerId;
        return new PollingOutboxPublisher(
                workerId,
                claimStore,
                eventTransport,
                policy,
                Clock.systemUTC(),
                executor);
    }
}
