package io.crewscope.application.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.agent.AgentConfigurableSlot;
import io.crewscope.domain.agent.AgentConfigurationVersion;
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
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** M5-A03 owner isolation, inherited TEAM binding, Revision and atomic evidence tests. */
class AgentConfigurationApplicationServiceM5A03Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-24T16:30:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final Principal actor = Principal.create(
            PrincipalId.generate(),
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            "Specialist owner",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);
    private final TeamInitialization team = TeamInitialization.create(actor, "Configuration team", NOW);
    private final AgentTemplateDefinition template = template();
    private final Principal specialist = Principal.create(
            PrincipalId.generate(),
            PrincipalScope.team(organizationId, team.team().id()),
            PrincipalType.SPECIALIST_AGENT,
            Optional.of(actor.id()),
            "Team-scope specialist",
            Optional.empty(),
            PrincipalVisibility.PRIVATE,
            NOW);
    private final AgentProfile profile = AgentProfile.createTemplateInstance(
            AgentProfileId.generate(),
            team.defaultWorkspace(),
            specialist,
            AgentOwnership.user(organizationId, team.team().id(), team.ownerMember().id()),
            template,
            false,
            actor.id(),
            NOW);

    private AgentProfileRepository profiles;
    private AgentConfigurationRepository configurations;
    private CommandReceiptStore receipts;
    private DomainEventStore events;
    private OutboxRepository outbox;
    private AgentConfigurationApplicationService service;

    @BeforeEach
    void setUp() {
        profiles = mock(AgentProfileRepository.class);
        AgentTemplateRepository templates = mock(AgentTemplateRepository.class);
        configurations = mock(AgentConfigurationRepository.class);
        ModelConnectionRepository connections = mock(ModelConnectionRepository.class);
        ModelCatalogEntryRepository catalogs = mock(ModelCatalogEntryRepository.class);
        SelectableModelCatalogService selectable = mock(SelectableModelCatalogService.class);
        AgentExecutionConfigurationResolver resolver = mock(AgentExecutionConfigurationResolver.class);
        TeamRepository teams = mock(TeamRepository.class);
        TeamMembershipQuery memberships = mock(TeamMembershipQuery.class);
        TeamRoleRepository roles = mock(TeamRoleRepository.class);
        MemberRoleRepository grants = mock(MemberRoleRepository.class);
        events = mock(DomainEventStore.class);
        outbox = mock(OutboxRepository.class);
        receipts = mock(CommandReceiptStore.class);

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
        when(configurations.findCurrent(organizationId, profile.id())).thenReturn(Optional.empty());
        when(configurations.append(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(receipts.findCompleted(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(receipts.reserve(any())).thenReturn(CommandReservation.newlyAcquired());
        when(resolver.resolve(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mock(ResolvedAgentExecutionConfiguration.class));

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

    @Test
    void appendsContinuousImmutableRevisionsAndPreflightsInheritedTeamBinding() {
        AgentConfigurationVersion first = service.append(
                        context("m5-a03-first"), team.team().id(), profile.id(), 0, draft())
                .result().orElseThrow();
        when(configurations.findCurrent(organizationId, profile.id()))
                .thenReturn(Optional.of(first));

        AgentConfigurationVersion second = service.append(
                        context("m5-a03-second"), team.team().id(), profile.id(), 1, draft())
                .result().orElseThrow();

        assertEquals(1, first.revision().value());
        assertEquals(2, second.revision().value());
        assertEquals(1, second.previousRevision().orElseThrow().value());
        assertEquals(
                io.crewscope.domain.agent.AgentModelBindingKind.INHERIT_TEAM_DEFAULT,
                second.teamModelBinding().orElseThrow().kind());
        verify(configurations, org.mockito.Mockito.times(2)).append(any());
        ArgumentCaptor<DomainEventEnvelope<?>> emitted =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(events, org.mockito.Mockito.times(2)).append(emitted.capture());
        assertEquals(
                List.of(0L, 1L),
                emitted.getAllValues().stream().map(DomainEventEnvelope::aggregateVersion).toList());
        verify(outbox, org.mockito.Mockito.times(2)).enqueue(any());
        verify(receipts, org.mockito.Mockito.times(2)).complete(any(), any(), any(), any());
    }

    @Test
    void deniesAUserAgentOwnedByAnotherMemberBeforeReservingTheCommand() {
        AgentProfile foreign = AgentProfile.reconstituteTemplateInstance(
                profile.id(),
                profile.scope(),
                profile.workspaceId(),
                profile.agentPrincipalId(),
                AgentOwnership.user(
                        organizationId,
                        team.team().id(),
                        io.crewscope.domain.team.TeamMemberId.generate()),
                profile.runtimeRole(),
                profile.templateVersion(),
                profile.type(),
                false,
                profile.status(),
                profile.version(),
                profile.audit());
        when(profiles.findById(organizationId, profile.id())).thenReturn(Optional.of(foreign));

        assertThrows(
                PolicyDeniedException.class,
                () -> service.append(
                        context("m5-a03-cross-owner"),
                        team.team().id(),
                        profile.id(),
                        0,
                        draft()));

        verify(receipts, never()).reserve(any());
        verify(configurations, never()).append(any());
    }

    private AgentConfigurationDraft draft() {
        return new AgentConfigurationDraft(
                Optional.empty(),
                Optional.of(AgentModelBindingDraft.inheritTeamDefault()),
                Optional.of("Keep the result concise."),
                Set.of(),
                Optional.empty(),
                Optional.empty(),
                SafeModelGenerateOptions.defaults());
    }

    private TeamCommandContext context(String key) {
        return new TeamCommandContext(
                new TeamAccessContext(actor, false),
                IdempotencyKey.from(key),
                UUID.randomUUID(),
                Optional.empty());
    }

    private AgentTemplateDefinition template() {
        return AgentTemplateDefinition.publishInitial(
                AgentTemplatePublisherScope.organization(organizationId),
                new AgentTemplateKey("team-scope-specialist"),
                AgentRuntimeRole.SPECIALIST,
                Set.of(AgentOwnershipType.USER),
                Set.of(AgentExecutionScope.TEAM),
                AgentTemplateCapabilities.define(
                        Set.of(new AgentTemplateCapability("team.execute")), Set.of()),
                AgentTemplatePolicy.define(
                        "Approved Team execution baseline.",
                        Set.of(),
                        Set.of(),
                        Optional.empty(),
                        Set.of(AgentConfigurableSlot.SUPPLEMENTAL_INSTRUCTIONS),
                        Set.of(AgentConfigurableSlot.MODEL_BINDING)),
                actor.id(),
                NOW);
    }
}
