package io.crewscope.server.config.application;

import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.identity.IdentityMappingService;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.time.TimeProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires framework-free identity use cases to their production ports. */
@Configuration(proxyBeanMethods = false)
public class IdentityApplicationConfiguration {

  @Bean
  IdentityMappingService identityMappingService(
      PrincipalRepository principalRepository,
      DomainEventStore domainEventStore,
      OutboxRepository outboxRepository,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider) {
    return new IdentityMappingService(
        principalRepository, domainEventStore, outboxRepository, transactionExecutor, timeProvider);
  }
}
