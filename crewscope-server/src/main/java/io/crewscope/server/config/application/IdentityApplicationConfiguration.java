package io.crewscope.server.config.application;

import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.identity.AccountOrganizationBindingRepository;
import io.crewscope.application.identity.AccountOrganizationPrincipalResolver;
import io.crewscope.application.identity.AuthenticatedAccountOrganizationResolver;
import io.crewscope.application.identity.CurrentAccountSnapshotReader;
import io.crewscope.application.identity.CurrentAccountApplicationService;
import io.crewscope.application.identity.IdentityMappingService;
import io.crewscope.application.identity.IdentityPersistenceCapacityException;
import io.crewscope.application.identity.LocalAccountRegistrationService;
import io.crewscope.application.identity.LocalAccountLoginService;
import io.crewscope.application.identity.LocalCredentialStore;
import io.crewscope.application.identity.LocalPasswordAuthentication;
import io.crewscope.application.identity.LoginIdentityRepository;
import io.crewscope.application.identity.LoginDefense;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.identity.UserAccountRepository;
import io.crewscope.application.team.InvitationTokenDigester;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamInvitationAcceptanceService;
import io.crewscope.application.team.TeamInvitationRepository;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.time.TimeProvider;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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

  @Bean
  TeamInvitationAcceptanceService teamInvitationAcceptanceService() {
    return new TeamInvitationAcceptanceService();
  }

  /** Keeps blocking registration persistence away from WebFlux and password-hash workers. */
  @Bean(name = "localRegistrationPersistenceExecutor", destroyMethod = "shutdown")
  ExecutorService localRegistrationPersistenceExecutor() {
    int workers = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
    AtomicInteger sequence = new AtomicInteger();
    return new ThreadPoolExecutor(
        workers,
        workers,
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(64),
        task -> {
          Thread thread = new Thread(task, "crewscope-registration-db-" + sequence.incrementAndGet());
          thread.setDaemon(true);
          return thread;
        },
        (task, executor) -> {
          throw new IdentityPersistenceCapacityException();
        });
  }

  @Bean
  LocalAccountRegistrationService localAccountRegistrationService(
      UserAccountRepository accounts,
      LoginIdentityRepository loginIdentities,
      LocalCredentialStore credentials,
      PrincipalRepository principals,
      AccountOrganizationBindingRepository bindings,
      TeamInvitationRepository invitations,
      TeamRepository teams,
      TeamMemberRepository members,
      TeamRoleRepository roles,
      MemberRoleRepository memberRoles,
      TeamInvitationAcceptanceService invitationAcceptance,
      ObjectProvider<InvitationTokenDigester> invitationDigester,
      LocalPasswordAuthentication passwords,
      DomainEventStore events,
      OutboxRepository outbox,
      CommandReceiptStore receipts,
      TransactionExecutor transactions,
      TimeProvider timeProvider,
      @Qualifier("localRegistrationPersistenceExecutor") ExecutorService persistenceExecutor) {
    return new LocalAccountRegistrationService(
        accounts,
        loginIdentities,
        credentials,
        principals,
        bindings,
        invitations,
        teams,
        members,
        roles,
        memberRoles,
        invitationAcceptance,
        Optional.ofNullable(invitationDigester.getIfAvailable()),
        passwords,
        events,
        outbox,
        receipts,
        transactions,
        timeProvider,
        persistenceExecutor);
  }

  @Bean
  @ConditionalOnBean(LoginDefense.class)
  LocalAccountLoginService localAccountLoginService(
      UserAccountRepository accounts,
      LoginIdentityRepository loginIdentities,
      LocalCredentialStore credentials,
      LocalPasswordAuthentication passwords,
      LoginDefense defense) {
    return new LocalAccountLoginService(
        accounts, loginIdentities, credentials, passwords, defense);
  }

  @Bean
  CurrentAccountApplicationService currentAccountApplicationService(
      UserAccountRepository accounts,
      LocalCredentialStore credentials,
      LocalPasswordAuthentication passwords,
      DomainEventStore events,
      OutboxRepository outbox,
      TransactionExecutor transactions,
      TimeProvider timeProvider,
      @Qualifier("localRegistrationPersistenceExecutor") ExecutorService persistenceExecutor) {
    return new CurrentAccountApplicationService(
        accounts,
        credentials,
        passwords,
        events,
        outbox,
        transactions,
        timeProvider,
        persistenceExecutor);
  }
}
