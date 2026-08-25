package io.crewscope.application.teamobserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.agent.AgentConfigurationRevision;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentExecutionModelBinding;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.SafeModelGenerateOptions;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalStatus;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.policy.PolicyPackId;
import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.teamobserver.TeamObserverInitialization;
import io.crewscope.domain.teamobserver.TeamObserverTemplate;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workspace.AgentProfileStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultTeamObserverServiceM6D05Test {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-25T13:00:00Z");

    private Principal owner;
    private TeamInitialization team;
    private AgentTemplateDefinition template;
    private InMemoryObservers observers;
    private InMemoryConfigurations configurations;
    private RecordingAdministration administration;
    private RecordingPreflight preflight;
    private DefaultTeamObserverService service;

    @BeforeEach
    void setUp() {
        owner = activeUser("Owner");
        team = TeamInitialization.create(owner, "Platform", NOW);
        template = TeamObserverTemplate.create(ORGANIZATION_ID, owner.id(), NOW);
        observers = new InMemoryObservers();
        configurations = new InMemoryConfigurations();
        administration = new RecordingAdministration();
        preflight = new RecordingPreflight();
        service = new DefaultTeamObserverService(
                observers,
                configurations,
                administration,
                preflight,
                new DirectTransactions(),
                new FixedTime());
    }

    @Test
    void provisionsExactlyOneStableDisabledObserverPerTeam() {
        TeamObserverInitialization first = ensure();
        TeamObserverInitialization replay = ensure();

        assertSame(first, replay);
        assertEquals(1, observers.values.size());
        assertEquals(PrincipalStatus.DISABLED, first.agentPrincipal().status());
        assertEquals(AgentProfileStatus.DISABLED, first.agentProfile().status());
    }

    @Test
    void rejectsRepositoryResultFromAnotherTeam() {
        Principal otherOwner = activeUser("Other owner");
        TeamInitialization other = TeamInitialization.create(otherOwner, "Other", NOW);
        AgentTemplateDefinition otherTemplate = TeamObserverTemplate.create(
                ORGANIZATION_ID, otherOwner.id(), NOW);
        observers.forcedResult = TeamObserverInitialization.createDefault(
                other.team(),
                other.defaultWorkspace(),
                other.ownerMember(),
                otherOwner,
                otherTemplate,
                NOW);

        assertThrows(DomainValidationException.class, this::ensure);
    }

    @Test
    void refusesActivationWithoutCurrentTeamConfiguration() {
        ensure();

        assertThrows(
                DomainValidationException.class,
                () -> service.activateDefault(
                        ORGANIZATION_ID, team.team().id(), owner));
        assertEquals(1, administration.calls);
        assertEquals(0, preflight.calls);
        assertEquals(0, observers.updateCalls);
    }

    @Test
    void activatesAtomicallyOnlyAfterAdministrationAndModelPreflight() {
        TeamObserverInitialization initialized = ensure();
        configurations.append(configuration(initialized));

        TeamObserverInitialization active = service.activateDefault(
                ORGANIZATION_ID, team.team().id(), owner);

        assertEquals(1, administration.calls);
        assertEquals(1, preflight.calls);
        assertEquals(1, observers.updateCalls);
        assertEquals(PrincipalStatus.ACTIVE, active.agentPrincipal().status());
        assertEquals(AgentProfileStatus.ACTIVE, active.agentProfile().status());
    }

    @Test
    void failedPreflightLeavesDisabledPairUnchanged() {
        TeamObserverInitialization initialized = ensure();
        configurations.append(configuration(initialized));
        preflight.ready = false;

        assertThrows(
                DomainValidationException.class,
                () -> service.activateDefault(
                        ORGANIZATION_ID, team.team().id(), owner));
        assertEquals(0, observers.updateCalls);
        assertEquals(
                AgentProfileStatus.DISABLED,
                observers.values.get(team.team().id()).agentProfile().status());
    }

    private TeamObserverInitialization ensure() {
        return service.ensureDefault(
                team.team(), team.defaultWorkspace(), team.ownerMember(), owner, template);
    }

    private AgentConfigurationVersion configuration(
            TeamObserverInitialization initialization) {
        return AgentConfigurationVersion.createInitial(
                initialization.agentProfile(),
                template,
                Optional.empty(),
                Optional.empty(),
                Optional.of(AgentExecutionModelBinding.inheritTeamDefault()),
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                Optional.empty(),
                new PolicyPackReference(PolicyPackId.generate(), 1),
                SafeModelGenerateOptions.defaults(),
                owner.id(),
                NOW);
    }

    private static Principal activeUser(String name) {
        return Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(ORGANIZATION_ID),
                PrincipalType.USER,
                Optional.empty(),
                name,
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
    }

    private static final class FixedTime implements TimeProvider {
        @Override
        public UtcTimestamp now() {
            return NOW;
        }
    }

    private static final class DirectTransactions implements TransactionExecutor {
        @Override
        public <T> T required(Supplier<T> operation) {
            return operation.get();
        }
    }

    private static final class InMemoryObservers implements DefaultTeamObserverRepository {
        private final Map<TeamId, TeamObserverInitialization> values = new HashMap<>();
        private TeamObserverInitialization forcedResult;
        private int updateCalls;

        @Override
        public TeamObserverInitialization initializeIfAbsent(
                TeamObserverInitialization candidate) {
            if (forcedResult != null) {
                return forcedResult;
            }
            TeamId teamId = candidate.agentProfile().ownership().teamId().orElseThrow();
            return values.computeIfAbsent(teamId, ignored -> candidate);
        }

        @Override
        public Optional<TeamObserverInitialization> findByTeam(
                OrganizationId organizationId, TeamId teamId) {
            return Optional.ofNullable(values.get(teamId))
                    .filter(value -> value.agentProfile().scope().organizationId()
                            .equals(organizationId));
        }

        @Override
        public TeamObserverInitialization updateLifecycle(
                TeamObserverInitialization initialization) {
            TeamId teamId = initialization.agentProfile().ownership().teamId().orElseThrow();
            TeamObserverInitialization current = values.get(teamId);
            if (current == null
                    || current.agentPrincipal().version() + 1
                            != initialization.agentPrincipal().version()
                    || current.agentProfile().version() + 1
                            != initialization.agentProfile().version()) {
                throw new DomainValidationException(
                        "teamObserver.version", "strong lifecycle version conflict");
            }
            updateCalls++;
            values.put(teamId, initialization);
            return initialization;
        }
    }

    private static final class InMemoryConfigurations implements AgentConfigurationRepository {
        private final Map<AgentProfileId, AgentConfigurationVersion> current = new HashMap<>();

        @Override
        public AgentConfigurationVersion append(AgentConfigurationVersion configuration) {
            current.put(configuration.agentProfileId(), configuration);
            return configuration;
        }

        @Override
        public Optional<AgentConfigurationVersion> findCurrent(
                OrganizationId organizationId, AgentProfileId agentProfileId) {
            return Optional.ofNullable(current.get(agentProfileId))
                    .filter(value -> value.organizationId().equals(organizationId));
        }

        @Override
        public Optional<AgentConfigurationVersion> findByRevision(
                OrganizationId organizationId,
                AgentProfileId agentProfileId,
                AgentConfigurationRevision revision) {
            return findCurrent(organizationId, agentProfileId)
                    .filter(value -> value.revision().equals(revision));
        }

        @Override
        public List<AgentConfigurationVersion> findAll(
                OrganizationId organizationId, AgentProfileId agentProfileId) {
            return findCurrent(organizationId, agentProfileId).stream().toList();
        }

        @Override
        public List<AgentConfigurationVersion> findPage(
                OrganizationId organizationId,
                AgentProfileId agentProfileId,
                int offset,
                int limit) {
            return findAll(organizationId, agentProfileId).stream()
                    .skip(offset)
                    .limit(limit)
                    .toList();
        }
    }

    private final class RecordingAdministration implements TeamObserverAdministration {
        private int calls;

        @Override
        public void requireAgentAdministrator(
                OrganizationId organizationId,
                TeamId teamId,
                Principal actor,
                UtcTimestamp occurredAt) {
            calls++;
            if (!ORGANIZATION_ID.equals(organizationId)
                    || !team.team().id().equals(teamId)
                    || !owner.id().equals(actor.id())
                    || !actor.canAct()) {
                throw new DomainValidationException(
                        "teamObserver.administrator", "requires AGENT_MANAGE");
            }
        }
    }

    private static final class RecordingPreflight implements TeamObserverModelPreflight {
        private int calls;
        private boolean ready = true;

        @Override
        public void requireReady(
                OrganizationId organizationId,
                TeamId teamId,
                AgentConfigurationVersion configuration) {
            calls++;
            if (!ready
                    || !configuration.organizationId().equals(organizationId)
                    || configuration.personalModelBinding().isPresent()
                    || configuration.teamModelBinding().isEmpty()) {
                throw new DomainValidationException(
                        "teamObserver.modelBinding", "TEAM model Preflight failed");
            }
        }
    }
}
