package io.crewscope.server.config.application;

import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.team.DefaultPersonalAgentRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamApplicationService;
import io.crewscope.application.team.TeamCreationService;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.time.TimeProvider;
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
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider) {
    return new TeamCreationService(
        teamRepository,
        workspaceRepository,
        teamMemberRepository,
        teamRoleRepository,
        memberRoleRepository,
        defaultPersonalAgentRepository,
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
}
