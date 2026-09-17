package io.crewscope.application.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.agent.AgentConfigurationRevision;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentTemplateVersion;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberStatus;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The search surface is metadata only: it must expose the field labels of the caller's visible
 * Agents and nothing else. Two properties are pinned here — the deep link it hands back has to be
 * one the Settings route actually reads, and a malformed query must fail as a domain error rather
 * than reaching the API error handler unmapped.
 */
class ConfigurationSearchApplicationServiceTest {

  private final OrganizationId organization = OrganizationId.generate();
  private final TeamId teamId = TeamId.generate();
  private final PrincipalId actorId = PrincipalId.generate();

  @Test
  void linksToTheAgentQueryParameterTheSettingsRouteReads() {
    AgentProfile profile = profile();
    ConfigurationSearchApplicationService service = service(List.of(profile));

    List<ConfigurationSearchResult> results = service.search(access(), organization, teamId, "模型");

    assertEquals(1, results.size());
    assertEquals("/settings/agents?agent=" + profile.id(), results.get(0).route());
    assertFalse(results.get(0).route().contains("agentId"));
  }

  @Test
  void matchesTheTemplateVersionKeyWithoutReturningTemplateValues() {
    ConfigurationSearchApplicationService service = service(List.of(profile()));

    List<ConfigurationSearchResult> results = service.search(
        access(), organization, teamId, "personal-assistant");

    assertEquals(List.of("模型绑定", "补充指令", "已批准技能", "记忆策略", "预算策略", "生成参数", "策略包"),
        results.stream().map(ConfigurationSearchResult::label).toList());
    assertTrue(results.stream().allMatch(result -> result.field().matches("[a-zA-Z]+")));
  }

  @Test
  void capsResultsAtOneHundred() {
    List<AgentProfile> profiles = new ArrayList<>();
    for (int index = 0; index < 200; index += 1) profiles.add(profile());
    ConfigurationSearchApplicationService service = service(profiles);

    assertEquals(100, service.search(access(), organization, teamId, "模型").size());
  }

  @Test
  void rejectsABlankQueryAsADomainError() {
    ConfigurationSearchApplicationService service = service(List.of(profile()));

    DomainValidationException failure = assertThrows(DomainValidationException.class,
        () -> service.search(access(), organization, teamId, "   "));

    assertEquals("q", failure.error().details().get("field"));
  }

  @Test
  void rejectsAQueryBeyondOneHundredCharacters() {
    ConfigurationSearchApplicationService service = service(List.of(profile()));

    assertThrows(DomainValidationException.class,
        () -> service.search(access(), organization, teamId, "模".repeat(101)));
  }

  private TeamAccessContext access() {
    Principal actor = mock(Principal.class);
    when(actor.id()).thenReturn(actorId);
    return new TeamAccessContext(actor, false);
  }

  private static AgentProfile profile() {
    AgentProfile profile = mock(AgentProfile.class);
    when(profile.id()).thenReturn(AgentProfileId.generate());
    return profile;
  }

  @SuppressWarnings("unchecked")
  private ConfigurationSearchApplicationService service(List<AgentProfile> profiles) {
    Principal actor = mock(Principal.class);
    when(actor.id()).thenReturn(actorId);
    TeamMember member = mock(TeamMember.class);
    when(member.status()).thenReturn(TeamMemberStatus.ACTIVE);
    when(member.userPrincipalId()).thenReturn(actorId);
    TeamMembershipQuery memberships = mock(TeamMembershipQuery.class);
    when(memberships.findByTeam(organization, teamId)).thenReturn(List.of(member));
    AgentProfileRepository profileRepository = mock(AgentProfileRepository.class);
    when(profileRepository.findVisibleToMember(organization, teamId, member.id(), 0, 200))
        .thenReturn(profiles);
    AgentConfigurationVersion configuration = mock(AgentConfigurationVersion.class);
    when(configuration.revision()).thenReturn(new AgentConfigurationRevision(2));
    when(configuration.templateVersion()).thenReturn(AgentTemplateVersion.of("personal-assistant", 3));
    AgentConfigurationRepository configurations = mock(AgentConfigurationRepository.class);
    when(configurations.findCurrent(any(), any())).thenReturn(Optional.of(configuration));
    TransactionExecutor transactions = mock(TransactionExecutor.class);
    when(transactions.required(any(Supplier.class))).thenAnswer(invocation ->
        ((Supplier<Object>) invocation.getArgument(0)).get());
    return new ConfigurationSearchApplicationService(
        mock(WorkItemAccessPolicy.class), memberships, profileRepository, configurations, transactions);
  }
}
