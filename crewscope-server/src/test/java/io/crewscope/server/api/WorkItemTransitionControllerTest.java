package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.workitem.WorkItemAvailableTransition;
import io.crewscope.application.workitem.WorkItemTransitionAvailabilityQueryService;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Proves the transition availability endpoint preserves safe, server-scoped contracts. */
class WorkItemTransitionControllerTest {
  private final OrganizationId organizationId = OrganizationId.generate();
  private final TeamId teamId = TeamId.generate();
  private final WorkProjectId projectId = WorkProjectId.generate();
  private final WorkItemId workItemId = WorkItemId.generate();
  private WorkItemTransitionAvailabilityQueryService service;
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    service = mock(WorkItemTransitionAvailabilityQueryService.class);
    TeamRequestIdentityResolver resolver =
        (authentication, organization, correlationId) ->
            Mono.just(new TeamAccessContext(mock(Principal.class), false));
    client = WebTestClient.bindToController(new WorkItemTransitionController(service, resolver))
        .controllerAdvice(new ApiExceptionHandler()).build();
  }

  @Test
  void returnsTheRuntimeTransitionProjection() {
    WorkItemAvailableTransition transition = WorkItemAvailableTransition.enabled(
        io.crewscope.domain.workitem.WorkItemTransitionCatalog.from(WorkItemStatus.BACKLOG).get(0));
    when(service.list(any())).thenReturn(List.of(transition));

    client.get().uri("/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/transitions/availability",
            organizationId, teamId, projectId, workItemId)
        .exchange().expectStatus().isOk().expectHeader().valueEquals("Cache-Control", "no-store")
        .expectBody().jsonPath("$.transitions[0].actionId").isEqualTo(transition.actionId())
        .jsonPath("$.transitions[0].enabled").isEqualTo(true)
        .jsonPath("$.transitions[0].reason").doesNotExist();
  }

  @Test
  void rejectsMalformedIdentifiersBeforeServiceInvocation() {
    client.get().uri("/api/v1/organizations/not-an-id/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/transitions/availability",
            teamId, projectId, workItemId)
        .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("invalid_request");
  }
}
