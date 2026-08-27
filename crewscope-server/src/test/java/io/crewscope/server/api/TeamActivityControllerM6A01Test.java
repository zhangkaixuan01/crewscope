package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.activity.ActivityApplicationService;
import io.crewscope.application.activity.ActivityCursorScope;
import io.crewscope.application.activity.ActivityFilter;
import io.crewscope.application.activity.AuthorizedActivitySnapshot;
import io.crewscope.application.activity.TeamActivityCursor;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.activity.ActivityActor;
import io.crewscope.domain.activity.ActivityCategory;
import io.crewscope.domain.activity.ActivityEvent;
import io.crewscope.domain.activity.ActivityPayloadSchema;
import io.crewscope.domain.activity.ActivityReference;
import io.crewscope.domain.activity.ActivityReferenceType;
import io.crewscope.domain.activity.ActivitySubject;
import io.crewscope.domain.activity.ActivitySubjectType;
import io.crewscope.domain.activity.ActivityVisibility;
import io.crewscope.domain.activity.TeamSequence;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemId;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** HTTP DTO, dual-cursor and continuously authorized SSE proof for M6-A01. */
class TeamActivityControllerM6A01Test {

  private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");
  private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
  private static final TeamId TEAM_ID = TeamId.generate();
  private static final WorkItemId WORK_ITEM_ID = WorkItemId.generate();
  private static final ProjectionName PROJECTION = new ProjectionName("team-activity");

  private ActivityApplicationService service;
  private TeamActivityRealtimeStream stream;
  private TeamAccessContext access;
  private WebTestClient client;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    service = mock(ActivityApplicationService.class);
    stream = mock(TeamActivityRealtimeStream.class);
    access = mock(TeamAccessContext.class);
    TeamRequestIdentityResolver resolver = mock(TeamRequestIdentityResolver.class);
    when(resolver.resolve(any(), eq(ORGANIZATION_ID), any())).thenReturn(Mono.just(access));
    TeamActivityCursorCodec codec = new TeamActivityCursorCodec(
        new TeamActivityCursorKeyRing(
            "k1", Map.of("k1", Base64.getEncoder().encodeToString(new byte[32]))),
        Clock.fixed(NOW, ZoneOffset.UTC),
        Duration.ofHours(1),
        Duration.ofSeconds(30));
    ObjectProvider<TeamActivityCursorCodec> codecs = mock(ObjectProvider.class);
    ObjectProvider<TeamActivityRealtimeStream> streams = mock(ObjectProvider.class);
    when(codecs.getIfAvailable()).thenReturn(codec);
    when(streams.getIfAvailable()).thenReturn(stream);
    TeamActivityController controller =
        new TeamActivityController(service, resolver, codecs, streams);
    client = WebTestClient.bindToController(controller)
        .controllerAdvice(new ApiExceptionHandler())
        .build();
  }

  @Test
  void returnsOnlyTheReviewedPublicFieldsAndBothSnapshotCursors() {
    ActivityEvent first = event(1, ActivityVisibility.TEAM_MEMBERS);
    ActivityEvent highWater = event(2, ActivityVisibility.TEAM_MEMBERS);
    ActivityCursorScope scope = scope(ActivityFilter.ALL);
    when(service.teamSnapshot(any(), any())).thenReturn(new AuthorizedActivitySnapshot(
        List.of(first),
        true,
        Optional.of(TeamActivityCursor.from(scope, first)),
        Optional.of(TeamActivityCursor.from(scope, highWater))));

    client.get()
        .uri(route("/snapshot?limit=1"))
        .exchange()
        .expectStatus().isOk()
        .expectHeader().cacheControl(CacheControl.noStore())
        .expectBody()
        .jsonPath("$.items[0].eventId").isEqualTo(first.id().value().toString())
        .jsonPath("$.items[0].payload.schemaName")
        .isEqualTo("activity.work-item-created")
        .jsonPath("$.items[0].projectionName").doesNotExist()
        .jsonPath("$.items[0].projectionGeneration").doesNotExist()
        .jsonPath("$.nextCursor").isNotEmpty()
        .jsonPath("$.snapshotCursor").isNotEmpty();
  }

  @Test
  void rejectsConflictingResumeCoordinatesBeforeOpeningTheStream() {
    client.get()
        .uri(route("/events?after=second"))
        .header(ApiHeaders.LAST_EVENT_ID, "first")
        .accept(MediaType.TEXT_EVENT_STREAM)
        .exchange()
        .expectStatus().isBadRequest();
  }

  @Test
  void omitsHiddenBusinessFramesAndPreservesVisibleSseIdentity() {
    ActivityEvent hidden = event(1, ActivityVisibility.TEAM_ADMINS);
    ActivityEvent visible = event(2, ActivityVisibility.TEAM_MEMBERS);
    when(service.canViewNow(access, ORGANIZATION_ID, TEAM_ID, hidden)).thenReturn(false);
    when(service.canViewNow(access, ORGANIZATION_ID, TEAM_ID, visible)).thenReturn(true);
    Flux<ServerSentEvent<TeamActivityStreamEvent>> frames = Flux.just(
        ServerSentEvent.<TeamActivityStreamEvent>builder(TeamActivityStreamEvent.from(hidden))
            .id("hidden-position")
            .event(hidden.eventType().value())
            .build(),
        ServerSentEvent.<TeamActivityStreamEvent>builder(TeamActivityStreamEvent.from(visible))
            .id("visible-position")
            .event(visible.eventType().value())
            .build(),
        ServerSentEvent.<TeamActivityStreamEvent>builder()
            .event("heartbeat")
            .comment("heartbeat")
            .build());
    when(stream.open(any(), eq(Optional.empty())))
        .thenReturn(Mono.just(new TeamActivitySseSession(scope(ActivityFilter.ALL), frames)));

    String body = client.get()
        .uri(route("/events"))
        .accept(MediaType.TEXT_EVENT_STREAM)
        .exchange()
        .expectStatus().isOk()
        .expectHeader().cacheControl(CacheControl.noStore())
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();

    assertFalse(body.contains("hidden-position"));
    assertTrue(body.contains("visible-position"));
    assertTrue(body.contains(visible.id().value().toString()));
    assertTrue(body.contains(":heartbeat"));
  }

  private static ActivityCursorScope scope(ActivityFilter filter) {
    return ActivityCursorScope.of(
        ORGANIZATION_ID,
        TEAM_ID,
        PROJECTION,
        ProjectionGeneration.FIRST,
        SchemaVersion.V1,
        filter);
  }

  private static String route(String suffix) {
    return "/api/v1/organizations/"
        + ORGANIZATION_ID
        + "/teams/"
        + TEAM_ID
        + "/activity"
        + suffix;
  }

  private static ActivityEvent event(long sequence, ActivityVisibility visibility) {
    UUID domainEventId = UUID.nameUUIDFromBytes(
        ("m6-a01-api-" + sequence).getBytes(StandardCharsets.UTF_8));
    ActivityPayloadSchema schema = new ActivityPayloadSchema(
        "activity.work-item-created",
        SchemaVersion.V1,
        Set.of("itemKey"),
        Set.of("title"));
    return ActivityEvent.project(
        domainEventId,
        ORGANIZATION_ID,
        TEAM_ID,
        PROJECTION,
        ProjectionGeneration.FIRST,
        SchemaVersion.V1,
        new TeamSequence(sequence),
        new EventType("WORK_ITEM_CREATED"),
        ActivityCategory.WORK_ITEM,
        visibility,
        new ActivitySubject(ActivitySubjectType.WORK_ITEM, WORK_ITEM_ID.value()),
        new ActivityActor(EventActorType.USER, Optional.of(PrincipalId.generate())),
        List.of(
            new ActivityReference(ActivityReferenceType.TEAM, TEAM_ID.value()),
            new ActivityReference(ActivityReferenceType.WORK_ITEM, WORK_ITEM_ID.value())),
        UtcTimestamp.from(NOW),
        schema.createPayload(Map.of("itemKey", "CS-" + sequence, "title", "Activity")));
  }
}
