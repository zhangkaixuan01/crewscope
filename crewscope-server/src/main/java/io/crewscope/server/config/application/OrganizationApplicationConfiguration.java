package io.crewscope.server.config.application;

import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.identity.InitialOrganizationOwnerProvisioner;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.organization.OrganizationAuthorization;
import io.crewscope.application.organization.OrganizationRoleApplicationService;
import io.crewscope.application.organization.OrganizationRoleGrantRepository;
import io.crewscope.application.organization.OrganizationSettingApplicationService;
import io.crewscope.application.organization.OrganizationSettingRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.time.TimeProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the organization-level management use cases (M12-A01) without introducing Spring into the
 * application module. The provisioner bean is the fresh-install half of the initial-owner dual
 * track; the V68 migration backfill covers databases that already hold accounts.
 */
@Configuration(proxyBeanMethods = false)
public class OrganizationApplicationConfiguration {

  @Bean
  OrganizationAuthorization organizationAuthorization(
      OrganizationRoleGrantRepository organizationRoleGrants) {
    return new OrganizationAuthorization(organizationRoleGrants);
  }

  @Bean
  OrganizationRoleApplicationService organizationRoleApplicationService(
      OrganizationRoleGrantRepository organizationRoleGrants,
      OrganizationAuthorization organizationAuthorization,
      PrincipalRepository principalRepository,
      DomainEventStore domainEventStore,
      OutboxRepository outboxRepository,
      CommandReceiptStore commandReceiptStore,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider) {
    return new OrganizationRoleApplicationService(
        organizationRoleGrants,
        organizationAuthorization,
        principalRepository,
        commandReceiptStore,
        domainEventStore,
        outboxRepository,
        transactionExecutor,
        timeProvider);
  }

  @Bean
  OrganizationSettingApplicationService organizationSettingApplicationService(
      OrganizationSettingRepository organizationSettings,
      OrganizationAuthorization organizationAuthorization,
      DomainEventStore domainEventStore,
      OutboxRepository outboxRepository,
      CommandReceiptStore commandReceiptStore,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider) {
    return new OrganizationSettingApplicationService(
        organizationSettings,
        organizationAuthorization,
        commandReceiptStore,
        domainEventStore,
        outboxRepository,
        transactionExecutor,
        timeProvider);
  }

  @Bean
  InitialOrganizationOwnerProvisioner initialOrganizationOwnerProvisioner(
      OrganizationRoleApplicationService organizationRoleApplicationService) {
    return organizationRoleApplicationService::ensureInitialOwner;
  }
}
