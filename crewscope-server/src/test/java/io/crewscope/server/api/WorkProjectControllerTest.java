package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.workitem.WorkProjectApplicationService;
import io.crewscope.application.workitem.WorkProjectCursor;
import io.crewscope.application.workitem.WorkProjectKeyAvailability;
import io.crewscope.application.workitem.WorkProjectPage;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.WorkProjectKey;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Proves WorkProject routes, cursor envelope, ETag, key check and safe validation. */
class WorkProjectControllerTest {

  private final OrganizationId organizationId = OrganizationId.generate();
  private final Principal actor =
      Principal.create(
          PrincipalId.generate(),
          PrincipalScope.organization(organizationId),
          PrincipalType.USER,
          Optional.empty(),
          "Owner",
          Optional.empty(),
          PrincipalVisibility.ORGANIZATION,
          UtcTimestamp.parse("2026-08-08T06:00:00Z"));
  private final TeamInitialization initialization =
      TeamInitialization.create(actor, "Platform", UtcTimestamp.parse("2026-08-08T06:00:00Z"));
  private final WorkProject project =
      WorkProject.create(
          WorkProjectId.generate(),
          new WorkProjectKey("CRW"),
          "CrewScope",
          initialization.team(),
          initialization.defaultWorkspace(),
          actor,
          UtcTimestamp.parse("2026-08-08T06:00:00Z"));

  private WorkProjectApplicationService service;
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    service = mock(WorkProjectApplicationService.class);
    TeamRequestIdentityResolver resolver =
        (authentication, organization, correlationId) ->
            Mono.just(new TeamAccessContext(actor, false));
    client =
        WebTestClient.bindToController(new WorkProjectController(service, resolver))
            .controllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @Test
  void createsAProjectUsingTheSharedAcceptedReceiptContract() {
    CommandReceipt receipt =
        new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID());
    when(service.create(any(), any(), any()))
        .thenReturn(CommandExecution.completed(project, receipt));

    client
        .post()
        .uri(
            "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects",
            organizationId,
            initialization.team().id())
        .header(ApiHeaders.IDEMPOTENCY_KEY, "create-project-http-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"key\":\"CRW\",\"name\":\"CrewScope\"}")
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.domainEventId")
        .isEqualTo(receipt.domainEventId().toString())
        .jsonPath("$.committedVersion")
        .isEqualTo(0);
  }

  @Test
  void listsProjectsWithAnOpaqueNextCursor() {
    WorkProjectCursor next =
        new WorkProjectCursor(project.audit().updatedAt(), project.id());
    String after = new WorkProjectCursorCodec().encode(next);
    when(service.list(any(), any(), any(), eq(Optional.of(next)), eq(2)))
        .thenReturn(new WorkProjectPage(List.of(project), Optional.of(next)));

    client
        .get()
        .uri(
            "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects"
                + "?after={after}&limit=2",
            organizationId,
            initialization.team().id(),
            after)
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("Cache-Control", "no-store")
        .expectBody()
        .jsonPath("$.items[0].key")
        .isEqualTo("CRW")
        .jsonPath("$.items[0].workspaceId")
        .isEqualTo(initialization.defaultWorkspace().id().toString())
        .jsonPath("$.nextCursor")
        .isNotEmpty();
  }

  @Test
  void returnsProjectDetailWithVersionAndAuditMetadata() {
    when(service.get(any(), any(), any(), any())).thenReturn(project);

    client
        .get()
        .uri(
            "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}",
            organizationId,
            initialization.team().id(),
            project.id())
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("ETag", "\"0\"")
        .expectBody()
        .jsonPath("$.id")
        .isEqualTo(project.id().toString())
        .jsonPath("$.createdByPrincipalId")
        .isEqualTo(actor.id().toString());
  }

  @Test
  void checksProjectKeyAvailabilityOnTheDedicatedRoute() {
    when(service.keyAvailability(any(), any(), any(), any()))
        .thenReturn(new WorkProjectKeyAvailability(new WorkProjectKey("NEW"), true));

    client
        .get()
        .uri(
            "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/keys/NEW",
            organizationId,
            initialization.team().id())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.key")
        .isEqualTo("NEW")
        .jsonPath("$.available")
        .isEqualTo(true);
  }

  @Test
  void rejectsMalformedCursorLimitKeyAndIdentifiersBeforeCallingTheService() {
    String root =
        "/api/v1/organizations/"
            + organizationId
            + "/teams/"
            + initialization.team().id()
            + "/work-projects";

    client
        .get()
        .uri(root + "?after=not-a-cursor")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("invalid_cursor");

    client
        .get()
        .uri(root + "?limit=101")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("invalid_request");

    client
        .post()
        .uri(root)
        .header(ApiHeaders.IDEMPOTENCY_KEY, "invalid-project-key")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"key\":\"bad\",\"name\":\"Invalid\"}")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("invalid_request");

    client
        .get()
        .uri(
            "/api/v1/organizations/{organizationId}/teams/not-a-uuid/work-projects",
            organizationId)
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.details.field")
        .isEqualTo("teamId");
  }
}
