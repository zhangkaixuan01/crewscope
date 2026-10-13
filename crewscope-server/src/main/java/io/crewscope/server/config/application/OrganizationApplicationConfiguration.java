package io.crewscope.server.config.application;

import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.identity.AccountOrganizationBindingRepository;
import io.crewscope.application.identity.InitialOrganizationOwnerProvisioner;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.identity.UserAccountRepository;
import io.crewscope.application.organization.OrganizationAuthorization;
import io.crewscope.application.organization.OrganizationRoleApplicationService;
import io.crewscope.application.organization.OrganizationRoleGrantRepository;
import io.crewscope.application.organization.OrganizationSettingApplicationService;
import io.crewscope.application.organization.OrganizationSettingRepository;
import io.crewscope.application.organization.governance.AccountDeletionStepExecutor;
import io.crewscope.application.organization.governance.AuditRetentionGateway;
import io.crewscope.application.organization.governance.GovernanceAuditRecorder;
import io.crewscope.application.organization.governance.OrganizationAccountDeletionApplicationService;
import io.crewscope.application.organization.governance.OrganizationAccountDeletionJobRepository;
import io.crewscope.application.organization.governance.OrganizationAuditRetentionSweeper;
import io.crewscope.application.organization.governance.OrganizationDataExportJobRepository;
import io.crewscope.application.organization.governance.OrganizationExportApplicationService;
import io.crewscope.application.organization.governance.OrganizationExportResourceReader;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.task.TaskExecutionRepository;
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

  @Bean
  OrganizationExportApplicationService organizationExportApplicationService(
      OrganizationDataExportJobRepository organizationDataExportJobs,
      OrganizationAuthorization organizationAuthorization,
      OrganizationExportResourceReader organizationExportResourceReader,
      CommandReceiptStore commandReceiptStore,
      DomainEventStore domainEventStore,
      OutboxRepository outboxRepository,
      GovernanceAuditRecorder governanceAuditRecorder,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider,
      tools.jackson.databind.ObjectMapper objectMapper) {
    return new OrganizationExportApplicationService(
        organizationDataExportJobs,
        organizationAuthorization,
        organizationExportResourceReader,
        commandReceiptStore,
        domainEventStore,
        outboxRepository,
        governanceAuditRecorder,
        transactionExecutor,
        timeProvider,
        objectMapper);
  }

  @Bean
  OrganizationAccountDeletionApplicationService organizationAccountDeletionApplicationService(
      OrganizationAccountDeletionJobRepository organizationAccountDeletionJobs,
      OrganizationAuthorization organizationAuthorization,
      AccountDeletionStepExecutor accountDeletionStepExecutor,
      AccountOrganizationBindingRepository accountOrganizationBindingRepository,
      UserAccountRepository userAccountRepository,
      TaskExecutionRepository taskExecutionRepository,
      ResponsibilityAssignmentRepository responsibilityAssignmentRepository,
      CommandReceiptStore commandReceiptStore,
      DomainEventStore domainEventStore,
      OutboxRepository outboxRepository,
      GovernanceAuditRecorder governanceAuditRecorder,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider,
      tools.jackson.databind.ObjectMapper objectMapper) {
    return new OrganizationAccountDeletionApplicationService(
        organizationAccountDeletionJobs,
        organizationAuthorization,
        accountDeletionStepExecutor,
        accountOrganizationBindingRepository,
        userAccountRepository,
        taskExecutionRepository,
        responsibilityAssignmentRepository,
        commandReceiptStore,
        domainEventStore,
        outboxRepository,
        governanceAuditRecorder,
        transactionExecutor,
        timeProvider,
        objectMapper);
  }

  @Bean
  OrganizationAuditRetentionSweeper organizationAuditRetentionSweeper(
      OrganizationSettingRepository organizationSettings,
      AuditRetentionGateway auditRetentionGateway,
      TimeProvider timeProvider) {
    return new OrganizationAuditRetentionSweeper(
        organizationSettings, auditRetentionGateway, timeProvider);
  }

  @Bean
  OrganizationRetentionSweepScheduler organizationRetentionSweepScheduler(
      OrganizationAuditRetentionSweeper organizationAuditRetentionSweeper) {
    return new OrganizationRetentionSweepScheduler(organizationAuditRetentionSweeper);
  }
}
