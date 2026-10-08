package io.crewscope.application.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
import io.crewscope.domain.agent.AgentModelPolicyConstraints;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.agent.SafeModelGenerateOptions;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.knowledge.distiller.KnowledgeDistillerInitialization;
import io.crewscope.domain.knowledge.distiller.KnowledgeDistillerTemplate;
import io.crewscope.domain.model.ModelDataRetentionMode;
import io.crewscope.domain.model.ModelRegion;
import io.crewscope.domain.policy.PolicyPackId;
import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.skill.distiller.SkillDistillerInitialization;
import io.crewscope.domain.skill.distiller.SkillDistillerTemplate;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileStatus;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for the lazily provisioned Distillers' first configuration Preflight:
 * their readiness services append the initial configuration while the durable pair is still
 * DISABLED (activation requires a current configuration), so Preflight must evaluate the
 * pending-ACTIVE view instead of rejecting with AGENT_UNAVAILABLE — found on the M10-Q02
 * real-model stack, where a fresh Team's first distillation could never provision.
 */
class AgentConfigurationApplicationServiceDistillerTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-07T04:00:00Z");

    @Test
    void preflightsDisabledSkillDistillerAsPendingActive() {
        Fixture skillDistiller = Fixture.skillDistiller();
        skillDistiller.assertAppendPreflightsPendingActive("skill-distiller-first-configuration");
    }

    @Test
    void preflightsDisabledKnowledgeDistillerAsPendingActive() {
        Fixture knowledgeDistiller = Fixture.knowledgeDistiller();
        knowledgeDistiller
                .assertAppendPreflightsPendingActive("knowledge-distiller-first-configuration");
    }

    /** Provisions one distiller's DISABLED profile from the same Team the mocks are wired to. */
    private interface DistillerProvisioner {
        AgentProfile provision(TeamInitialization team, Principal owner, AgentTemplateDefinition template);
    }

    private static final class Fixture {
        private final Principal owner;
        private final TeamInitialization team;
        private final AgentProfile profile;
        private final AgentProfileRepository profiles;
        private final AgentConfigurationRepository configurations;
        private final AgentExecutionConfigurationResolver resolver;
        private final AgentConfigurationApplicationService service;

        private Fixture(AgentTemplateDefinition template, DistillerProvisioner provisioner) {
            OrganizationId organizationId = template.publisherScope().organizationId();
            owner = activeUser(organizationId);
            team = TeamInitialization.create(owner, "Distiller regression", NOW);
            profile = provisioner.provision(team, owner, template);

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

        static Fixture skillDistiller() {
            return distiller(SkillDistillerTemplate.create(
                    OrganizationId.generate(), PrincipalId.generate(), NOW),
                    (team, owner, template) -> SkillDistillerInitialization.createDefault(
                            team.team(),
                            team.defaultWorkspace(),
                            team.ownerMember(),
                            owner,
                            template,
                            NOW).agentProfile());
        }

        static Fixture knowledgeDistiller() {
            return distiller(KnowledgeDistillerTemplate.create(
                    OrganizationId.generate(), PrincipalId.generate(), NOW),
                    (team, owner, template) -> KnowledgeDistillerInitialization.createDefault(
                            team.team(),
                            team.defaultWorkspace(),
                            team.ownerMember(),
                            owner,
                            template,
                            NOW).agentProfile());
        }

        private static Fixture distiller(
                AgentTemplateDefinition template, DistillerProvisioner provisioner) {
            return new Fixture(template, provisioner);
        }

        private void assertAppendPreflightsPendingActive(String key) {
            when(resolver.resolve(any(), any(), any(), any(), any(), any(), any()))
                    .thenAnswer(invocation -> {
                        AgentProfile preflightProfile = invocation.getArgument(0);
                        assertNotSame(profile, preflightProfile);
                        assertEquals(AgentProfileStatus.ACTIVE, preflightProfile.status());
                        assertEquals(AgentProfileStatus.DISABLED, profile.status());
                        return mock(ResolvedAgentExecutionConfiguration.class);
                    });

            service.append(context(key), team.team().id(), profile.id(), 0, teamDraft());

            verify(configurations).append(any());
            verify(profiles, never()).update(any());
            assertEquals(AgentProfileStatus.DISABLED, profile.status());
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
