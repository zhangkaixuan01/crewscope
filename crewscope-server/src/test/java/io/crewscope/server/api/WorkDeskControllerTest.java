package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.workdesk.WorkDeskQueryService;
import io.crewscope.application.workdesk.WorkDeskSummary;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Proves WorkDesk parsing, server-resolved identity and response envelope behavior. */
class WorkDeskControllerTest {
  private final OrganizationId organizationId = OrganizationId.generate();
  private final TeamId teamId = TeamId.generate();
  private final WorkProjectId projectId = WorkProjectId.generate();
  private WorkDeskQueryService service;
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    service = mock(WorkDeskQueryService.class);
    TeamRequestIdentityResolver resolver =
        (authentication, organization, correlationId) ->
            Mono.just(new TeamAccessContext(mock(Principal.class), false));
    client = WebTestClient.bindToController(new WorkDeskController(service, resolver))
        .controllerAdvice(new ApiExceptionHandler()).build();
  }

  @Test
  void returnsTheDerivedSummaryWithoutAcceptingAMemberId() {
    WorkDeskSummary summary = new WorkDeskSummary(
        organizationId.toString(), teamId.toString(), Optional.of(projectId.toString()),
        Instant.parse("2026-09-13T00:00:00Z"), List.of());
    when(service.summarize(any(), eq(organizationId), eq(teamId), eq(Optional.of(projectId)), any(), eq(true)))
        .thenReturn(summary);

    client.get().uri("/api/v1/organizations/{organizationId}/teams/{teamId}/work-desk?projectId={projectId}&onlyNeedsAction=true",
            organizationId, teamId, projectId)
        .exchange().expectStatus().isOk().expectHeader().valueEquals("Cache-Control", "no-store")
        .expectBody().jsonPath("$.organizationId").isEqualTo(organizationId.toString())
        .jsonPath("$.projectId").isEqualTo(projectId.toString());
    verify(service).summarize(any(), eq(organizationId), eq(teamId), eq(Optional.of(projectId)), any(), eq(true));
  }

  @Test
  void rejectsMalformedScopeProjectAndRoleBeforeCallingTheService() {
    client.get().uri("/api/v1/organizations/not-an-id/teams/{teamId}/work-desk", teamId)
        .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("invalid_request");
    client.get().uri("/api/v1/organizations/{organizationId}/teams/{teamId}/work-desk?projectId=bad", organizationId, teamId)
        .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("invalid_request");
    client.get().uri("/api/v1/organizations/{organizationId}/teams/{teamId}/work-desk?responsibilityRole=unknown", organizationId, teamId)
        .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("invalid_request");
  }
}
