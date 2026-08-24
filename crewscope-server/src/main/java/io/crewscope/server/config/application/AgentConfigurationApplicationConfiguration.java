package io.crewscope.server.config.application;

import io.crewscope.application.agent.AgentConfigurationApplicationService;
import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.agent.AgentExecutionConfigurationResolver;
import io.crewscope.application.agent.AgentModelGovernance;
import io.crewscope.application.agent.AgentModelGovernanceSnapshot;
import io.crewscope.application.agent.AgentTemplateRepository;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.conversation.AgentRuntimeSessionRepository;
import io.crewscope.application.conversation.ConversationConfigurationRefreshGuard;
import io.crewscope.application.conversation.ConversationConfigurationRefreshService;
import io.crewscope.application.conversation.ConversationRepository;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.model.ModelCatalogEntryRepository;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.model.SelectableModelCatalogService;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.agent.AgentModelPolicyConstraints;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelDataRetentionMode;
import io.crewscope.domain.model.ModelRegion;
import io.crewscope.domain.policy.PolicyPackId;
import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.shared.time.TimeProvider;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit composition root for M5-A03 Agent configuration and safe Conversation refresh. */
@Configuration(proxyBeanMethods = false)
public class AgentConfigurationApplicationConfiguration {

    private static final PolicyPackReference M5_BASELINE_POLICY = new PolicyPackReference(
            new PolicyPackId(UUID.fromString("4a43bbf5-fb86-51a5-98aa-d8acb5d6071b")), 1);

    @Bean
    AgentModelGovernance agentModelGovernance() {
        // M5 starts with a conservative server-owned baseline. A later policy adapter can replace
        // this Bean without moving policy decisions into Controllers or mutable client payloads.
        return (actor, teamId, profile, usableConnections) -> {
            Set<ModelRegion> regions = usableConnections.stream()
                    .map(ModelConnection::region)
                    .collect(Collectors.toUnmodifiableSet());
            if (regions.isEmpty()) {
                regions = Set.of(new ModelRegion("global"));
            }
            return new AgentModelGovernanceSnapshot(
                    M5_BASELINE_POLICY,
                    new AgentModelPolicyConstraints(
                            Set.of(),
                            regions,
                            EnumSet.allOf(ModelDataRetentionMode.class),
                            java.util.Optional.empty(),
                            true,
                            1,
                            1),
                    Set.of(),
                    Set.of());
        };
    }

    @Bean
    AgentConfigurationApplicationService agentConfigurationApplicationService(
            AgentProfileRepository profiles,
            AgentTemplateRepository templates,
            AgentConfigurationRepository configurations,
            ModelConnectionRepository connections,
            ModelCatalogEntryRepository catalogs,
            SelectableModelCatalogService selectableModels,
            AgentExecutionConfigurationResolver resolver,
            AgentModelGovernance governance,
            TeamRepository teams,
            TeamMembershipQuery memberships,
            TeamRoleRepository roles,
            MemberRoleRepository grants,
            DomainEventStore events,
            OutboxRepository outbox,
            CommandReceiptStore receipts,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        return new AgentConfigurationApplicationService(
                profiles,
                templates,
                configurations,
                connections,
                catalogs,
                selectableModels,
                resolver,
                governance,
                teams,
                memberships,
                roles,
                grants,
                events,
                outbox,
                receipts,
                transactions,
                timeProvider);
    }

    @Bean
    ConversationConfigurationRefreshService conversationConfigurationRefreshService(
            ConversationRepository conversations,
            WorkspaceRepository workspaces,
            TeamMemberRepository members,
            PrincipalRepository principals,
            AgentProfileRepository profiles,
            AgentConfigurationRepository configurations,
            AgentRuntimeSessionRepository sessions,
            ConversationConfigurationRefreshGuard refreshGuard,
            DomainEventStore events,
            OutboxRepository outbox,
            CommandReceiptStore receipts,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        return new ConversationConfigurationRefreshService(
                conversations,
                workspaces,
                members,
                principals,
                profiles,
                configurations,
                sessions,
                refreshGuard,
                events,
                outbox,
                receipts,
                transactions,
                timeProvider);
    }
}
