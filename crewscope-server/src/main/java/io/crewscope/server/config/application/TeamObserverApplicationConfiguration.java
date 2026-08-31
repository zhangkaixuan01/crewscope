package io.crewscope.server.config.application;

import io.crewscope.agentscope.template.AgentTemplateRuntimeAssembler;
import io.crewscope.agentscope.template.AgentTemplateRuntimeRegistry;
import io.crewscope.agentscope.teamobserver.TeamObserverModelFactory;
import io.crewscope.agentscope.teamobserver.TeamObserverRuntime;
import io.crewscope.agentscope.teamobserver.TeamObserverTemplateRuntimeRegistry;
import io.crewscope.application.agent.AgentConfigurationApplicationService;
import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.agent.AgentExecutionConfigurationService;
import io.crewscope.application.agent.AgentExecutionConfigurationResolver;
import io.crewscope.application.agent.AgentModelGovernance;
import io.crewscope.application.agent.AgentTemplateCatalogInitializer;
import io.crewscope.application.agent.AgentTemplateRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.observability.OperationalTelemetry;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.application.teamobserver.DefaultTeamObserverRepository;
import io.crewscope.application.teamobserver.DefaultTeamObserverService;
import io.crewscope.application.teamobserver.TeamObserverAdministration;
import io.crewscope.application.teamobserver.TeamObserverExecutionPort;
import io.crewscope.application.teamobserver.TeamObserverInvocationService;
import io.crewscope.application.teamobserver.TeamObserverModelPreflight;
import io.crewscope.application.teamobserver.TeamObserverProvisioningService;
import io.crewscope.application.teamobserver.TeamObserverReadService;
import io.crewscope.application.teamobserver.TeamSummaryProjectionPort;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Constructor-based composition of the fixed read-only Team Observer execution slice. */
@Configuration(proxyBeanMethods = false)
public class TeamObserverApplicationConfiguration {

    @Bean
    TeamObserverAdministration teamObserverAdministration(
            AgentConfigurationApplicationService configurations) {
        return (organizationId, teamId, actor, occurredAt) -> configurations.current(
                new io.crewscope.application.team.TeamAccessContext(actor, false),
                organizationId,
                teamId,
                io.crewscope.domain.teamobserver.TeamObserverInitialization.stableProfileId(teamId));
    }

    @Bean
    TeamObserverModelPreflight teamObserverModelPreflight(
            TeamRepository teams,
            AgentProfileRepository profiles,
            AgentTemplateRepository templates,
            PrincipalRepository principals,
            ModelConnectionRepository connections,
            AgentModelGovernance governance,
            AgentExecutionConfigurationResolver resolver,
            TimeProvider timeProvider) {
        return new AgentScopeTeamObserverModelPreflight(
                teams,
                profiles,
                templates,
                principals,
                connections,
                governance,
                resolver,
                timeProvider);
    }

    @Bean
    DefaultTeamObserverService defaultTeamObserverService(
            DefaultTeamObserverRepository observers,
            AgentConfigurationRepository configurations,
            TeamObserverAdministration administration,
            TeamObserverModelPreflight modelPreflight,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        return new DefaultTeamObserverService(
                observers,
                configurations,
                administration,
                modelPreflight,
                transactions,
                timeProvider);
    }

    @Bean
    TeamObserverProvisioningService teamObserverProvisioningService(
            TeamRepository teams,
            WorkspaceRepository workspaces,
            TeamMemberRepository members,
            PrincipalRepository principals,
            AgentTemplateCatalogInitializer templateCatalog,
            AgentTemplateRepository templates,
            DefaultTeamObserverService observers,
            DefaultTeamObserverRepository observerRepository,
            AgentConfigurationRepository configurations,
            AgentConfigurationApplicationService configurationService,
            TimeProvider timeProvider) {
        return new TeamObserverProvisioningService(
                teams,
                workspaces,
                members,
                principals,
                templateCatalog,
                templates,
                observers,
                observerRepository,
                configurations,
                configurationService,
                timeProvider);
    }

    @Bean
    TeamObserverTemplateRuntimeRegistry teamObserverTemplateRuntimeRegistry() {
        return new TeamObserverTemplateRuntimeRegistry();
    }

    @Bean
    TeamObserverReadService teamObserverReadService(
            TeamMemberRepository members, TeamSummaryProjectionPort projections) {
        return new TeamObserverReadService(members, projections);
    }

    @Bean
    TeamObserverModelFactory teamObserverModelFactory(
            AgentTemplateRuntimeAssembler assembler,
            TeamObserverTemplateRuntimeRegistry templates) {
        return new TeamObserverModelFactory(assembler, templates);
    }

    @Bean
    TeamObserverRuntime teamObserverRuntime(
            AgentTemplateRuntimeRegistry agents,
            TeamObserverTemplateRuntimeRegistry templates,
            TeamObserverReadService reads,
            TimeProvider timeProvider,
            OperationalTelemetry telemetry) {
        return new TeamObserverRuntime(
                agents, templates, reads, timeProvider, Duration.ofMinutes(2), telemetry);
    }

    @Bean
    TeamObserverExecutionPort teamObserverExecutionPort(
            TeamRepository teams,
            AgentProfileRepository profiles,
            AgentTemplateRepository templates,
            AgentConfigurationRepository configurations,
            ModelConnectionRepository connections,
            AgentModelGovernance governance,
            AgentExecutionConfigurationService resolver,
            TeamObserverModelFactory models,
            TeamObserverRuntime runtime,
            TeamObserverProvisioningService provisioning,
            TimeProvider timeProvider) {
        return new AgentScopeTeamObserverExecutionAdapter(
                teams,
                profiles,
                templates,
                configurations,
                connections,
                governance,
                resolver,
                models,
                runtime,
                provisioning,
                timeProvider);
    }

    @Bean
    TeamObserverInvocationService teamObserverInvocationService(
            TeamRepository teams,
            TeamMemberRepository members,
            TeamObserverExecutionPort executions,
            TimeProvider timeProvider) {
        return new TeamObserverInvocationService(teams, members, executions, timeProvider);
    }
}
