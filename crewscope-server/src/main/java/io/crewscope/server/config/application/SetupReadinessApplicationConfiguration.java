package io.crewscope.server.config.application;

import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.audit.AuditAuthorization;
import io.crewscope.application.credential.CredentialStore;
import io.crewscope.application.agent.AgentModelDefaultRepository;
import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.github.GitHubProviderRepository;
import io.crewscope.application.model.ModelCatalogEntryRepository;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.model.ModelProviderDefinitionRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.runtime.RuntimeObservationService;
import io.crewscope.application.setup.TeamSetupReadinessApplicationService;
import io.crewscope.application.setup.ConfigurationHealthApplicationService;
import io.crewscope.application.setup.ConfigurationSearchApplicationService;
import io.crewscope.application.principal.PrincipalDirectoryAccessPolicy;
import io.crewscope.application.principal.PrincipalDirectoryQueryService;
import io.crewscope.application.principal.PrincipalDirectoryRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.application.workitem.WorkProjectRepository;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.server.api.PrincipalDirectoryCursorCodec;
import io.crewscope.server.api.TeamActivityCursorKeyRing;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit composition root for the M8 Team Setup Readiness query. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PrincipalDirectoryQueryProperties.class)
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

    @Bean
    ConfigurationHealthApplicationService configurationHealthApplicationService(
            WorkItemAccessPolicy accessPolicy,
            TeamMembershipQuery memberships,
            AgentProfileRepository profiles,
            AgentConfigurationRepository configurations,
            ModelConnectionRepository modelConnections,
            ConnectionRepository connections,
            CredentialStore credentials,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        return new ConfigurationHealthApplicationService(
                accessPolicy, memberships, profiles, configurations, modelConnections,
                connections, credentials, transactions, timeProvider);
    }

    @Bean
    ConfigurationSearchApplicationService configurationSearchApplicationService(
            WorkItemAccessPolicy accessPolicy,
            TeamMembershipQuery memberships,
            AgentProfileRepository profiles,
            AgentConfigurationRepository configurations,
            TransactionExecutor transactions) {
        return new ConfigurationSearchApplicationService(
                accessPolicy, memberships, profiles, configurations, transactions);
    }

    @Bean
    PrincipalDirectoryAccessPolicy principalDirectoryAccessPolicy(WorkItemAccessPolicy accessPolicy) {
        return new PrincipalDirectoryAccessPolicy(accessPolicy);
    }

    @Bean
    PrincipalDirectoryQueryService principalDirectoryQueryService(
            PrincipalDirectoryAccessPolicy accessPolicy,
            AuditAuthorization auditAuthorization,
            PrincipalDirectoryRepository directory,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        return new PrincipalDirectoryQueryService(
                accessPolicy, auditAuthorization, directory, transactions, timeProvider);
    }

    @Bean
    PrincipalDirectoryCursorCodec principalDirectoryCursorCodec(
            TeamActivityCursorKeyRing keyRing, PrincipalDirectoryQueryProperties properties) {
        return new PrincipalDirectoryCursorCodec(
                keyRing, Clock.systemUTC(), properties.getCursorMaximumAge());
    }
}
