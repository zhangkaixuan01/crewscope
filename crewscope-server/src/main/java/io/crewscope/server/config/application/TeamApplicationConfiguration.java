package io.crewscope.server.config.application;

import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.identity.UserAccountRepository;
import io.crewscope.application.provider.TeamProviderInitializer;
import io.crewscope.application.team.DefaultPersonalAgentRepository;
import io.crewscope.application.team.InvitationTokenDigester;
import io.crewscope.application.team.InvitationTokenGenerator;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.OnboardingApplicationService;
import io.crewscope.application.team.TeamInvitationAcceptanceService;
import io.crewscope.application.team.TeamInvitationApplicationService;
import io.crewscope.application.team.TeamInvitationIssueService;
import io.crewscope.application.team.TeamInvitationRepository;
import io.crewscope.application.team.TeamApplicationService;
import io.crewscope.application.team.TeamCreationService;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.time.TimeProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires Team and membership use cases without introducing Spring into the application module. */
@Configuration(proxyBeanMethods = false)
public class TeamApplicationConfiguration {

  @Bean
  TeamCreationService teamCreationService(
      TeamRepository teamRepository,
      WorkspaceRepository workspaceRepository,
      TeamMemberRepository teamMemberRepository,
      TeamRoleRepository teamRoleRepository,
      MemberRoleRepository memberRoleRepository,
      DefaultPersonalAgentRepository defaultPersonalAgentRepository,
      TeamProviderInitializer teamProviderInitializer,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider) {
    return new TeamCreationService(
        teamRepository,
        workspaceRepository,
        teamMemberRepository,
        teamRoleRepository,
        memberRoleRepository,
        defaultPersonalAgentRepository,
        teamProviderInitializer,
        transactionExecutor,
        timeProvider);
  }

  @Bean
  TeamApplicationService teamApplicationService(
      TeamCreationService teamCreationService,
      TeamRepository teamRepository,
      WorkspaceRepository workspaceRepository,
      TeamMemberRepository teamMemberRepository,
      TeamMembershipQuery teamMembershipQuery,
      TeamRoleRepository teamRoleRepository,
      MemberRoleRepository memberRoleRepository,
      PrincipalRepository principalRepository,
      DefaultPersonalAgentRepository defaultPersonalAgentRepository,
      DomainEventStore domainEventStore,
      OutboxRepository outboxRepository,
      CommandReceiptStore commandReceiptStore,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider) {
    return new TeamApplicationService(
        teamCreationService,
        teamRepository,
        workspaceRepository,
        teamMemberRepository,
        teamMembershipQuery,
        teamRoleRepository,
        memberRoleRepository,
        principalRepository,
        defaultPersonalAgentRepository,
        domainEventStore,
        outboxRepository,
        commandReceiptStore,
        transactionExecutor,
        timeProvider);
  }

  @Bean
  OnboardingApplicationService onboardingApplicationService(
      UserAccountRepository userAccountRepository,
      TeamRepository teamRepository,
      TeamApplicationService teamApplicationService,
      TransactionExecutor transactionExecutor) {
    return new OnboardingApplicationService(
        userAccountRepository, teamRepository, teamApplicationService, transactionExecutor);
  }

  @Bean
  @ConditionalOnBean({InvitationTokenGenerator.class, InvitationTokenDigester.class})
  TeamInvitationIssueService teamInvitationIssueService(
      TeamInvitationRepository invitations,
      InvitationTokenGenerator tokens,
      InvitationTokenDigester digester,
      TransactionExecutor transactions,
      TimeProvider timeProvider) {
    return new TeamInvitationIssueService(
        invitations, tokens, digester, transactions, timeProvider);
  }

  @Bean
  @ConditionalOnBean(TeamInvitationIssueService.class)
  TeamInvitationApplicationService teamInvitationApplicationService(
      TeamInvitationIssueService issueService,
      InvitationTokenDigester digester,
      TeamInvitationRepository invitations,
      TeamRepository teams,
      TeamMemberRepository members,
      TeamRoleRepository roles,
      MemberRoleRepository memberRoles,
      TeamInvitationAcceptanceService acceptanceService,
      DomainEventStore events,
      OutboxRepository outbox,
      CommandReceiptStore receipts,
      TransactionExecutor transactions,
      TimeProvider timeProvider) {
    return new TeamInvitationApplicationService(
        issueService,
        digester,
        invitations,
        teams,
        members,
        roles,
        memberRoles,
        acceptanceService,
        events,
        outbox,
        receipts,
        transactions,
        timeProvider);
  }
}
