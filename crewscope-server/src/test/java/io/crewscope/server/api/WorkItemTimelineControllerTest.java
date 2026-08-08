package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.workitem.WorkItemTimelineCursor;
import io.crewscope.application.workitem.WorkItemTimelineEvent;
import io.crewscope.application.workitem.WorkItemTimelinePage;
import io.crewscope.application.workitem.WorkItemTimelineService;
import io.crewscope.application.workitem.WorkItemTimelineSource;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/** Proves the WorkItem timeline HTTP, JSON and opaque-cursor contract. */
class WorkItemTimelineControllerTest {

  private final OrganizationId organizationId = OrganizationId.generate();
  private final TeamId teamId = TeamId.generate();
  private final WorkProjectId projectId = WorkProjectId.generate();
  private final WorkItemId workItemId = WorkItemId.generate();
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

  private WorkItemTimelineService timelineService;
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    timelineService = mock(WorkItemTimelineService.class);
    TeamRequestIdentityResolver resolver =
        (authentication, organization, correlationId) ->
            Mono.just(new TeamAccessContext(actor, false));
    client =
        WebTestClient.bindToController(
                new WorkItemTimelineController(timelineService, resolver, new ObjectMapper()))
            .controllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @Test
  void returnsStructuredPayloadAndAnOpaqueResumeCursor() {
    WorkItemTimelineEvent event = event();
    WorkItemTimelineCursor cursor = event.cursor();
    String encoded = new WorkItemTimelineCursorCodec().encode(cursor);
    when(timelineService.list(
            any(),
            eq(organizationId),
            eq(teamId),
            eq(projectId),
            eq(workItemId),
            eq(Optional.of(cursor)),
            eq(20)))
        .thenReturn(new WorkItemTimelinePage(List.of(event), Optional.of(cursor)));

    client
        .get()
        .uri(root() + "?after=" + encoded + "&limit=20")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("Cache-Control", "no-store")
        .expectBody()
        .jsonPath("$.items[0].eventType")
        .isEqualTo("WORK_ITEM_STATUS_CHANGED")
        .jsonPath("$.items[0].actorDisplayName")
        .isEqualTo("Owner")
        .jsonPath("$.items[0].payload.previousStatus")
        .isEqualTo("BACKLOG")
        .jsonPath("$.items[0].payload.status")
        .isEqualTo("READY")
        .jsonPath("$.nextCursor")
        .isEqualTo(encoded);
  }

  @Test
  void rejectsMalformedForeignCursorsLimitsAndIdentifiers() {
    WorkItemTimelineCursor cursor = event().cursor();
    String foreign =
        new WorkItemCursorCodec()
            .encode(
                new io.crewscope.application.workitem.WorkItemCursor(
                    cursor.occurredAt(), workItemId));

    client
        .get()
        .uri(root() + "?after=" + foreign)
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("invalid_cursor");

    client
        .get()
        .uri(root() + "?limit=101")
        .exchange()
        .expectStatus()
        .isBadRequest();

    client
        .get()
        .uri(root().replace(workItemId.toString(), "not-a-uuid"))
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.details.field")
        .isEqualTo("workItemId");
  }

  private WorkItemTimelineEvent event() {
    UUID eventId = UUID.randomUUID();
    return new WorkItemTimelineEvent(
        eventId,
        Optional.of(eventId),
        eventId,
        WorkItemTimelineSource.DOMAIN_EVENT,
        "WORK_ITEM_STATUS_CHANGED",
        "1",
        "WORK_ITEM",
        workItemId.value(),
        Optional.of(1L),
        EventActorType.USER,
        Optional.of(actor.id()),
        Optional.of(actor.displayName()),
        UUID.randomUUID(),
        Optional.empty(),
        now,
        "SUCCEEDED",
        "{\"previousStatus\":\"BACKLOG\",\"status\":\"READY\"}");
  }

  private String root() {
    return "/api/v1/organizations/"
        + organizationId
        + "/teams/"
        + teamId
        + "/work-projects/"
        + projectId
        + "/work-items/"
        + workItemId
        + "/timeline";
  }
}
