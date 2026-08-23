package io.crewscope.infrastructure.persistence.review;

import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.publication.DomainEventConsumer;
import io.crewscope.application.review.DurableReviewEventPublisher;
import io.crewscope.application.review.ReviewEventPublisher;
import io.crewscope.application.review.ReviewRequestRepository;
import io.crewscope.application.task.TaskEventRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Constructor-based Spring composition for M5 Review persistence and durable events. */
@Configuration(proxyBeanMethods = false)
public class ReviewPersistenceConfiguration {

    @Bean
    ReviewEventPublisher reviewEventPublisher(
            DomainEventStore eventStore,
            TaskEventRepository taskEvents,
            OutboxRepository outbox,
            TransactionExecutor transactions) {
        return new DurableReviewEventPublisher(eventStore, taskEvents, outbox, transactions);
    }

    @Bean
    DomainEventConsumer reviewDiffInvalidationConsumer(
            ObjectMapper objectMapper,
            ReviewRequestRepository requests,
            ReviewEventPublisher events) {
        return new ReviewDiffInvalidationConsumer(objectMapper, requests, events);
    }
}
