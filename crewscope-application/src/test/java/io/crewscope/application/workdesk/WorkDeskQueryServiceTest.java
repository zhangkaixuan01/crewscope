package io.crewscope.application.workdesk;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Verifies WorkDesk delegates authorization and keeps the member identity server-derived. */
class WorkDeskQueryServiceTest {

  @Test
  void authorizesTheTeamBeforeBuildingTheMemberScopedQuery() {
    OrganizationId organizationId = OrganizationId.generate();
    TeamId teamId = TeamId.generate();
    TeamMember member = mock(TeamMember.class);
    Principal principal = mock(Principal.class);
    PrincipalId principalId = PrincipalId.generate();
    TeamAccessContext context = new TeamAccessContext(principal, false);
    WorkItemAccessPolicy itemPolicy = mock(WorkItemAccessPolicy.class);
    WorkDeskRepository repository = mock(WorkDeskRepository.class);
    WorkDeskAccessPolicy accessPolicy = new WorkDeskAccessPolicy(itemPolicy);
    WorkDeskQueryService service = new WorkDeskQueryService(repository, accessPolicy);
    WorkDeskSummary expected = new WorkDeskSummary(
        organizationId.toString(), teamId.toString(), Optional.empty(), Instant.parse("2026-09-13T00:00:00Z"), List.of());

    when(itemPolicy.requireVisibleTeamMember(context, organizationId, teamId)).thenReturn(member);
    when(member.id()).thenReturn(TeamMemberId.generate());
    when(member.userPrincipalId()).thenReturn(principalId);
    when(repository.summarize(any(WorkDeskQuery.class))).thenReturn(expected);

    WorkDeskSummary actual = service.summarize(
        context, organizationId, teamId, Optional.of(WorkProjectId.generate()),
        Optional.empty(), true);

    assertSame(expected, actual);
    verify(itemPolicy).requireVisibleTeamMember(context, organizationId, teamId);
    verify(repository).summarize(any(WorkDeskQuery.class));
  }

  @Test
  void passesTheValidatedScopeAndFiltersToTheRepository() {
    OrganizationId organizationId = OrganizationId.generate();
    TeamId teamId = TeamId.generate();
    TeamMember member = mock(TeamMember.class);
    Principal principal = mock(Principal.class);
    PrincipalId principalId = PrincipalId.generate();
    TeamAccessContext context = new TeamAccessContext(principal, false);
    WorkItemAccessPolicy itemPolicy = mock(WorkItemAccessPolicy.class);
    WorkDeskRepository repository = mock(WorkDeskRepository.class);
    WorkProjectId projectId = WorkProjectId.generate();
    when(itemPolicy.requireVisibleTeamMember(any(), eq(organizationId), eq(teamId))).thenReturn(member);
    when(member.id()).thenReturn(TeamMemberId.generate());
    when(member.userPrincipalId()).thenReturn(principalId);

    new WorkDeskQueryService(repository, new WorkDeskAccessPolicy(itemPolicy))
        .summarize(context, organizationId, teamId, Optional.of(projectId), Optional.empty(), true);

    org.mockito.ArgumentCaptor<WorkDeskQuery> captor = org.mockito.ArgumentCaptor.forClass(WorkDeskQuery.class);
    verify(repository).summarize(captor.capture());
    WorkDeskQuery query = captor.getValue();
    org.junit.jupiter.api.Assertions.assertEquals(projectId, query.projectId().orElseThrow());
    org.junit.jupiter.api.Assertions.assertTrue(query.onlyNeedsAction());
    org.junit.jupiter.api.Assertions.assertEquals(member.id(), query.memberId());
    org.junit.jupiter.api.Assertions.assertEquals(principalId, query.principalId());
  }
}
