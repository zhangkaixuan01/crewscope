package io.crewscope.infrastructure;

import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemApplicationService;
import io.crewscope.application.workitem.WorkItemRepository;
import io.crewscope.domain.shared.time.TimeProvider;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires framework-free application services to production persistence and time adapters. */
@Configuration(proxyBeanMethods = false)
public class ApplicationServiceConfiguration {

    @Bean
    TimeProvider crewscopeTimeProvider() {
        return TimeProvider.from(Clock.systemUTC());
    }

    @Bean
    WorkItemApplicationService workItemApplicationService(
            WorkItemRepository workItemRepository,
            DomainEventStore domainEventStore,
            OutboxRepository outboxRepository,
            CommandReceiptStore commandReceiptStore,
            TransactionExecutor transactionExecutor,
            TimeProvider timeProvider) {
        return new WorkItemApplicationService(
                workItemRepository,
                domainEventStore,
                outboxRepository,
                commandReceiptStore,
                transactionExecutor,
                timeProvider);
    }
}
