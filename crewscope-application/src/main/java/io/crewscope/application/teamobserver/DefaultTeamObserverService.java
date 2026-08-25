package io.crewscope.application.teamobserver;

import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.teamobserver.TeamObserverInitialization;
import io.crewscope.domain.workspace.Workspace;
import java.util.Objects;

/** Idempotent provisioning and guarded activation boundary for each Team's built-in Observer. */
public final class DefaultTeamObserverService {

    private final DefaultTeamObserverRepository observers;
    private final AgentConfigurationRepository configurations;
    private final TeamObserverAdministration administration;
    private final TeamObserverModelPreflight modelPreflight;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;

    public DefaultTeamObserverService(
            DefaultTeamObserverRepository observers,
            AgentConfigurationRepository configurations,
            TeamObserverAdministration administration,
            TeamObserverModelPreflight modelPreflight,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        this.observers = Objects.requireNonNull(observers, "observers");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.administration = Objects.requireNonNull(administration, "administration");
        this.modelPreflight = Objects.requireNonNull(modelPreflight, "modelPreflight");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    /** Returns one deterministic disabled pair for retries, migration replay and concurrent calls. */
    public TeamObserverInitialization ensureDefault(
            Team team,
            Workspace workspace,
            TeamMember ownerMember,
            Principal ownerUser,
            AgentTemplateDefinition template) {
        Team requiredTeam = Objects.requireNonNull(team, "team");
        Workspace requiredWorkspace = Objects.requireNonNull(workspace, "workspace");
        return transactions.required(() -> {
            TeamObserverInitialization candidate = TeamObserverInitialization.createDefault(
                    requiredTeam,
                    requiredWorkspace,
                    ownerMember,
                    ownerUser,
                    template,
                    timeProvider.now());
            TeamObserverInitialization resolved = Objects.requireNonNull(
                    observers.initializeIfAbsent(candidate),
                    "DefaultTeamObserverRepository.initializeIfAbsent result");
            return resolved.requireDefaultFor(requiredTeam, requiredWorkspace);
        });
    }

    /**
     * Enables the Observer only after current administration, TEAM configuration and model
     * Preflight checks; the synchronized Principal/Profile transition is one repository commit.
     */
    public TeamObserverInitialization activateDefault(
            OrganizationId organizationId, TeamId teamId, Principal actor) {
        OrganizationId requiredOrganization = Objects.requireNonNull(
                organizationId, "organizationId");
        TeamId requiredTeam = Objects.requireNonNull(teamId, "teamId");
        Principal requiredActor = Objects.requireNonNull(actor, "actor");
        return transactions.required(() -> {
            UtcTimestamp now = timeProvider.now();
            administration.requireAgentAdministrator(
                    requiredOrganization, requiredTeam, requiredActor, now);
            TeamObserverInitialization current = observers
                    .findByTeam(requiredOrganization, requiredTeam)
                    .orElseThrow(() -> new DomainValidationException(
                            "teamObserver", "the Team has no initialized built-in Observer"));
            var configuration = configurations
                    .findCurrent(requiredOrganization, current.agentProfile().id())
                    .orElseThrow(() -> new DomainValidationException(
                            "teamObserver.configuration",
                            "a current TEAM model configuration is required before activation"));
            current.requireActivationConfiguration(configuration);
            // Preflight resolves inherited defaults and current Connection/catalog/policy facts.
            modelPreflight.requireReady(requiredOrganization, requiredTeam, configuration);
            return observers.updateLifecycle(current.activate(
                    configuration, requiredActor.id(), now));
        });
    }
}
