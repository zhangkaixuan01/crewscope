package io.crewscope.server.config.application;

import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.agent.AgentModelDefaultRepository;
import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.github.GitHubProviderRepository;
import io.crewscope.application.model.ModelCatalogEntryRepository;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.model.ModelProviderDefinitionRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.runtime.RuntimeObservationService;
import io.crewscope.application.setup.TeamSetupReadinessApplicationService;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.application.workitem.WorkProjectRepository;
import io.crewscope.domain.shared.time.TimeProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit composition root for the M8 Team Setup Readiness query. */
@Configuration(proxyBeanMethods = false)
public class SetupReadinessApplicationConfiguration {

    @Bean
    TeamSetupReadinessApplicationService teamSetupReadinessApplicationService(
            WorkItemAccessPolicy accessPolicy,
            TeamMembershipQuery memberships,
            AgentProfileRepository profiles,
            AgentConfigurationRepository configurations,
            AgentModelDefaultRepository modelDefaults,
            ModelConnectionRepository modelConnections,
            ModelCatalogEntryRepository catalogEntries,
            ModelProviderDefinitionRepository providers,
            WorkProjectRepository projects,
            RepositoryBindingRepository bindings,
            ConnectionRepository connections,
            GitHubProviderRepository github,
            RuntimeObservationService runtimeObservation,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        return new TeamSetupReadinessApplicationService(
                accessPolicy,
                memberships,
                profiles,
                configurations,
                modelDefaults,
                modelConnections,
                catalogEntries,
                providers,
                projects,
                bindings,
                connections,
                github,
                runtimeObservation,
                transactions,
                timeProvider);
    }
}
