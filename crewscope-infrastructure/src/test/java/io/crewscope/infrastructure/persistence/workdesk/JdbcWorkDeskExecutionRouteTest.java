package io.crewscope.infrastructure.persistence.workdesk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.workdesk.WorkDeskQuery;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The Task Execution section deep-links into the board. The board only opens a Task detail from
 * `task=`, so the route has to carry the Task coordinate next to the execution one — an execution
 * alone lands the member on an empty board, which is what made this link unusable.
 */
class JdbcWorkDeskExecutionRouteTest {

  @Test
  void carriesTheTaskCoordinateNextToTheExecution() {
    TeamId teamId = TeamId.generate();
    WorkProjectId projectId = WorkProjectId.generate();
    UUID taskId = UUID.randomUUID();
    UUID executionId = UUID.randomUUID();

    String route = JdbcWorkDeskRepositoryAdapter.taskExecutionRoute(
        query(teamId, projectId), projectId.value(), taskId, executionId.toString());

    assertEquals("/work?team=" + teamId + "&project=" + projectId
        + "&task=" + taskId + "&taskExecution=" + executionId, route);
  }

  @Test
  void keepsTheTeamScopeOnTheExecutionRoute() {
    TeamId teamId = TeamId.generate();
    WorkProjectId projectId = WorkProjectId.generate();

    String route = JdbcWorkDeskRepositoryAdapter.taskExecutionRoute(
        query(teamId, projectId), projectId.value(), UUID.randomUUID(), UUID.randomUUID().toString());

    assertTrue(route.startsWith("/work?team=" + teamId + "&project=" + projectId));
  }

  private static WorkDeskQuery query(TeamId teamId, WorkProjectId projectId) {
    return new WorkDeskQuery(OrganizationId.generate(), teamId, TeamMemberId.generate(),
        PrincipalId.generate(), Optional.of(projectId), Optional.empty(), false);
  }
}
