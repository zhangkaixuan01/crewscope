package io.crewscope.server.config.application;

import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.agent.AgentInstanceRepository;
import io.crewscope.application.agent.AgentManagementApplicationService;
import io.crewscope.application.agent.AgentTemplateRepository;
import io.crewscope.application.agent.AgentTemplateCatalogInitializer;
import io.crewscope.application.agent.DefaultAgentTemplateCatalogInitializer;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.time.TimeProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Explicit composition root for M5-A02 Agent catalog and lifecycle management. */
@Configuration(proxyBeanMethods = false)
public class AgentManagementApplicationConfiguration {

  @Bean
  AgentTemplateCatalogInitializer agentTemplateCatalogInitializer(
      AgentTemplateRepository templates, ObjectMapper objectMapper) {
    return new DefaultAgentTemplateCatalogInitializer(templates, objectMapper);
  }

    @Bean
    AgentManagementApplicationService agentManagementApplicationService(
            AgentTemplateRepository templates,
            AgentProfileRepository profiles,
            AgentConfigurationRepository configurations,
            AgentInstanceRepository instances,
            PrincipalRepository principals,
            TeamRepository teams,
            WorkspaceRepository workspaces,
            TeamMembershipQuery memberships,
            TeamRoleRepository roles,
            MemberRoleRepository grants,
            DomainEventStore events,
            OutboxRepository outbox,
            CommandReceiptStore receipts,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        return new AgentManagementApplicationService(
                templates,
                profiles,
                configurations,
                instances,
                principals,
                teams,
                workspaces,
                memberships,
                roles,
                grants,
                events,
                outbox,
                receipts,
                transactions,
                timeProvider);
    }
}
