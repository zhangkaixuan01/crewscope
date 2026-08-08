package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.responsibility.GateReviewerAssignment;
import io.crewscope.application.responsibility.OwnerAssignmentChange;
import io.crewscope.application.responsibility.ResponsibilityAssignmentView;
import io.crewscope.application.responsibility.ResponsibilityCommandService;
import io.crewscope.application.responsibility.ResponsibilityQueryService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.responsibility.ReviewerEligibilityDecision;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Proves the six responsibility management routes and optimistic request contracts. */
class ResponsibilityControllerTest {

  private final OrganizationId organizationId = OrganizationId.generate();
  private final UtcTimestamp now = UtcTimestamp.parse("2026-08-08T11:00:00Z");
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
  private final TeamInitialization initialization = TeamInitialization.create(actor, "Platform", now);
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
          WorkItemType.TASK,
          "Responsibility API",
          Optional.empty(),
          WorkItemPriority.HIGH,
          Set.of(),
          Optional.empty(),
          actor,
          now);
  private final ResponsibilityAssignment assignment =
      ResponsibilityAssignment.assign(
          ResponsibilityAssignmentId.generate(),
          item,
          ResponsibilityRole.OWNER,
          actor,
          Optional.of(initialization.ownerMember()),
          actor,
          now);

  private ResponsibilityQueryService queryService;
  private ResponsibilityCommandService commandService;
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    queryService = mock(ResponsibilityQueryService.class);
    commandService = mock(ResponsibilityCommandService.class);
    TeamRequestIdentityResolver resolver =
        (authentication, organization, correlationId) ->
            Mono.just(new TeamAccessContext(actor, false));
    client =
        WebTestClient.bindToController(
                new ResponsibilityController(queryService, commandService, resolver))
            .controllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @Test
  void listsTheActiveResponsibilityChainWithResolvedPrincipalData() {
    when(queryService.listActive(any(), any(), any(), any(), any()))
        .thenReturn(List.of(new ResponsibilityAssignmentView(assignment, "Owner")));

    client
        .get()
        .uri(root())
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("Cache-Control", "no-store")
        .expectBody()
        .jsonPath("$[0].role")
        .isEqualTo("OWNER")
        .jsonPath("$[0].actorDisplayName")
        .isEqualTo("Owner")
        .jsonPath("$[0].version")
        .isEqualTo(0);
  }

  @Test
  void assignsAndReplacesTheOwnerUsingTheAcceptedReceiptContract() {
    CommandReceipt receipt = receipt(0);
    when(commandService.replaceOwner(any(), any(), any(), any(), any()))
        .thenReturn(
            CommandExecution.completed(
                new OwnerAssignmentChange(Optional.empty(), assignment), receipt));

    client
        .post()
        .uri(root() + "/owner")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "owner-http-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"actorPrincipalId\":\"" + actor.id() + "\"}")
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.domainEventId")
        .isEqualTo(receipt.domainEventId().toString());

    client
        .post()
        .uri(root() + "/owner")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "owner-http-2")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            "{\"actorPrincipalId\":\""
                + actor.id()
                + "\",\"expectedAssignmentId\":\""
                + assignment.id()
                + "\",\"expectedVersion\":0}")
        .exchange()
        .expectStatus()
        .isAccepted();
  }

  @Test
  void exposesExecutorReviewerAdvisoryAndReleaseCommands() {
    CommandReceipt assignedReceipt = receipt(0);
    CommandReceipt releasedReceipt = receipt(1);
    when(commandService.assignExecutor(any(), any(), any(), any(), any()))
        .thenReturn(CommandExecution.completed(assignment, assignedReceipt));
    when(commandService.assignGateReviewer(any(), any(), any(), any(), any()))
        .thenReturn(
            CommandExecution.completed(
                new GateReviewerAssignment(
                    reviewerAssignment(PrincipalType.USER), ReviewerEligibilityDecision.strict()),
                assignedReceipt));
    when(commandService.assignAdvisoryReviewer(any(), any(), any(), any(), any()))
        .thenReturn(CommandExecution.completed(reviewerAssignment(PrincipalType.SPECIALIST_AGENT), assignedReceipt));
    when(commandService.release(any(), any(), any(), any(), any(), any()))
        .thenReturn(CommandExecution.completed(assignment, releasedReceipt));

    for (String child : new String[] {"executors", "gate-reviewers", "advisory-reviewers"}) {
      client
          .post()
          .uri(root() + "/" + child)
          .header(ApiHeaders.IDEMPOTENCY_KEY, child + "-http")
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue("{\"actorPrincipalId\":\"" + actor.id() + "\"}")
          .exchange()
          .expectStatus()
          .isAccepted();
    }

    client
        .post()
        .uri(root() + "/" + assignment.id() + "/releases")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "release-http")
        .header(ApiHeaders.IF_MATCH, "\"0\"")
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.committedVersion")
        .isEqualTo(1);
  }

  @Test
  void rejectsIncompleteOwnerExpectationsInvalidVersionsAndIdentifiers() {
    client
        .post()
        .uri(root() + "/owner")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "invalid-owner-expectation")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            "{\"actorPrincipalId\":\""
                + actor.id()
                + "\",\"expectedAssignmentId\":\""
                + assignment.id()
                + "\"}")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("invalid_request");

    client
        .post()
        .uri(root() + "/" + assignment.id() + "/releases")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "missing-release-version")
        .exchange()
        .expectStatus()
        .isEqualTo(428);

    client
        .post()
        .uri(root() + "/not-a-uuid/releases")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "invalid-assignment-id")
        .header(ApiHeaders.IF_MATCH, "\"0\"")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.details.field")
        .isEqualTo("assignmentId");
  }

  private ResponsibilityAssignment reviewerAssignment(PrincipalType type) {
    if (type == PrincipalType.USER) {
      return ResponsibilityAssignment.assign(
          ResponsibilityAssignmentId.generate(),
          item,
          ResponsibilityRole.REVIEWER,
          actor,
          Optional.of(initialization.ownerMember()),
          actor,
          now);
    }
    Principal specialist =
        Principal.create(
            PrincipalId.generate(),
            PrincipalScope.team(organizationId, initialization.team().id()),
            PrincipalType.SPECIALIST_AGENT,
            Optional.of(actor.id()),
            "Specialist",
            Optional.empty(),
            PrincipalVisibility.TEAM,
            now);
    return ResponsibilityAssignment.assign(
        ResponsibilityAssignmentId.generate(),
        item,
        ResponsibilityRole.REVIEWER,
        specialist,
        Optional.empty(),
        actor,
        now);
  }

  private CommandReceipt receipt(long version) {
    return new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), version, UUID.randomUUID());
  }

  private String root() {
    return "/api/v1/organizations/"
        + organizationId
        + "/teams/"
        + initialization.team().id()
        + "/work-projects/"
        + project.id()
        + "/work-items/"
        + item.id()
        + "/responsibilities";
  }
}
