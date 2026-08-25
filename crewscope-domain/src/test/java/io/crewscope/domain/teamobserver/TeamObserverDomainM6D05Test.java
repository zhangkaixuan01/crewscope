package io.crewscope.domain.teamobserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentExecutionModelBinding;
import io.crewscope.domain.agent.AgentExecutionScope;
import io.crewscope.domain.agent.AgentOwnershipType;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.AgentToolKey;
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
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.workspace.AgentProfileStatus;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TeamObserverDomainM6D05Test {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-25T12:00:00Z");

    private Principal owner;
    private TeamInitialization team;
    private AgentTemplateDefinition template;
    private TeamObserverInitialization observer;

    @BeforeEach
    void setUp() {
        owner = activeUser("Owner");
        team = TeamInitialization.create(owner, "Platform", NOW);
        template = TeamObserverTemplate.create(ORGANIZATION_ID, owner.id(), NOW);
        observer = TeamObserverInitialization.createDefault(
                team.team(), team.defaultWorkspace(), team.ownerMember(), owner, template, NOW);
    }

    @Test
    void definesExactTeamOwnedReadOnlyTemplateSurface() {
        assertEquals(TeamObserverTemplate.VERSION, template.templateVersion());
        assertEquals(Set.of(AgentOwnershipType.TEAM), template.allowedOwnershipTypes());
        assertEquals(Set.of(AgentExecutionScope.TEAM), template.allowedExecutionScopes());
        assertEquals(TeamObserverTemplate.ALLOWED_TOOLS, template.policy().allowedTools());
        assertTrue(template.policy().approvedSkillKeys().isEmpty());
        assertTrue(template.policy().memberConfigurableSlots().isEmpty());
        assertTrue(template.policy().structuredOutputSchema().orElseThrow()
                .contains("pendingConfirmations"));
        assertTrue(template.policy().allowedTools().stream()
                .map(AgentToolKey::value)
                .allMatch(value -> value.endsWith(".read") || value.contains(".read-")));
        assertFalse(template.policy().allowedTools().contains(new AgentToolKey("task.create")));
    }

    @Test
    void createsStableDisabledTeamServicePrincipalAndProfile() {
        TeamObserverInitialization retry = TeamObserverInitialization.createDefault(
                team.team(),
                team.defaultWorkspace(),
                team.ownerMember(),
                owner,
                template,
                UtcTimestamp.parse("2026-08-25T12:01:00Z"));
        Principal anotherOwner = activeUser("Other owner");
        TeamInitialization anotherTeam = TeamInitialization.create(anotherOwner, "Other", NOW);

        assertEquals(observer.agentPrincipal().id(), retry.agentPrincipal().id());
        assertEquals(observer.agentProfile().id(), retry.agentProfile().id());
        assertEquals(PrincipalType.TEAM_AGENT, observer.agentPrincipal().type());
        assertEquals(PrincipalVisibility.TEAM, observer.agentPrincipal().visibility());
        assertEquals(PrincipalStatus.DISABLED, observer.agentPrincipal().status());
        assertEquals(AgentProfileStatus.DISABLED, observer.agentProfile().status());
        assertEquals(AgentOwnershipType.TEAM, observer.agentProfile().ownership().type());
        assertNotEquals(
                observer.agentPrincipal().id(),
                TeamObserverInitialization.stablePrincipalId(anotherTeam.team().id()));
    }

    @Test
    void reconstitutesTheSameMigrationPairWithoutChangingIdentityOrLifecycle() {
        Principal principal = observer.agentPrincipal();
        var profile = observer.agentProfile();
        TeamObserverInitialization restored = new TeamObserverInitialization(
                Principal.reconstitute(
                        principal.id(),
                        principal.scope(),
                        principal.type(),
                        principal.ownerPrincipalId(),
                        principal.displayName(),
                        principal.externalIdentity(),
                        principal.visibility(),
                        principal.status(),
                        principal.version(),
                        principal.lifecycle()),
                io.crewscope.domain.workspace.AgentProfile.reconstituteTemplateInstance(
                        profile.id(),
                        profile.scope(),
                        profile.workspaceId(),
                        profile.agentPrincipalId(),
                        profile.ownership(),
                        profile.runtimeRole(),
                        profile.templateVersion(),
                        profile.type(),
                        profile.defaultProfile(),
                        profile.status(),
                        profile.version(),
                        profile.audit()))
                .requireDefaultFor(team.team(), team.defaultWorkspace());

        assertEquals(observer.agentPrincipal().id(), restored.agentPrincipal().id());
        assertEquals(observer.agentProfile().id(), restored.agentProfile().id());
        assertEquals(observer.agentPrincipal().version(), restored.agentPrincipal().version());
        assertEquals(observer.agentProfile().version(), restored.agentProfile().version());
    }

    @Test
    void rejectsWidenedTemplateAndWrongTeamWorkspace() {
        AgentTemplateDefinition widened = io.crewscope.domain.agent.AgentTemplateDefinition
                .publishInitial(
                        io.crewscope.domain.agent.AgentTemplatePublisherScope.organization(
                                ORGANIZATION_ID),
                        new io.crewscope.domain.agent.AgentTemplateKey("team-observer"),
                        io.crewscope.domain.agent.AgentRuntimeRole.TEAM_COORDINATOR,
                        Set.of(AgentOwnershipType.TEAM),
                        Set.of(AgentExecutionScope.TEAM),
                        template.capabilities(),
                        io.crewscope.domain.agent.AgentTemplatePolicy.define(
                                "A widened observer.",
                                Set.of(new AgentToolKey("task.create")),
                                Set.of(),
                                Optional.of(TeamObserverTemplate.outputSchema()),
                                Set.of(),
                                Set.of(io.crewscope.domain.agent.AgentConfigurableSlot.MODEL_BINDING)),
                        owner.id(),
                        NOW);
        Principal otherOwner = activeUser("Other owner");
        TeamInitialization other = TeamInitialization.create(otherOwner, "Other", NOW);

        assertThrows(
                DomainValidationException.class,
                () -> TeamObserverInitialization.createDefault(
                        team.team(),
                        team.defaultWorkspace(),
                        team.ownerMember(),
                        owner,
                        widened,
                        NOW));
        assertThrows(
                DomainValidationException.class,
                () -> TeamObserverInitialization.createDefault(
                        team.team(),
                        other.defaultWorkspace(),
                        team.ownerMember(),
                        owner,
                        template,
                        NOW));
    }

    @Test
    void configuresWhileDisabledAndActivatesOnlyWithTeamBinding() {
        AgentConfigurationVersion configuration = teamConfiguration(observer, template);

        TeamObserverInitialization active = observer.activate(
                configuration, owner.id(), UtcTimestamp.parse("2026-08-25T12:01:00Z"));

        assertTrue(configuration.personalModelBinding().isEmpty());
        assertEquals(
                AgentExecutionScope.TEAM,
                configuration.teamModelBinding().orElseThrow().executionScope());
        assertEquals(PrincipalStatus.ACTIVE, active.agentPrincipal().status());
        assertEquals(AgentProfileStatus.ACTIVE, active.agentProfile().status());
        assertThrows(
                DomainValidationException.class,
                () -> active.activate(configuration, owner.id(), NOW));
    }

    @Test
    void requiresCurrentActiveMemberAndExactStructuredVisibility() {
        TeamSummaryRequest request = new TeamSummaryRequest(
                ORGANIZATION_ID, team.team().id(), team.ownerMember().id(), 5)
                .requireAuthorizedMember(team.ownerMember());
        TeamObserverInitialization active = observer.activate(
                teamConfiguration(observer, template), owner.id(), NOW);
        TeamSummaryEntry progress = entry(
                request, TeamSummarySection.PROGRESS, TeamSummaryDataScope.TEAM_ACTIVITY);

        TeamSummaryResult result = TeamSummaryResult.create(
                request,
                active.agentProfile(),
                NOW,
                List.of(progress),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        assertEquals(List.of(progress), result.progress());
        assertThrows(
                DomainValidationException.class,
                () -> request.requireAuthorizedMember(team.ownerMember().suspend(NOW)));
        assertThrows(
                DomainValidationException.class,
                () -> TeamSummaryResult.create(
                        request,
                        observer.agentProfile(),
                        NOW,
                        List.of(progress),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()));
    }

    @Test
    void rejectsCrossMemberEntriesWrongDataScopeAndExternalEvidence() {
        TeamSummaryRequest request = new TeamSummaryRequest(
                ORGANIZATION_ID, team.team().id(), team.ownerMember().id(), 1);
        TeamObserverInitialization active = observer.activate(
                teamConfiguration(observer, template), owner.id(), NOW);
        TeamSummaryEntry wrongMember = new TeamSummaryEntry(
                ORGANIZATION_ID,
                team.team().id(),
                io.crewscope.domain.team.TeamMemberId.generate(),
                TeamSummarySection.PROGRESS,
                TeamSummaryDataScope.TEAM_ACTIVITY,
                "Progress",
                "/work-items/1");

        assertThrows(
                DomainValidationException.class,
                () -> TeamSummaryResult.create(
                        request,
                        active.agentProfile(),
                        NOW,
                        List.of(wrongMember),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()));
        assertThrows(
                DomainValidationException.class,
                () -> new TeamSummaryEntry(
                        ORGANIZATION_ID,
                        team.team().id(),
                        team.ownerMember().id(),
                        TeamSummarySection.PENDING_CONFIRMATIONS,
                        TeamSummaryDataScope.ARTIFACT_SUMMARY,
                        "Pending",
                        "/confirmations/1"));
        assertThrows(
                DomainValidationException.class,
                () -> new TeamSummaryEntry(
                        ORGANIZATION_ID,
                        team.team().id(),
                        team.ownerMember().id(),
                        TeamSummarySection.PROGRESS,
                        TeamSummaryDataScope.TEAM_ACTIVITY,
                        "Progress",
                        "https://provider.example/private"));
        assertThrows(
                DomainValidationException.class,
                () -> new TeamSummaryEntry(
                        ORGANIZATION_ID,
                        team.team().id(),
                        team.ownerMember().id(),
                        TeamSummarySection.PROGRESS,
                        TeamSummaryDataScope.TEAM_ACTIVITY,
                        "Progress",
                        "/work-items/%2e%2e/private"));
        assertThrows(
                DomainValidationException.class,
                () -> new TeamSummaryEntry(
                        ORGANIZATION_ID,
                        team.team().id(),
                        team.ownerMember().id(),
                        TeamSummarySection.PROGRESS,
                        TeamSummaryDataScope.TEAM_ACTIVITY,
                        "Progress",
                        "/work-items/1\nX-Injected: true"));
        assertThrows(
                DomainValidationException.class,
                () -> new TeamSummaryEntry(
                        ORGANIZATION_ID,
                        team.team().id(),
                        team.ownerMember().id(),
                        TeamSummarySection.PROGRESS,
                        TeamSummaryDataScope.TEAM_ACTIVITY,
                        "Progress\u202Ecod.exe",
                        "/work-items/1"));
        assertThrows(
                DomainValidationException.class,
                () -> new TeamSummaryEntry(
                        ORGANIZATION_ID,
                        team.team().id(),
                        team.ownerMember().id(),
                        TeamSummarySection.PROGRESS,
                        TeamSummaryDataScope.TEAM_ACTIVITY,
                        "Progress",
                        "/work-items/\u200B1"));
    }

    private static AgentConfigurationVersion teamConfiguration(
            TeamObserverInitialization initialization, AgentTemplateDefinition definition) {
        return AgentConfigurationVersion.createInitial(
                initialization.agentProfile(),
                definition,
                Optional.empty(),
                Optional.empty(),
                Optional.of(AgentExecutionModelBinding.inheritTeamDefault()),
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                Optional.empty(),
                new PolicyPackReference(PolicyPackId.generate(), 1),
                SafeModelGenerateOptions.defaults(),
                initialization.agentPrincipal().ownerPrincipalId().orElseThrow(),
                NOW);
    }

    private static TeamSummaryEntry entry(
            TeamSummaryRequest request,
            TeamSummarySection section,
            TeamSummaryDataScope scope) {
        return new TeamSummaryEntry(
                request.organizationId(),
                request.teamId(),
                request.requestingMemberId(),
                section,
                scope,
                "Current progress is on track.",
                "/work-items/00000000-0000-0000-0000-000000000001");
    }

    private static Principal activeUser(String displayName) {
        return Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(ORGANIZATION_ID),
                PrincipalType.USER,
                Optional.empty(),
                displayName,
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
    }
}
