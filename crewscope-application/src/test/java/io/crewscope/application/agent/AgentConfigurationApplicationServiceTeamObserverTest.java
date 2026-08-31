package io.crewscope.application.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.model.ModelCatalogEntryRepository;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.model.SelectableModelCatalogService;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.agent.AgentConfigurableSlot;
import io.crewscope.domain.agent.AgentExecutionScope;
import io.crewscope.domain.agent.AgentModelPolicyConstraints;
import io.crewscope.domain.agent.AgentOwnership;
import io.crewscope.domain.agent.AgentOwnershipType;
import io.crewscope.domain.agent.AgentRuntimeRole;
import io.crewscope.domain.agent.AgentTemplateCapabilities;
import io.crewscope.domain.agent.AgentTemplateCapability;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.AgentTemplateKey;
import io.crewscope.domain.agent.AgentTemplatePolicy;
import io.crewscope.domain.agent.AgentTemplatePublisherScope;
import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.agent.SafeModelGenerateOptions;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.model.ModelDataRetentionMode;
import io.crewscope.domain.model.ModelRegion;
import io.crewscope.domain.policy.PolicyPackId;
import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.teamobserver.TeamObserverInitialization;
import io.crewscope.domain.teamobserver.TeamObserverTemplate;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileStatus;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Regression coverage for the disabled built-in Observer's first configuration Preflight. */
class AgentConfigurationApplicationServiceTeamObserverTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-31T04:00:00Z");

    @Test
    void preflightsDisabledObserverAsPendingActiveWithoutPersistingLifecycleEarly() {
        Fixture fixture = Fixture.observer();
        when(fixture.resolver.resolve(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    AgentProfile preflightProfile = invocation.getArgument(0);
                    assertNotSame(fixture.profile, preflightProfile);
                    assertEquals(AgentProfileStatus.ACTIVE, preflightProfile.status());
                    assertEquals(AgentProfileStatus.DISABLED, fixture.profile.status());
                    verify(fixture.profiles, never()).update(any());
                    return mock(ResolvedAgentExecutionConfiguration.class);
                });

        fixture.service.append(
                fixture.context("observer-first-configuration"),
                fixture.team.team().id(),
                fixture.profile.id(),
                0,
                teamDraft());

        verify(fixture.configurations).append(any());
        verify(fixture.profiles, never()).update(any());
        assertEquals(AgentProfileStatus.DISABLED, fixture.profile.status());
    }

    @Test
    void leavesOrdinaryDisabledAgentBehindTheExistingActiveExecutionGate() {
        Fixture fixture = Fixture.ordinaryTeamAgent();
        when(fixture.resolver.resolve(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    AgentProfile preflightProfile = invocation.getArgument(0);
                    if (preflightProfile.status() != AgentProfileStatus.ACTIVE) {
                        throw new DomainValidationException(
                                "agentProfile.status", "must be ACTIVE");
                    }
                    return mock(ResolvedAgentExecutionConfiguration.class);
                });

        assertThrows(
                DomainValidationException.class,
                () -> fixture.service.append(
                        fixture.context("ordinary-disabled-configuration"),
                        fixture.team.team().id(),
                        fixture.profile.id(),
                        0,
                        teamDraft()));

        verify(fixture.configurations, never()).append(any());
        verify(fixture.profiles, never()).update(any());
        assertEquals(AgentProfileStatus.DISABLED, fixture.profile.status());
    }

    private static AgentConfigurationDraft teamDraft() {
        return new AgentConfigurationDraft(
                Optional.empty(),
                Optional.of(AgentModelBindingDraft.inheritTeamDefault()),
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                Optional.empty(),
                SafeModelGenerateOptions.defaults());
    }

    private static final class Fixture {
        private final OrganizationId organizationId;
        private final Principal owner;
        private final TeamInitialization team;
        private final AgentProfile profile;
        private final AgentProfileRepository profiles;
        private final AgentConfigurationRepository configurations;
        private final AgentExecutionConfigurationResolver resolver;
        private final AgentConfigurationApplicationService service;

        private Fixture(AgentTemplateDefinition template, boolean observer) {
            organizationId = template.publisherScope().organizationId();
            owner = activeUser(organizationId);
            team = TeamInitialization.create(owner, "Observer regression", NOW);
            profile = observer
                    ? TeamObserverInitialization.createDefault(
                                    team.team(),
                                    team.defaultWorkspace(),
                                    team.ownerMember(),
                                    owner,
                                    template,
                                    NOW)
                            .agentProfile()
                    : disabledTeamAgent(template);

            profiles = mock(AgentProfileRepository.class);
            AgentTemplateRepository templates = mock(AgentTemplateRepository.class);
            configurations = mock(AgentConfigurationRepository.class);
            ModelConnectionRepository connections = mock(ModelConnectionRepository.class);
            ModelCatalogEntryRepository catalogs = mock(ModelCatalogEntryRepository.class);
            SelectableModelCatalogService selectable = mock(SelectableModelCatalogService.class);
            resolver = mock(AgentExecutionConfigurationResolver.class);
            TeamRepository teams = mock(TeamRepository.class);
            TeamMembershipQuery memberships = mock(TeamMembershipQuery.class);
            TeamRoleRepository roles = mock(TeamRoleRepository.class);
            MemberRoleRepository grants = mock(MemberRoleRepository.class);
            DomainEventStore events = mock(DomainEventStore.class);
            OutboxRepository outbox = mock(OutboxRepository.class);
            CommandReceiptStore receipts = mock(CommandReceiptStore.class);

            when(teams.findUninitializedById(organizationId, team.team().id()))
                    .thenReturn(Optional.empty());
            when(teams.findById(organizationId, team.team().id()))
                    .thenReturn(Optional.of(team.team()));
            when(memberships.findByTeam(organizationId, team.team().id()))
                    .thenReturn(List.of(team.ownerMember()));
            when(roles.findByTeam(organizationId, team.team().id()))
                    .thenReturn(team.builtInRoles());
            when(grants.findByMember(organizationId, team.ownerMember().id()))
                    .thenReturn(List.of(team.ownerRole()));
            when(profiles.findById(organizationId, profile.id())).thenReturn(Optional.of(profile));
            when(templates.findByVersion(template.publisherScope(), template.templateVersion()))
                    .thenReturn(Optional.of(template));
            when(connections.findByOwner(any())).thenReturn(List.of());
            when(configurations.findCurrent(organizationId, profile.id()))
                    .thenReturn(Optional.empty());
            when(configurations.append(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(receipts.findCompleted(any(), any(), any(), any())).thenReturn(Optional.empty());
            when(receipts.reserve(any())).thenReturn(CommandReservation.newlyAcquired());

            PolicyPackReference policyPack = new PolicyPackReference(PolicyPackId.generate(), 1);
            AgentModelGovernance governance = (requestingActor, teamId, agentProfile, usable) ->
                    new AgentModelGovernanceSnapshot(
                            policyPack,
                            new AgentModelPolicyConstraints(
                                    Set.of(),
                                    Set.of(new ModelRegion("global")),
                                    Set.of(ModelDataRetentionMode.NONE),
                                    Optional.empty(),
                                    true,
                                    1,
                                    1),
                            Set.of(),
                            Set.of());
            TransactionExecutor transactions = new TransactionExecutor() {
                @Override
                public <T> T required(Supplier<T> operation) {
                    return operation.get();
                }
            };
            service = new AgentConfigurationApplicationService(
                    profiles,
                    templates,
                    configurations,
                    connections,
                    catalogs,
                    selectable,
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
                    () -> NOW);
        }

        static Fixture observer() {
            OrganizationId organizationId = OrganizationId.generate();
            PrincipalId publisher = PrincipalId.generate();
            return new Fixture(TeamObserverTemplate.create(organizationId, publisher, NOW), true);
        }

        static Fixture ordinaryTeamAgent() {
            OrganizationId organizationId = OrganizationId.generate();
            PrincipalId publisher = PrincipalId.generate();
            AgentTemplateDefinition template = AgentTemplateDefinition.publishInitial(
                    AgentTemplatePublisherScope.organization(organizationId),
                    new AgentTemplateKey("ordinary-team-agent"),
                    AgentRuntimeRole.TEAM_COORDINATOR,
                    Set.of(AgentOwnershipType.TEAM),
                    Set.of(AgentExecutionScope.TEAM),
                    AgentTemplateCapabilities.define(
                            Set.of(new AgentTemplateCapability("team.coordinate")), Set.of()),
                    AgentTemplatePolicy.define(
                            "Coordinate approved Team work.",
                            Set.of(),
                            Set.of(),
                            Optional.empty(),
                            Set.of(),
                            Set.of(AgentConfigurableSlot.MODEL_BINDING)),
                    publisher,
                    NOW);
            return new Fixture(template, false);
        }

        private AgentProfile disabledTeamAgent(AgentTemplateDefinition template) {
            Principal agent = Principal.create(
                    PrincipalId.generate(),
                    PrincipalScope.team(organizationId, team.team().id()),
                    PrincipalType.TEAM_AGENT,
                    Optional.of(owner.id()),
                    "Ordinary Team Agent",
                    Optional.empty(),
                    PrincipalVisibility.TEAM,
                    NOW);
            return AgentProfile.createTemplateInstance(
                            io.crewscope.domain.workspace.AgentProfileId.generate(),
                            team.defaultWorkspace(),
                            agent,
                            AgentOwnership.team(organizationId, team.team().id()),
                            template,
                            false,
                            owner.id(),
                            NOW)
                    .disable(owner.id(), NOW);
        }

        private TeamCommandContext context(String key) {
            return new TeamCommandContext(
                    new TeamAccessContext(owner, false),
                    IdempotencyKey.from(key),
                    UUID.randomUUID(),
                    Optional.empty());
        }

        private static Principal activeUser(OrganizationId organizationId) {
            return Principal.create(
                    PrincipalId.generate(),
                    PrincipalScope.organization(organizationId),
                    PrincipalType.USER,
                    Optional.empty(),
                    "Owner",
                    Optional.empty(),
                    PrincipalVisibility.ORGANIZATION,
                    NOW);
        }
    }
}
