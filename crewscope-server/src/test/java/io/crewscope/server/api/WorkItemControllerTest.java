package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.workitem.WorkItemCommandService;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemPriority;
import io.crewscope.domain.workitem.WorkItemType;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.WorkProjectKey;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Proves native WorkItem commands, If-Match handling and safe HTTP validation. */
class WorkItemControllerTest {

  @Test
  void createsWithOnlyTitleAndServerDefaults() {
    when(service.create(any(), any(), any(), any())).thenAnswer(invocation -> {
      var command = invocation.getArgument(3, io.crewscope.application.workitem.CreateNativeWorkItemCommand.class);
      org.junit.jupiter.api.Assertions.assertNull(command.key());
      org.junit.jupiter.api.Assertions.assertEquals(WorkItemType.TASK, command.type());
      org.junit.jupiter.api.Assertions.assertEquals(WorkItemPriority.MEDIUM, command.priority());
      return CommandExecution.completed(item, new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID()));
    });
    client.post().uri(root()).header("Idempotency-Key", "minimal-create")
        .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"title\":\"New work\"}")
        .exchange().expectStatus().isAccepted();
  }

  @Test
  void patchesPresenceWithoutAcceptingKeyTypeOrStateChanges() {
    when(service.updateContent(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
      var update = invocation.getArgument(4, io.crewscope.application.workitem.UpdateWorkItemContentCommand.class);
      org.junit.jupiter.api.Assertions.assertFalse(update.title().present());
      org.junit.jupiter.api.Assertions.assertTrue(update.description().present());
      org.junit.jupiter.api.Assertions.assertNull(update.description().value());
      org.junit.jupiter.api.Assertions.assertEquals(0, update.expectedVersion());
      return CommandExecution.completed(item, new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID()));
    });
    client.patch().uri(root() + "/" + item.id()).header("Idempotency-Key", "edit-content")
        .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"description\":null}").exchange().expectStatus().isAccepted();
    for (String body : java.util.List.of("{\"title\":null}", "{\"type\":\"BUG\"}", "{\"key\":\"CRW-99\"}",
        "{\"status\":\"DONE\"}", "{\"priority\":null}", "{}")) {
      client.patch().uri(root() + "/" + item.id()).header("Idempotency-Key", "invalid-edit")
          .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
          .bodyValue(body).exchange().expectStatus().isEqualTo(
              body.contains("\"title\"") || body.contains("\"priority\"") || body.equals("{}") ? 422 : 400);
    }
    client.patch().uri(root() + "/" + item.id()).header("Idempotency-Key", "edit-no-version")
        .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"title\":\"Updated\"}")
        .exchange().expectStatus().isEqualTo(428);
  }


  private final OrganizationId organizationId = OrganizationId.generate();
  private final UtcTimestamp now = UtcTimestamp.parse("2026-08-08T08:00:00Z");
  private final Principal actor =
      Principal.create(
          PrincipalId.generate(),
          PrincipalScope.organization(organizationId),
          PrincipalType.USER,
          Optional.empty(),
          "Owner",
          Optional.empty(),
          PrincipalVisibility.ORGANIZATION,
          now);
  private final TeamInitialization initialization =
      TeamInitialization.create(actor, "Platform", now);
  private final WorkProject project =
      WorkProject.create(
          WorkProjectId.generate(),
          new WorkProjectKey("CRW"),
          "CrewScope",
          initialization.team(),
          initialization.defaultWorkspace(),
          actor,
          now);
  private final WorkItem item =
      WorkItem.createNative(
          WorkItemId.generate(),
          project,
          new WorkItemKey("CRW-1"),
          WorkItemType.FEATURE,
          "Build API",
          Optional.of("Plan"),
          WorkItemPriority.HIGH,
          Set.of(),
          Optional.empty(),
          actor,
          now);

  private WorkItemCommandService service;
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    service = mock(WorkItemCommandService.class);
    TeamRequestIdentityResolver resolver =
        (authentication, organization, correlationId) ->
            Mono.just(new TeamAccessContext(actor, false));
    client =
        WebTestClient.bindToController(new WorkItemController(service, resolver))
            .controllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @Test
  void createsACompleteNativeWorkItemUsingTheAcceptedReceiptContract() {
    CommandReceipt receipt =
        new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID());
    when(service.create(any(), any(), any(), any()))
        .thenReturn(CommandExecution.completed(item, receipt));

    client
        .post()
        .uri(root())
        .header(ApiHeaders.IDEMPOTENCY_KEY, "create-work-item-http-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            """
            {
              "key":"CRW-1",
              "type":"FEATURE",
              "title":"Build API",
              "description":"Plan",
              "priority":"HIGH",
              "labels":["Backend","API"],
              "dueAt":"2026-08-10T12:00:00Z"
            }
            """)
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
  void transitionsUsingOneStrongIfMatchVersion() {
    WorkItem ready = item.transitionTo(io.crewscope.domain.workitem.WorkItemStatus.READY, actor, now);
    CommandReceipt receipt =
        new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID());
    when(service.transition(any(), any(), any(), any(), any()))
        .thenReturn(CommandExecution.completed(ready, receipt));

    client
        .post()
        .uri(root() + "/" + item.id() + "/transitions")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "transition-work-item-http-1")
        .header(ApiHeaders.IF_MATCH, "\"0\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"targetStatus\":\"READY\"}")
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.committedVersion")
        .isEqualTo(1);
  }

  @Test
  void requiresIfMatchAndRejectsWeakOrWildcardEtags() {
    String uri = root() + "/" + item.id() + "/transitions";
    client
        .post()
        .uri(uri)
        .header(ApiHeaders.IDEMPOTENCY_KEY, "missing-if-match")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"targetStatus\":\"READY\"}")
        .exchange()
        .expectStatus()
        .isEqualTo(428)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("precondition_required");

    for (String invalid : new String[] {"W/\"0\"", "*", "\"0\",\"1\""}) {
      client
          .post()
          .uri(uri)
          .header(ApiHeaders.IDEMPOTENCY_KEY, "invalid-if-match")
          .header(ApiHeaders.IF_MATCH, invalid)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue("{\"targetStatus\":\"READY\"}")
          .exchange()
          .expectStatus()
          .isBadRequest()
          .expectBody()
          .jsonPath("$.code")
          .isEqualTo("invalid_if_match");
    }

    client
        .post()
        .uri(uri)
        .header(ApiHeaders.IDEMPOTENCY_KEY, "multiple-if-match-lines")
        .header(ApiHeaders.IF_MATCH, "\"0\"", "\"1\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"targetStatus\":\"READY\"}")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("invalid_if_match");
  }

  @Test
  void mapsOptimisticConflictWithTheCurrentVersion() {
    when(service.transition(any(), any(), any(), any(), any()))
        .thenThrow(new OptimisticLockConflictException("WorkItem", item.id(), 0, 2));

    client
        .post()
        .uri(root() + "/" + item.id() + "/transitions")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "stale-transition-http")
        .header(ApiHeaders.IF_MATCH, "\"0\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"targetStatus\":\"READY\"}")
        .exchange()
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("optimistic_lock_conflict")
        .jsonPath("$.currentVersion")
        .isEqualTo(2);
  }

  @Test
  void rejectsInvalidCreateFieldsEnumsAndIdentifiers() {
    client
        .post()
        .uri(root())
        .header(ApiHeaders.IDEMPOTENCY_KEY, "invalid-create-http")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            """
            {"key":"bad","type":"FEATURE","title":" ","priority":"HIGH","labels":[]}
            """)
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("invalid_request");

    client
        .post()
        .uri(root())
        .header(ApiHeaders.IDEMPOTENCY_KEY, "invalid-enum-http")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            """
            {"key":"CRW-2","type":"UNKNOWN","title":"Valid","priority":"HIGH","labels":[]}
            """)
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("invalid_request");

    client
        .post()
        .uri(
            "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/"
                + "not-a-uuid/work-items",
            organizationId,
            initialization.team().id())
        .header(ApiHeaders.IDEMPOTENCY_KEY, "invalid-project-id-http")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            """
            {"key":"CRW-2","type":"TASK","title":"Valid","priority":"MEDIUM","labels":[]}
            """)
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.details.field")
        .isEqualTo("projectId");
  }

  private String root() {
    return "/api/v1/organizations/"
        + organizationId
        + "/teams/"
        + initialization.team().id()
        + "/work-projects/"
        + project.id()
        + "/work-items";
  }
}
