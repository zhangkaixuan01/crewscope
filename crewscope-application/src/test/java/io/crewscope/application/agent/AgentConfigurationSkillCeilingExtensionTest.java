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
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * M10-A03b save-boundary ceiling: only the Coding template's saves widen the immutable
 * template ceiling with the Team's currently published keys, and only through the
 * caller-proven contributor — every other template and a missing contributor keep the
 * exact A03a rejection of foreign keys.
 */
class AgentConfigurationSkillCeilingExtensionTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-04T09:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final Principal actor = Principal.create(
            PrincipalId.generate(),
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            "Coding owner",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);
    private final TeamInitialization team =
            TeamInitialization.create(actor, "Ceiling team", NOW);
    private final AgentTemplateDefinition template = template("coding");
    private final Principal specialist = Principal.create(
            PrincipalId.generate(),
            PrincipalScope.team(organizationId, team.team().id()),
            PrincipalType.SPECIALIST_AGENT,
            Optional.of(actor.id()),
            "Coding specialist",
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
    private AgentTemplateRepository templates;
    private AgentConfigurationRepository configurations;
    private AgentConfigurationApplicationService service;
    private AgentConfigurationApplicationService bareService;
    private final AtomicInteger contributorCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        profiles = mock(AgentProfileRepository.class);
        templates = mock(AgentTemplateRepository.class);
        configurations = mock(AgentConfigurationRepository.class);
        ModelConnectionRepository connections = mock(ModelConnectionRepository.class);
        ModelCatalogEntryRepository catalogs = mock(ModelCatalogEntryRepository.class);
        SelectableModelCatalogService selectable = mock(SelectableModelCatalogService.class);
        AgentExecutionConfigurationResolver resolver = mock(AgentExecutionConfigurationResolver.class);
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
        // The published-domain contributor mirrors TeamSkillCatalogCeilingContributor's proof.
        ApprovedSkillCeilingContributor contributor = (organization, team) -> {
            contributorCalls.incrementAndGet();
            return Set.of("team-skill-x", "team-skill-y");
        };
        service = new AgentConfigurationApplicationService(
                profiles, templates, configurations, connections, catalogs, selectable,
                resolver, governance, teams, memberships, roles, grants, events, outbox,
                receipts, transactions, () -> NOW, contributor);
        bareService = new AgentConfigurationApplicationService(
                profiles, templates, configurations, connections, catalogs, selectable,
                resolver, governance, teams, memberships, roles, grants, events, outbox,
                receipts, transactions, () -> NOW);
    }

    @Test
    void codingSavesAdmitPublishedTeamSkillKeysThroughTheContributor() {
        AgentConfigurationVersion committed = service.append(
                        context("ceiling-widen"), team.team().id(), profile.id(), 0,
                        draft(Set.of("coding-baseline", "team-skill-x")))
                .result().orElseThrow();

        assertEquals(Set.of("coding-baseline", "team-skill-x"), committed.approvedSkillKeys());
        assertEquals(1, contributorCalls.get());
        verify(configurations).append(any());
    }

    @Test
    void keysOutsideTheProvenDomainStayRejectedOnCodingSaves() {
        assertThrows(
                DomainValidationException.class,
                () -> service.append(
                        context("ceiling-rogue"), team.team().id(), profile.id(), 0,
                        draft(Set.of("rogue-skill"))));

        verify(configurations, never()).append(any());
    }

    @Test
    void nonCodingTemplatesNeverConsultTheContributorAndKeepTheTemplateCeiling() {
        AgentTemplateDefinition stranger = template("reviewer");
        when(templates.findByVersion(stranger.publisherScope(), stranger.templateVersion()))
                .thenReturn(Optional.of(stranger));
        AgentProfile strangerProfile = AgentProfile.createTemplateInstance(
                profile.id(), team.defaultWorkspace(), specialist,
                profile.ownership(), stranger, false, actor.id(), NOW);
        when(profiles.findById(organizationId, profile.id()))
                .thenReturn(Optional.of(strangerProfile));

        assertThrows(
                DomainValidationException.class,
                () -> service.append(
                        context("ceiling-non-coding"), team.team().id(), profile.id(), 0,
                        draft(Set.of("team-skill-x"))));

        assertEquals(0, contributorCalls.get(),
                "the ceiling extension is consulted only for the Coding template");
        verify(configurations, never()).append(any());
    }

    @Test
    void missingContributorKeepsTheExactA03aRejectionForCodingSaves() {
        assertThrows(
                DomainValidationException.class,
                () -> bareService.append(
                        context("ceiling-bare"), team.team().id(), profile.id(), 0,
                        draft(Set.of("team-skill-x"))));
        verify(configurations, never()).append(any());
    }

    private AgentConfigurationDraft draft(Set<String> approvedSkillKeys) {
        return new AgentConfigurationDraft(
                Optional.empty(),
                Optional.of(AgentModelBindingDraft.inheritTeamDefault()),
                Optional.empty(),
                approvedSkillKeys,
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

    private AgentTemplateDefinition template(String key) {
        return AgentTemplateDefinition.publishInitial(
                AgentTemplatePublisherScope.organization(organizationId),
                new AgentTemplateKey(key),
                AgentRuntimeRole.SPECIALIST,
                Set.of(AgentOwnershipType.USER),
                Set.of(AgentExecutionScope.TEAM),
                AgentTemplateCapabilities.define(
                        Set.of(new AgentTemplateCapability("source-code.change")), Set.of()),
                AgentTemplatePolicy.define(
                        key + " baseline prompt.",
                        Set.of(),
                        Set.of("coding-baseline"),
                        Optional.empty(),
                        Set.of(AgentConfigurableSlot.SUPPLEMENTAL_INSTRUCTIONS,
                                AgentConfigurableSlot.APPROVED_SKILLS),
                        Set.of(AgentConfigurableSlot.MODEL_BINDING)),
                actor.id(),
                NOW);
    }
}
