package io.crewscope.application.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentExecutionAuthorizationFacts;
import io.crewscope.domain.agent.AgentExecutionScopeFacts;
import io.crewscope.domain.agent.AgentModelPolicyConstraints;
import io.crewscope.domain.agent.AgentModelPreflightException;
import io.crewscope.domain.agent.AgentModelPreflightRejectionCode;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.AgentTemplateHash;
import io.crewscope.domain.agent.AgentTemplatePublisherScope;
import io.crewscope.domain.agent.AgentTemplateVersion;
import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workspace.WorkspaceScope;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentExecutionConfigurationServiceM5I04Test {

    @Test
    void loadsTheCurrentConfigurationAndExactOrganizationTemplateBeforeResolving() {
        Fixture fixture = fixture();

        ResolvedAgentExecutionConfiguration actual = fixture.service().resolve(fixture.request());

        assertSame(fixture.resolved(), actual);
        verify(fixture.configurations()).findCurrent(
                fixture.organizationId(), fixture.profileId());
        verify(fixture.resolver()).resolve(
                fixture.profile(),
                fixture.template(),
                fixture.configuration(),
                fixture.request().scopeFacts(),
                fixture.request().policyConstraints(),
                fixture.request().authorization(),
                fixture.request().resolvedAt());
    }

    @Test
    void failsClosedWhenTeamAndOrganizationBothClaimTheExactTemplateCoordinate() {
        Fixture fixture = fixture();
        when(fixture.templates().findByVersion(
                        AgentTemplatePublisherScope.team(
                                fixture.organizationId(), fixture.teamId()),
                        fixture.configuration().templateVersion()))
                .thenReturn(Optional.of(fixture.template()));

        AgentModelPreflightException failure = assertThrows(
                AgentModelPreflightException.class,
                () -> fixture.service().resolve(fixture.request()));

        assertEquals(AgentModelPreflightRejectionCode.COORDINATE_MISMATCH, failure.reason());
    }

    private static Fixture fixture() {
        OrganizationId organizationId = OrganizationId.generate();
        TeamId teamId = TeamId.generate();
        AgentProfileId profileId = AgentProfileId.generate();
        AgentTemplateVersion templateVersion = AgentTemplateVersion.of("coding", 2);
        AgentTemplateHash templateHash = new AgentTemplateHash("1".repeat(64));
        AgentProfile profile = mock(AgentProfile.class);
        when(profile.scope()).thenReturn(WorkspaceScope.team(organizationId, teamId));
        when(profile.templateVersion()).thenReturn(templateVersion);
        AgentConfigurationVersion configuration = mock(AgentConfigurationVersion.class);
        when(configuration.templateVersion()).thenReturn(templateVersion);
        when(configuration.templateContentHash()).thenReturn(templateHash);
        AgentTemplateDefinition template = mock(AgentTemplateDefinition.class);
        when(template.contentHash()).thenReturn(templateHash);
        AgentProfileRepository profiles = mock(AgentProfileRepository.class);
        when(profiles.findById(organizationId, profileId)).thenReturn(Optional.of(profile));
        AgentConfigurationRepository configurations = mock(AgentConfigurationRepository.class);
        when(configurations.findCurrent(organizationId, profileId))
                .thenReturn(Optional.of(configuration));
        AgentTemplateRepository templates = mock(AgentTemplateRepository.class);
        when(templates.findByVersion(
                        AgentTemplatePublisherScope.team(organizationId, teamId),
                        templateVersion))
                .thenReturn(Optional.empty());
        when(templates.findByVersion(
                        AgentTemplatePublisherScope.organization(organizationId),
                        templateVersion))
                .thenReturn(Optional.of(template));
        AgentExecutionConfigurationResolver resolver =
                mock(AgentExecutionConfigurationResolver.class);
        ResolvedAgentExecutionConfiguration resolved =
                mock(ResolvedAgentExecutionConfiguration.class);
        AgentExecutionAuthorizationFacts authorization = new AgentExecutionAuthorizationFacts(
                PrincipalId.generate(), true, true, true, true, true, Set.of());
        AgentModelPolicyConstraints policy = new AgentModelPolicyConstraints(
                Set.of(),
                Set.of(new io.crewscope.domain.model.ModelRegion("global")),
                Set.of(io.crewscope.domain.model.ModelDataRetentionMode.NONE),
                Optional.of(Duration.ofDays(1)),
                false,
                1,
                1);
        ResolveAgentExecutionConfigurationRequest request =
                new ResolveAgentExecutionConfigurationRequest(
                        organizationId,
                        profileId,
                        Optional.empty(),
                        new AgentExecutionScopeFacts(false, false, false, false),
                        policy,
                        authorization,
                        UtcTimestamp.parse("2026-08-23T08:00:00Z"));
        when(resolver.resolve(
                        profile,
                        template,
                        configuration,
                        request.scopeFacts(),
                        policy,
                        authorization,
                        request.resolvedAt()))
                .thenReturn(resolved);
        return new Fixture(
                organizationId,
                teamId,
                profileId,
                profile,
                configuration,
                template,
                profiles,
                templates,
                configurations,
                resolver,
                resolved,
                new AgentExecutionConfigurationService(
                        profiles, templates, configurations, resolver),
                request);
    }

    private record Fixture(
            OrganizationId organizationId,
            TeamId teamId,
            AgentProfileId profileId,
            AgentProfile profile,
            AgentConfigurationVersion configuration,
            AgentTemplateDefinition template,
            AgentProfileRepository profiles,
            AgentTemplateRepository templates,
            AgentConfigurationRepository configurations,
            AgentExecutionConfigurationResolver resolver,
            ResolvedAgentExecutionConfiguration resolved,
            AgentExecutionConfigurationService service,
            ResolveAgentExecutionConfigurationRequest request) {}
}
