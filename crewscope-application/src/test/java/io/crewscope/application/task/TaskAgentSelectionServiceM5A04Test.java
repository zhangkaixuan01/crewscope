package io.crewscope.application.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.agent.AgentExecutionConfigurationService;
import io.crewscope.application.agent.AgentModelGovernance;
import io.crewscope.application.agent.AgentModelGovernanceSnapshot;
import io.crewscope.application.agent.ResolveAgentExecutionConfigurationRequest;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.model.ModelConnectionAvailabilityVerifier;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.domain.agent.AgentConfigurableSlot;
import io.crewscope.domain.agent.AgentConfigurationRevision;
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
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.model.ModelDataRetentionMode;
import io.crewscope.domain.model.ModelRegion;
import io.crewscope.domain.policy.PolicyPackId;
import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentStatus;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** M5-A04 server-owned PERSONAL/TEAM scope and responsibility revalidation tests. */
class TaskAgentSelectionServiceM5A04Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-24T18:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final Principal owner = user("Owner");
    private final TeamInitialization team = TeamInitialization.create(owner, "Delivery", NOW);
    private final Principal specialist = Principal.create(
            PrincipalId.generate(),
            PrincipalScope.team(organizationId, team.team().id()),
            PrincipalType.SPECIALIST_AGENT,
            Optional.of(owner.id()),
            "Coding specialist",
            Optional.empty(),
            PrincipalVisibility.PRIVATE,
            NOW);
    private final AgentTemplateDefinition template = template();
    private final AgentProfile personalProfile = AgentProfile.createTemplateInstance(
            AgentProfileId.generate(),
            team.defaultWorkspace(),
            specialist,
            AgentOwnership.user(organizationId, team.team().id(), team.ownerMember().id()),
            template,
            false,
            owner.id(),
            NOW);
    private final WorkItem workItem = workItem();
    private final List<ResponsibilityAssignment> responsibilityChain = List.of(
            assignment(ResponsibilityRole.OWNER, owner),
            assignment(ResponsibilityRole.EXECUTOR, specialist));

    private AgentProfileRepository profiles;
    private PrincipalRepository principals;
    private TeamMembershipQuery memberships;
    private AgentExecutionConfigurationService configurations;
    private ResolvedAgentExecutionConfiguration resolved;
    private TaskAgentSelectionService service;

    @BeforeEach
    void setUp() {
        profiles = mock(AgentProfileRepository.class);
        principals = mock(PrincipalRepository.class);
        TeamRepository teams = mock(TeamRepository.class);
        memberships = mock(TeamMembershipQuery.class);
        ModelConnectionRepository connections = mock(ModelConnectionRepository.class);
        ModelConnectionAvailabilityVerifier availability =
                mock(ModelConnectionAvailabilityVerifier.class);
        configurations = mock(AgentExecutionConfigurationService.class);
        resolved = mock(ResolvedAgentExecutionConfiguration.class);

        when(teams.findById(organizationId, team.team().id())).thenReturn(Optional.of(team.team()));
        when(memberships.findByTeam(organizationId, team.team().id()))
                .thenReturn(List.of(team.ownerMember()));
        when(profiles.findById(organizationId, personalProfile.id()))
                .thenReturn(Optional.of(personalProfile));
        when(principals.findById(organizationId, specialist.id()))
                .thenReturn(Optional.of(specialist));
        when(principals.findById(organizationId, owner.id())).thenReturn(Optional.of(owner));
        when(connections.findByOwner(any())).thenReturn(List.of());
        when(resolved.agentProfileId()).thenReturn(personalProfile.id());
        when(resolved.agentProfileVersion()).thenReturn(personalProfile.version());
        when(resolved.agentPrincipalId()).thenReturn(specialist.id());
        when(resolved.executionScope()).thenReturn(AgentExecutionScope.PERSONAL);
        when(configurations.resolve(any())).thenReturn(resolved);

        PolicyPackReference policyPack = new PolicyPackReference(PolicyPackId.generate(), 1);
        AgentModelGovernance governance = (actor, teamId, profile, usable) ->
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
        service = new TaskAgentSelectionService(
                profiles,
                principals,
                teams,
                memberships,
                connections,
                availability,
                governance,
                configurations);
    }

    @Test
    void resolvesOwnerOnlyResponsibilityChainAsPersonalAndForwardsExactRevision() {
        AgentConfigurationRevision revision = new AgentConfigurationRevision(3);

        TaskAgentExecutionSelection selection = service.resolve(
                new TeamAccessContext(owner, false),
                workItem,
                responsibilityChain,
                new TaskAgentSelectionRequest(personalProfile.id(), Optional.of(revision)),
                NOW);

        assertEquals(AgentExecutionScope.PERSONAL, selection.resolvedConfiguration().executionScope());
        ArgumentCaptor<ResolveAgentExecutionConfigurationRequest> request =
                ArgumentCaptor.forClass(ResolveAgentExecutionConfigurationRequest.class);
        verify(configurations).resolve(request.capture());
        assertEquals(Optional.of(revision), request.getValue().configurationRevision());
        assertEquals(
                AgentExecutionScope.PERSONAL,
                io.crewscope.domain.agent.AgentExecutionScopePolicy.resolve(
                        request.getValue().scopeFacts()));
    }

    @Test
    void forcesTeamOwnedAgentToTeamExecution() {
        Principal teamSpecialist = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.team(organizationId, team.team().id()),
                PrincipalType.SPECIALIST_AGENT,
                Optional.of(owner.id()),
                "Team coding specialist",
                Optional.empty(),
                PrincipalVisibility.TEAM,
                NOW);
        AgentProfile teamProfile = AgentProfile.createTemplateInstance(
                AgentProfileId.generate(),
                team.defaultWorkspace(),
                teamSpecialist,
                AgentOwnership.team(organizationId, team.team().id()),
                template,
                false,
                owner.id(),
                NOW);
        when(profiles.findById(organizationId, teamProfile.id())).thenReturn(Optional.of(teamProfile));
        when(principals.findById(organizationId, teamSpecialist.id()))
                .thenReturn(Optional.of(teamSpecialist));
        when(resolved.agentProfileId()).thenReturn(teamProfile.id());
        when(resolved.agentProfileVersion()).thenReturn(teamProfile.version());
        when(resolved.agentPrincipalId()).thenReturn(teamSpecialist.id());
        when(resolved.executionScope()).thenReturn(AgentExecutionScope.TEAM);

        TaskAgentExecutionSelection selection = service.resolve(
                new TeamAccessContext(owner, false),
                workItem,
                List.of(
                        assignment(ResponsibilityRole.OWNER, owner),
                        assignment(ResponsibilityRole.EXECUTOR, teamSpecialist)),
                TaskAgentSelectionRequest.current(teamProfile.id()),
                NOW);

        assertEquals(AgentExecutionScope.TEAM, selection.resolvedConfiguration().executionScope());
    }

    @Test
    void rejectsAUserOwnedAgentAfterItsOwnerLeavesTheTeam() {
        Principal delegate = user("Delegate");
        var delegateMember = team.team().joinMember(
                io.crewscope.domain.team.TeamMemberId.generate(),
                delegate,
                io.crewscope.domain.team.TeamJoinMethod.SCIM,
                NOW);
        when(memberships.findByTeam(organizationId, team.team().id()))
                .thenReturn(List.of(delegateMember));
        List<ResponsibilityAssignment> delegated = List.of(
                assignment(ResponsibilityRole.OWNER, delegate),
                assignment(ResponsibilityRole.EXECUTOR, specialist));

        assertThrows(
                io.crewscope.domain.agent.AgentModelPreflightException.class,
                () -> service.resolve(
                        new TeamAccessContext(delegate, false),
                        workItem,
                        delegated,
                        TaskAgentSelectionRequest.current(personalProfile.id()),
                        NOW));
    }

    private Principal user(String name) {
        return Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                name,
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
    }

    private WorkItem workItem() {
        WorkItemScope scope = new WorkItemScope(
                organizationId,
                team.team().id(),
                team.defaultWorkspace().id(),
                WorkProjectId.generate());
        return WorkItem.reconstitute(
                WorkItemId.generate(),
                scope,
                new WorkItemKey("CRW-504"),
                "Pin Agent execution configuration",
                WorkItemStatus.READY,
                2,
                AuditMetadata.createdBy(owner.id(), NOW));
    }

    private ResponsibilityAssignment assignment(ResponsibilityRole role, Principal principal) {
        return ResponsibilityAssignment.reconstitute(
                ResponsibilityAssignmentId.generate(),
                workItem.scope(),
                workItem.id(),
                role,
                principal.id(),
                principal.type(),
                role == ResponsibilityRole.OWNER
                        ? Optional.of(team.ownerMember().id())
                        : Optional.empty(),
                ResponsibilityAssignmentStatus.ACTIVE,
                owner.id(),
                NOW,
                NOW,
                Optional.empty(),
                Optional.empty(),
                0,
                AuditMetadata.createdBy(owner.id(), NOW));
    }

    private AgentTemplateDefinition template() {
        return AgentTemplateDefinition.publishInitial(
                AgentTemplatePublisherScope.organization(organizationId),
                new AgentTemplateKey("task-specialist"),
                AgentRuntimeRole.SPECIALIST,
                Set.of(AgentOwnershipType.USER, AgentOwnershipType.TEAM),
                Set.of(AgentExecutionScope.PERSONAL, AgentExecutionScope.TEAM),
                AgentTemplateCapabilities.define(
                        Set.of(new AgentTemplateCapability("task.execute")), Set.of()),
                AgentTemplatePolicy.define(
                        "Execute the assigned Task.",
                        Set.of(),
                        Set.of(),
                        Optional.empty(),
                        Set.of(AgentConfigurableSlot.MODEL_BINDING),
                        Set.of(AgentConfigurableSlot.MODEL_BINDING)),
                owner.id(),
                NOW);
    }
}
