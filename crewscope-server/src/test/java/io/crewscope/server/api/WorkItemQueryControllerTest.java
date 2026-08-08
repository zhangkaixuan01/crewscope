package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.workitem.WorkItemCollaborationService;
import io.crewscope.application.workitem.WorkItemCursor;
import io.crewscope.application.workitem.WorkItemDetails;
import io.crewscope.application.workitem.WorkItemPage;
import io.crewscope.application.workitem.WorkItemQueryService;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
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
import java.util.List;
import java.util.Optional;
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
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    queryService = mock(WorkItemQueryService.class);
    collaborationService = mock(WorkItemCollaborationService.class);
    TeamRequestIdentityResolver resolver =
        (authentication, organization, correlationId) ->
            Mono.just(new TeamAccessContext(actor, false));
    client =
        WebTestClient.bindToController(
                new WorkItemQueryController(queryService, collaborationService, resolver))
            .controllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @Test
  void listsOneProjectWithStatusAndAnOpaqueCursor() {
    WorkItemCursor cursor = new WorkItemCursor(item.audit().updatedAt(), item.id());
    String encoded = new WorkItemCursorCodec().encode(cursor);
    when(queryService.list(
            any(),
            eq(organizationId),
            eq(initialization.team().id()),
            eq(project.id()),
            eq(Optional.of(WorkItemStatus.BACKLOG)),
            eq(Optional.of(cursor)),
            eq(20)))
        .thenReturn(new WorkItemPage(List.of(item), Optional.of(cursor)));

    client
        .get()
        .uri(root() + "?status=BACKLOG&after=" + encoded + "&limit=20")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("Cache-Control", "no-store")
        .expectBody()
        .jsonPath("$.items[0].key")
        .isEqualTo("CRW-1")
        .jsonPath("$.nextCursor")
        .isEqualTo(encoded);
  }

  @Test
  void returnsCompleteDetailsAndDedicatedChildCollections() {
    when(queryService.get(any(), any(), any(), any(), any()))
        .thenReturn(new WorkItemDetails(item, List.of(comment), List.of(link)));

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
        .isEqualTo("https://example.com/spec");

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
