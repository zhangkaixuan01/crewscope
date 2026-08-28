package io.crewscope.server.config.application;

import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.identity.AccountOrganizationBindingRepository;
import io.crewscope.application.identity.AccountOrganizationPrincipalResolver;
import io.crewscope.application.identity.AuthenticatedAccountOrganizationResolver;
import io.crewscope.application.identity.CurrentAccountSnapshotReader;
import io.crewscope.application.identity.IdentityMappingService;
import io.crewscope.application.identity.LoginIdentityRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.time.TimeProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires framework-free identity use cases to their production ports. */
@Configuration(proxyBeanMethods = false)
public class IdentityApplicationConfiguration {

  @Bean
  AccountOrganizationPrincipalResolver accountOrganizationPrincipalResolver(
      AccountOrganizationBindingRepository bindingRepository,
      PrincipalRepository principalRepository) {
    return new AccountOrganizationPrincipalResolver(
        bindingRepository, principalRepository::findById);
  }

  @Bean
  AuthenticatedAccountOrganizationResolver authenticatedAccountOrganizationResolver(
      CurrentAccountSnapshotReader snapshotReader,
      LoginIdentityRepository loginIdentityRepository,
      AccountOrganizationPrincipalResolver principalResolver) {
    return new AuthenticatedAccountOrganizationResolver(
        snapshotReader, loginIdentityRepository, principalResolver);
  }

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
