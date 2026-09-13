package io.crewscope.application.setup;

import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.credential.CredentialAccessContext;
import io.crewscope.application.credential.CredentialDescriptor;
import io.crewscope.application.credential.CredentialStore;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionHealthStatus;
import io.crewscope.domain.model.ModelConnectionOwner;
import io.crewscope.domain.model.ModelConnectionStatus;
import io.crewscope.domain.provider.Connection;
import io.crewscope.domain.provider.ConnectionStatus;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberStatus;
import io.crewscope.domain.workspace.AgentProfile;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Derives configuration health at read time; no health state is persisted by this service. */
public final class ConfigurationHealthApplicationService {

    private static final int MAX_PROFILES = 200;
    private static final String GITHUB_CONNECTOR = "github-source-code";
    private static final String LARK_CONNECTOR = "lark-collaboration";

    private final WorkItemAccessPolicy accessPolicy;
    private final TeamMembershipQuery memberships;
    private final AgentProfileRepository profiles;
    private final AgentConfigurationRepository configurations;
    private final ModelConnectionRepository modelConnections;
    private final ConnectionRepository connections;
    private final CredentialStore credentials;
    private final io.crewscope.application.transaction.TransactionExecutor transactions;
    private final TimeProvider timeProvider;

    public ConfigurationHealthApplicationService(
            WorkItemAccessPolicy accessPolicy,
            TeamMembershipQuery memberships,
            AgentProfileRepository profiles,
            AgentConfigurationRepository configurations,
            ModelConnectionRepository modelConnections,
            ConnectionRepository connections,
            CredentialStore credentials,
            io.crewscope.application.transaction.TransactionExecutor transactions,
            TimeProvider timeProvider) {
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.modelConnections = Objects.requireNonNull(modelConnections, "modelConnections");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    /** Returns a member-safe snapshot. RuntimeEnvironment is accepted for API parity and future probes. */
    public ConfigurationHealthView get(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            RuntimeEnvironment environment) {
        return transactions.required(() -> {
            Team team = accessPolicy.requireVisibleTeam(context, organizationId, teamId);
            TeamMember member = memberships.findByTeam(organizationId, teamId).stream()
                    .filter(value -> value.status() == TeamMemberStatus.ACTIVE)
                    .filter(value -> value.userPrincipalId().equals(context.actor().id()))
                    .findFirst()
                    .orElseThrow(() -> new PolicyDeniedException("read Team configuration health"));
            UtcTimestamp now = timeProvider.now();
            List<AgentProfile> visibleProfiles = profiles.findVisibleToMember(
                    organizationId, teamId, member.id(), 0, MAX_PROFILES);
            List<ModelConnection> modelValues = new ArrayList<>();
            modelValues.addAll(modelConnections.findByOwner(ModelConnectionOwner.team(team)));
            modelValues.addAll(modelConnections.findByOwner(ModelConnectionOwner.organization(organizationId)));
            List<ConfigurationHealthItem> items = new ArrayList<>();
            items.add(agentConfiguration(visibleProfiles));
            items.add(modelHealth(modelValues));
            items.add(credentialHealth(modelValues, context.actor().id(), now));
            items.add(integrationHealth(connections.findByOwner(ProviderOwner.team(team))));
            ConfigurationHealthStatus overall = items.stream()
                    .map(ConfigurationHealthItem::status)
                    .min(Comparator.comparingInt(ConfigurationHealthApplicationService::severity))
                    .orElse(ConfigurationHealthStatus.UNAVAILABLE);
            return new ConfigurationHealthView(organizationId, teamId, now, overall, items);
        });
    }

    private ConfigurationHealthItem agentConfiguration(List<AgentProfile> profiles) {
        if (profiles.isEmpty()) {
            return action("AGENT_CONFIGURATION", "AGENT_CONFIGURATION_REQUIRED", "Team 管理员",
                    "OPEN_AGENT_SETTINGS");
        }
        boolean missing = profiles.stream().anyMatch(profile -> configurations.findCurrent(
                profile.scope().organizationId(), profile.id()).isEmpty());
        return missing
                ? action("AGENT_CONFIGURATION", "AGENT_CONFIGURATION_REQUIRED", "Team 管理员",
                        "OPEN_AGENT_SETTINGS")
                : ready("AGENT_CONFIGURATION", "Team 管理员");
    }

    private ConfigurationHealthItem modelHealth(List<ModelConnection> values) {
        if (values.isEmpty()) {
            return action("MODEL_CONNECTION", "MODEL_CONNECTION_REQUIRED", "Team 管理员",
                    "OPEN_MODEL_SETTINGS");
        }
        boolean healthy = values.stream().anyMatch(value -> value.status() == ModelConnectionStatus.ACTIVE
                && value.health().status() == ModelConnectionHealthStatus.HEALTHY
                && value.health().isHealthyFor(value.credentialBinding().credentialVersion()));
        return healthy ? ready("MODEL_CONNECTION", "Team 管理员")
                : action("MODEL_CONNECTION", "MODEL_CONNECTION_UNHEALTHY", "Team 管理员",
                        "OPEN_MODEL_SETTINGS");
    }

    private ConfigurationHealthItem credentialHealth(
            List<ModelConnection> values, io.crewscope.domain.shared.id.PrincipalId actor,
            UtcTimestamp now) {
        if (values.isEmpty()) {
            return ready("CREDENTIAL", "Team 管理员");
        }
        boolean unavailable = false;
        boolean expiring = false;
        for (ModelConnection value : values) {
            try {
                Optional<CredentialDescriptor> descriptor = credentials.describe(
                        new io.crewscope.application.credential.CredentialReference(
                                value.organizationId(), value.credentialBinding().credentialId()),
                        new CredentialAccessContext(value.organizationId(), actor,
                                java.util.Set.of(value.credentialBinding().credentialId()),
                                "model:configuration-health"));
                if (descriptor.isEmpty() || !descriptor.orElseThrow().isUsableAt(now)) {
                    unavailable = true;
                } else if (descriptor.orElseThrow().expiresAt()
                        .map(deadline -> Duration.between(now.value(), deadline.value()).toDays() <= 7)
                        .orElse(false)) {
                    expiring = true;
                }
            } catch (RuntimeException failure) {
                unavailable = true;
            }
        }
        if (unavailable) {
            return action("CREDENTIAL", "CREDENTIAL_UNAVAILABLE", "Team 管理员", "OPEN_MODEL_SETTINGS");
        }
        return expiring
                ? action("CREDENTIAL", "CREDENTIAL_EXPIRING", "Team 管理员", "OPEN_MODEL_SETTINGS")
                : ready("CREDENTIAL", "Team 管理员");
    }

    private ConfigurationHealthItem integrationHealth(List<Connection> values) {
        boolean github = values.stream().anyMatch(value -> GITHUB_CONNECTOR.equals(value.connectorKey())
                && value.status() == ConnectionStatus.ACTIVE);
        boolean lark = values.stream().anyMatch(value -> LARK_CONNECTOR.equals(value.connectorKey())
                && value.status() == ConnectionStatus.ACTIVE);
        if (github && lark) {
            return ready("INTEGRATION", "Team 管理员");
        }
        return action("INTEGRATION", "INTEGRATION_CONNECTION_REQUIRED", "Team 管理员",
                github ? "OPEN_LARK_SETTINGS" : "OPEN_GITHUB_SETTINGS");
    }

    private static ConfigurationHealthItem ready(String component, String responsibleParty) {
        return new ConfigurationHealthItem(component, ConfigurationHealthStatus.READY, "READY",
                responsibleParty, Optional.empty());
    }

    private static ConfigurationHealthItem action(
            String component, String reason, String responsibleParty, String actionKey) {
        return new ConfigurationHealthItem(component, ConfigurationHealthStatus.ACTION_REQUIRED,
                reason, responsibleParty, Optional.of(actionKey));
    }

    private static int severity(ConfigurationHealthStatus status) {
        return switch (status) {
            case UNAVAILABLE -> 0;
            case BLOCKED -> 1;
            case ACTION_REQUIRED -> 2;
            case READY -> 3;
        };
    }
}
