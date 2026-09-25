package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.workitem.WorkItemAvailableTransition;
import io.crewscope.application.workitem.WorkItemBlockedReason;
import io.crewscope.application.workitem.WorkItemCollaborationService;
import io.crewscope.application.workitem.WorkItemCursor;
import io.crewscope.application.workitem.WorkItemCursorScope;
import io.crewscope.application.workitem.WorkItemDetails;
import io.crewscope.application.workitem.WorkItemExecutionSummary;
import io.crewscope.application.workitem.WorkItemFilter;
import io.crewscope.application.workitem.WorkItemListPage;
import io.crewscope.application.workitem.WorkItemListRow;
import io.crewscope.application.workitem.WorkItemQueryService;
import io.crewscope.application.workitem.WorkItemSort;
import io.crewscope.application.workitem.WorkItemTransitionAvailabilityProjector;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.task.TaskExecutionWaitReason;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemComment;
import io.crewscope.domain.workitem.WorkItemCommentId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemPriority;
import io.crewscope.domain.workitem.WorkItemResourceLink;
import io.crewscope.domain.workitem.WorkItemResourceLinkId;
import io.crewscope.domain.workitem.WorkItemResourceType;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkItemType;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.WorkProjectKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Proves WorkItem query, comment and resource-link HTTP contracts. */
class WorkItemQueryControllerTest {

  private final OrganizationId organizationId = OrganizationId.generate();
  private final UtcTimestamp now = UtcTimestamp.parse("2026-08-08T10:00:00Z");
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
          WorkItemType.FEATURE,
          "Collaboration API",
          Optional.of("Complete details"),
          WorkItemPriority.HIGH,
          Set.of(),
          Optional.empty(),
          actor,
          now);
  private final WorkItemComment comment =
      WorkItemComment.addNative(
          WorkItemCommentId.generate(), item, actor, "Review complete", now);
  private final WorkItemResourceLink link =
      WorkItemResourceLink.link(
          WorkItemResourceLinkId.generate(),
          item,
          WorkItemResourceType.EXTERNAL_URL,
          "https://example.com/spec",
          Optional.of("Spec"),
          actor,
          now);

  private WorkItemQueryService queryService;
  private WorkItemCollaborationService collaborationService;
  private WorkItemCursorCodec cursorCodec;
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    queryService = mock(WorkItemQueryService.class);
    collaborationService = mock(WorkItemCollaborationService.class);
    cursorCodec =
        new WorkItemCursorCodec(
            new TeamActivityCursorKeyRing("k1", Map.of("k1", key())),
            Clock.fixed(Instant.parse("2026-08-08T10:00:30Z"), ZoneOffset.UTC),
            Duration.ofMinutes(30));
    TeamRequestIdentityResolver resolver =
        (authentication, organization, correlationId) ->
            Mono.just(new TeamAccessContext(actor, false));
    client =
        WebTestClient.bindToController(
                new WorkItemQueryController(queryService, collaborationService, resolver, cursorCodec))
            .controllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @Test
  void listsOneProjectWithStatusSortAndAnOpaqueCursor() {
    WorkItemFilter filter = WorkItemFilter.ofStatus(WorkItemStatus.BACKLOG);
    WorkItemCursor cursor =
        new WorkItemCursor(
            scope(WorkItemSort.UPDATED_AT, filter),
            Optional.of(item.audit().updatedAt()),
            OptionalInt.empty(),
            item.id());
    String encoded = cursorCodec.encode(cursor);
    when(queryService.list(
            any(),
            eq(organizationId),
            eq(initialization.team().id()),
            eq(project.id()),
            eq(filter),
            eq(WorkItemSort.UPDATED_AT),
            eq(Optional.of(cursor)),
            eq(20)))
        .thenReturn(
            new WorkItemListPage(
                List.of(
                    new WorkItemListRow(
                        item, actions(WorkItemStatus.BACKLOG, false), Optional.empty())),
                Optional.of(cursor)));

    client
        .get()
        .uri(root() + "?status=BACKLOG&sort=updatedAt&after=" + encoded + "&limit=20")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("Cache-Control", "no-store")
        .expectBody()
        .jsonPath("$.items[0].key")
        .isEqualTo("CRW-1")
        .jsonPath("$.items[0].availableActions[0].actionId")
        .isEqualTo("submit-ready")
        .jsonPath("$.items[0].availableActions[0].enabled")
        .isEqualTo(false)
        .jsonPath("$.items[0].availableActions[0].reason")
        .isEqualTo("EXTERNAL_PROVIDER_MANAGED")
        .jsonPath("$.items[0].availableActions[0].reasonMessage")
        .isNotEmpty()
        .jsonPath("$.items[0].summary")
        .value(org.hamcrest.Matchers.nullValue())
        .jsonPath("$.nextCursor")
        .isEqualTo(encoded);
  }

  /** The M9b-A06 summary rides along in the same response, list and detail alike. */
  @Test
  void embedsTheExecutionSummaryInEveryListRow() {
    TaskId taskId = TaskId.generate();
    PrincipalId reviewer = PrincipalId.generate();
    WorkItemExecutionSummary summary =
        new WorkItemExecutionSummary(
            item.id(),
            item.version(),
            WorkItemStatus.IN_REVIEW,
            1,
            1,
            2,
            Optional.of(taskId),
            Optional.of(TaskExecutionId.generate()),
            Optional.of(TaskExecutionStatus.WAITING),
            false,
            List.of(
                WorkItemBlockedReason.waiting(
                    TaskExecutionWaitReason.REVIEW,
                    taskId,
                    TaskExecutionId.generate(),
                    Optional.of(now),
                    Optional.of(reviewer)),
                WorkItemBlockedReason.reviewPending()),
            Optional.empty(),
            Optional.empty(),
            item.version(),
            now);
    when(queryService.list(
            any(),
            eq(organizationId),
            eq(initialization.team().id()),
            eq(project.id()),
            eq(WorkItemFilter.ALL),
            eq(WorkItemSort.UPDATED_AT),
            eq(Optional.empty()),
            eq(20)))
        .thenReturn(
            new WorkItemListPage(
                List.of(
                    new WorkItemListRow(
                        item, actions(WorkItemStatus.IN_REVIEW, false), Optional.of(summary))),
                Optional.empty()));

    client
        .get()
        .uri(root())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items[0].summary.workItemId")
        .isEqualTo(item.id().toString())
        .jsonPath("$.items[0].summary.taskCount")
        .isEqualTo(1)
        .jsonPath("$.items[0].summary.activeTaskCount")
        .isEqualTo(1)
        .jsonPath("$.items[0].summary.pendingReviewCount")
        .isEqualTo(2)
        .jsonPath("$.items[0].summary.currentTaskId")
        .isEqualTo(taskId.toString())
        .jsonPath("$.items[0].summary.executionStatus")
        .isEqualTo("WAITING")
        .jsonPath("$.items[0].summary.selectionRequired")
        .isEqualTo(false)
        .jsonPath("$.items[0].summary.blockedReasons[0].code")
        .isEqualTo("REVIEW")
        .jsonPath("$.items[0].summary.blockedReasons[0].waitingOnPrincipalId")
        .isEqualTo(reviewer.toString())
        .jsonPath("$.items[0].summary.blockedReasons[1].code")
        .isEqualTo("REVIEW_PENDING")
        .jsonPath("$.items[0].summary.resultSummary")
        .value(org.hamcrest.Matchers.nullValue())
        .jsonPath("$.items[0].summary.observedAt")
        .isEqualTo(now.toString())
        .jsonPath("$.nextCursor")
        .value(org.hamcrest.Matchers.nullValue());
  }

  @Test
  void parsesMultiValueFiltersResponsibilityRoleAndSort() {
    WorkItemFilter filter =
        new WorkItemFilter(
            Set.of(),
            Set.of(WorkItemType.FEATURE, WorkItemType.BUG),
            Set.of(WorkItemPriority.HIGH, WorkItemPriority.URGENT),
            Optional.of(ResponsibilityRole.REVIEWER));
    when(queryService.list(
            any(),
            eq(organizationId),
            eq(initialization.team().id()),
            eq(project.id()),
            eq(filter),
            eq(WorkItemSort.PRIORITY),
            eq(Optional.empty()),
            eq(20)))
        .thenReturn(new WorkItemListPage(List.of(), Optional.empty()));

    client
        .get()
        .uri(root() + "?type=FEATURE,BUG&priority=HIGH&priority=URGENT&responsibilityRole=REVIEWER&sort=priority")
        .exchange()
        .expectStatus()
        .isOk();

    verify(queryService)
        .list(
            any(),
            eq(organizationId),
            eq(initialization.team().id()),
            eq(project.id()),
            eq(filter),
            eq(WorkItemSort.PRIORITY),
            eq(Optional.empty()),
            eq(20));
  }

  @Test
  void returnsCompleteDetailsAndDedicatedChildCollections() {
    when(queryService.get(any(), any(), any(), any(), any()))
        .thenReturn(
            new WorkItemDetails(
                item,
                List.of(comment),
                List.of(link),
                actions(WorkItemStatus.BACKLOG, true),
                Optional.of(
                    WorkItemExecutionSummary.none(item.id(), item.version(), WorkItemStatus.BACKLOG, now))));

    client
        .get()
        .uri(root() + "/" + item.id())
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("ETag", "\"0\"")
        .expectBody()
        .jsonPath("$.workItem.id")
        .isEqualTo(item.id().toString())
        .jsonPath("$.comments[0].content")
        .isEqualTo("Review complete")
        .jsonPath("$.resourceLinks[0].resourceReference")
        .isEqualTo("https://example.com/spec")
        .jsonPath("$.workItem.availableActions[0].targetStatus")
        .isEqualTo("READY")
        .jsonPath("$.workItem.availableActions[0].label")
        .isEqualTo("准备工作项")
        .jsonPath("$.workItem.summary.taskCount")
        .isEqualTo(0)
        .jsonPath("$.workItem.summary.selectionRequired")
        .isEqualTo(false);

    client
        .get()
        .uri(root() + "/" + item.id() + "/comments")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$[0].id")
        .isEqualTo(comment.id().toString());

    client
        .get()
        .uri(root() + "/" + item.id() + "/resource-links")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$[0].label")
        .isEqualTo("Spec");
  }

  @Test
  void appendsCommentsAndResourceLinksUsingTheAcceptedReceiptContract() {
    CommandReceipt commentReceipt =
        new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID());
    CommandReceipt linkReceipt =
        new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID());
    when(collaborationService.addComment(any(), any(), any(), any(), any()))
        .thenReturn(CommandExecution.completed(comment, commentReceipt));
    when(collaborationService.linkResource(any(), any(), any(), any(), any()))
        .thenReturn(CommandExecution.completed(link, linkReceipt));

    client
        .post()
        .uri(root() + "/" + item.id() + "/comments")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "comment-http-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"content\":\"Review complete\"}")
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.domainEventId")
        .isEqualTo(commentReceipt.domainEventId().toString());

    client
        .post()
        .uri(root() + "/" + item.id() + "/resource-links")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "link-http-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            "{\"resourceType\":\"EXTERNAL_URL\","
                + "\"resourceReference\":\"https://example.com/spec\",\"label\":\"Spec\"}")
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.domainEventId")
        .isEqualTo(linkReceipt.domainEventId().toString());
  }

  @Test
  void rejectsMalformedCursorEnumsBodiesAndIdentifiers() {
    client
        .get()
        .uri(root() + "?after=not-a-cursor")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("invalid_cursor");

    client
        .get()
        .uri(root() + "?status=UNKNOWN")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("invalid_request");

    client
        .get()
        .uri(root() + "?sort=BOGUS")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("invalid_request")
        .jsonPath("$.details.parameter")
        .isEqualTo("sort");

    client
        .get()
        .uri(root() + "?type=FEATURE,BOGUS")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("invalid_request")
        .jsonPath("$.details.parameter")
        .isEqualTo("type");

    client
        .get()
        .uri(root() + "?responsibilityRole=BOGUS")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("invalid_request")
        .jsonPath("$.details.parameter")
        .isEqualTo("responsibilityRole");

    client
        .post()
        .uri(root() + "/" + item.id() + "/comments")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "blank-comment")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"content\":\" \"}")
        .exchange()
        .expectStatus()
        .isBadRequest();

    client
        .get()
        .uri(root() + "/not-a-uuid")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.details.field")
        .isEqualTo("workItemId");
  }

  private WorkItemCursorScope scope(WorkItemSort sort, WorkItemFilter filter) {
    return WorkItemCursorScope.of(
        organizationId, initialization.team().id(), project.id(), actor.id(), sort, filter);
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

  private static String key() {
    byte[] value = new byte[32];
    for (int index = 0; index < value.length; index++) {
      value[index] = (byte) (11 + index);
    }
    return Base64.getEncoder().encodeToString(value);
  }

  /**
   * The real adjudication, not a hand-built stub: the HTTP test then proves the response carries the
   * same object shape the availability endpoint serializes, verdicts included.
   */
  private static List<WorkItemAvailableTransition> actions(
      WorkItemStatus status, boolean nativeSource) {
    return new WorkItemTransitionAvailabilityProjector().all(status, nativeSource, true);
  }
}
