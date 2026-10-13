package io.crewscope.server.config.application;

import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.organization.OrganizationAuthorization;
import io.crewscope.application.quota.OrganizationQuotaAdmissionService;
import io.crewscope.application.quota.OrganizationQuotaApplicationService;
import io.crewscope.application.quota.OrganizationQuotaConfigurationRepository;
import io.crewscope.application.quota.OrganizationQuotaUsageQueryRepository;
import io.crewscope.application.quota.OrganizationQuotaUsageQueryService;
import io.crewscope.application.quota.OrganizationTaskIntakeQuotaCounter;
import io.crewscope.application.quota.OrganizationTokenBudgetLedger;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.time.TimeProvider;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the organization quota use cases (M12-A02). The admission service shares the
 * observability reporting zone so the intake window, the ledger month and the usage rollup
 * always group on the same boundary.
 */
@Configuration(proxyBeanMethods = false)
public class QuotaApplicationConfiguration {

  @Bean
  OrganizationQuotaAdmissionService organizationQuotaAdmissionService(
      OrganizationQuotaConfigurationRepository organizationQuotaConfigurations,
      OrganizationTaskIntakeQuotaCounter organizationTaskIntakeQuotaCounter,
      OrganizationTokenBudgetLedger organizationTokenBudgetLedger,
      @Value("${crewscope.observability.reporting-zone:UTC}") String reportingZone) {
    return new OrganizationQuotaAdmissionService(
        organizationQuotaConfigurations,
        organizationTaskIntakeQuotaCounter,
        organizationTokenBudgetLedger,
        ZoneId.of(reportingZone));
  }

  @Bean
  OrganizationQuotaApplicationService organizationQuotaApplicationService(
      OrganizationQuotaConfigurationRepository organizationQuotaConfigurations,
      OrganizationAuthorization organizationAuthorization,
      CommandReceiptStore commandReceiptStore,
      DomainEventStore domainEventStore,
      OutboxRepository outboxRepository,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider) {
    return new OrganizationQuotaApplicationService(
        organizationQuotaConfigurations,
        organizationAuthorization,
        commandReceiptStore,
        domainEventStore,
        outboxRepository,
        transactionExecutor,
        timeProvider);
  }

  @Bean
  OrganizationQuotaUsageQueryService organizationQuotaUsageQueryService(
      OrganizationQuotaConfigurationRepository organizationQuotaConfigurations,
      OrganizationQuotaUsageQueryRepository organizationQuotaUsageFacts,
      OrganizationAuthorization organizationAuthorization,
      TimeProvider timeProvider,
      @Value("${crewscope.observability.reporting-zone:UTC}") String reportingZone) {
    return new OrganizationQuotaUsageQueryService(
        organizationQuotaConfigurations,
        organizationQuotaUsageFacts,
        organizationAuthorization,
        timeProvider,
        ZoneId.of(reportingZone));
  }
}
