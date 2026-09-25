package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.activity.ActivityApplicationService;
import io.crewscope.application.activity.ActivityCursorScope;
import io.crewscope.application.activity.ActivityFilter;
import io.crewscope.application.activity.ActivityPage;
import io.crewscope.application.activity.ActivityQuery;
import io.crewscope.application.activity.TeamActivityCursor;
import io.crewscope.application.activity.TeamActivitySnapshot;
import io.crewscope.application.activity.TeamActivitySnapshotRequest;
import io.crewscope.application.activity.TeamRealtimeEventStore;
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
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.server.config.application.TeamActivityRealtimeProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;

/**
 * M9b-A07 revocation bounds for an otherwise idle Team Activity SSE session: the idle probe
 * revalidates authorization on its own clock, the heartbeat branch stays reachable, and a
 * failing authorization on a probe frame closes the HTTP stream.
 */
class TeamActivityStreamRevocationTest {

  private static final Instant NOW = Instant.parse("2026-09-09T04:00:00Z");
  private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
  private static final TeamId TEAM_ID = TeamId.generate();
  private static final ProjectionName PROJECTION_NAME = new ProjectionName("team-activity");
  private static final ActivityFilter FILTER = ActivityFilter.ALL;
  private static final WorkItemId WORK_ITEM_ID = WorkItemId.generate();
  private static final PrincipalId ACTOR_ID = PrincipalId.generate();

  @Test
  void emitsIdleProbesOnAnIndependentClockWhileTheStreamStaysQuiet() {
    FakeStore store = new FakeStore();
    store.add(1);
    TeamActivityRealtimeProperties properties = new TeamActivityRealtimeProperties();
    properties.setBatchSize(2);
    properties.setPollInterval(Duration.ofMillis(10));
    // The heartbeat window never opens, so every null frame in this test is an idle probe.
    properties.setHeartbeatInterval(Duration.ofSeconds(30));
    properties.setIdleProbeInterval(Duration.ofMillis(150));
    TeamActivityRealtimeStream stream =
        new TeamActivityRealtimeStream(store, codec(), properties);
    TeamActivityStreamRequest request =
        new TeamActivityStreamRequest(ORGANIZATION_ID, TEAM_ID, PROJECTION_NAME, FILTER);
    TeamActivitySseSession session =
        stream.open(request, Optional.of(codec().encode(cursor(event(1))))).block();

    assertNotNull(session);
    List<Long> probeArrivals = new CopyOnWriteArrayList<>();
    long startedAt = System.nanoTime();
    List<ServerSentEvent<TeamActivityStreamEvent>> probes = session
        .body()
        .doOnNext(
            frame -> {
              if ("idle probe".equals(frame.comment())) {
                probeArrivals.add(System.nanoTime() - startedAt);
              }
            })
        .filter(frame -> "idle probe".equals(frame.comment()))
        .take(2)
        .collectList()
        .block(Duration.ofSeconds(10));

    assertNotNull(probes);
    assertEquals(2, probes.size());
    for (ServerSentEvent<TeamActivityStreamEvent> probe : probes) {
      assertEquals("heartbeat", probe.event());
      assertEquals(null, probe.data());
      assertEquals(null, probe.id());
      assertEquals("idle probe", probe.comment());
    }
    // The first probe must arrive within the configured bound even while nothing is emitted.
    long bound = 5L * Duration.ofMillis(150).toNanos();
    assertTrue(probeArrivals.get(0) <= bound, "first probe arrived late: " + probeArrivals);
    // Probes keep their own cadence: never postponed by heartbeats, never burst-repeated.
    long gap = probeArrivals.get(1) - probeArrivals.get(0);
    assertTrue(gap >= Duration.ofMillis(150).toNanos() - Duration.ofMillis(50).toNanos(),
        "probes fired too early: " + probeArrivals);
    assertTrue(gap <= bound, "second probe arrived late: " + probeArrivals);
  }

  @Test
  void keepsTheHeartbeatBranchReachableWhenTheProbeWindowIsLonger() {
    FakeStore store = new FakeStore();
    store.add(1);
    TeamActivityRealtimeProperties properties = new TeamActivityRealtimeProperties();
    properties.setBatchSize(2);
    properties.setPollInterval(Duration.ofMillis(10));
    properties.setHeartbeatInterval(Duration.ofMillis(80));
    // The probe window never opens, so the null frame in this test is a plain heartbeat.
    properties.setIdleProbeInterval(Duration.ofSeconds(60));
    TeamActivityRealtimeStream stream =
        new TeamActivityRealtimeStream(store, codec(), properties);
    TeamActivityStreamRequest request =
        new TeamActivityStreamRequest(ORGANIZATION_ID, TEAM_ID, PROJECTION_NAME, FILTER);
    TeamActivitySseSession session =
        stream.open(request, Optional.of(codec().encode(cursor(event(1))))).block();

    assertNotNull(session);
    ServerSentEvent<TeamActivityStreamEvent> heartbeat = session
        .body()
        .filter(frame -> frame.data() == null)
        .next()
        .block(Duration.ofSeconds(5));

    assertNotNull(heartbeat);
    assertEquals("heartbeat", heartbeat.event());
    assertEquals("heartbeat", heartbeat.comment());
    assertEquals(null, heartbeat.id());
  }

  @Test
  @SuppressWarnings("unchecked")
  void closesTheIdleConnectionWhenAuthorizationFailsOnAProbeFrame() {
    ActivityApplicationService service = mock(ActivityApplicationService.class);
    TeamAccessContext access = mock(TeamAccessContext.class);
    ActivityEvent visible = event(2, ActivityVisibility.TEAM_MEMBERS);
    when(service.canViewNow(access, ORGANIZATION_ID, TEAM_ID, visible)).thenReturn(true);
    // The stream-open authorization passes; only the later idle probe frame is denied, which
    // is exactly the moment a mid-session revocation surfaces.
    doNothing()
        .doThrow(new PolicyDeniedException("access this Team"))
        .when(service)
        .requireTeamAccess(access, ORGANIZATION_ID, TEAM_ID);

    TeamActivityRealtimeStream stream = mock(TeamActivityRealtimeStream.class);
    // The delayed probe and the never() tail prove termination: without the failing probe
    // revalidation the connection would stay open and the terminal signal would never arrive.
    Flux<ServerSentEvent<TeamActivityStreamEvent>> frames = Flux
        .concat(
            Flux.just(
                ServerSentEvent.<TeamActivityStreamEvent>builder(
                        TeamActivityStreamEvent.from(visible))
                    .id("visible-position")
                    .event(visible.eventType().value())
                    .build()),
            Mono.delay(Duration.ofMillis(150))
                .map(
                    ignored ->
                        ServerSentEvent.<TeamActivityStreamEvent>builder()
                            .event("heartbeat")
                            .comment("idle probe")
                            .build()),
            Flux.never());
    when(stream.open(any(), eq(Optional.empty())))
        .thenReturn(Mono.just(new TeamActivitySseSession(scope(), frames)));

    TeamRequestIdentityResolver resolver = mock(TeamRequestIdentityResolver.class);
    when(resolver.resolve(any(), eq(ORGANIZATION_ID), any())).thenReturn(Mono.just(access));
    ObjectProvider<TeamActivityCursorCodec> codecs = mock(ObjectProvider.class);
    ObjectProvider<TeamActivityRealtimeStream> streams = mock(ObjectProvider.class);
    when(codecs.getIfAvailable()).thenReturn(codec());
    when(streams.getIfAvailable()).thenReturn(stream);
    WebTestClient client =
        WebTestClient.bindToController(
                new TeamActivityController(service, resolver, codecs, streams))
            .controllerAdvice(new ApiExceptionHandler())
            .build();

    FluxExchangeResult<String> result = client
        .get()
        .uri(route())
        .accept(MediaType.TEXT_EVENT_STREAM)
        .exchange()
        .expectStatus()
        .isOk()
        .returnResult(String.class);

    List<String> delivered = new CopyOnWriteArrayList<>();
    Signal<List<String>> terminal = result
        .getResponseBody()
        .doOnNext(delivered::add)
        .collectList()
        .materialize()
        .block(Duration.ofSeconds(5));

    assertNotNull(terminal);
    assertTrue(terminal.isOnError(), "idle probe denial must terminate the SSE stream");
    assertTrue(
        delivered.stream().anyMatch(frame -> frame.contains("CS-2")),
        "the already authorized row is delivered before the probe closes the stream");
    // Once at stream open, once for the denied idle probe frame.
    Mockito.verify(service, Mockito.times(2))
        .requireTeamAccess(access, ORGANIZATION_ID, TEAM_ID);
  }

  private static TeamActivityCursorCodec codec() {
    return new TeamActivityCursorCodec(
        new TeamActivityCursorKeyRing("k1", Map.of("k1", encodedKey())),
        Clock.fixed(NOW, ZoneOffset.UTC),
        Duration.ofHours(24),
        Duration.ofSeconds(30));
  }

  private static ActivityCursorScope scope() {
    return ActivityCursorScope.of(
        ORGANIZATION_ID,
        TEAM_ID,
        PROJECTION_NAME,
        ProjectionGeneration.FIRST,
        SchemaVersion.V1,
        FILTER);
  }

  private static String route() {
    return "/api/v1/organizations/" + ORGANIZATION_ID + "/teams/" + TEAM_ID + "/activity/events";
  }

  private static TeamActivityCursor cursor(ActivityEvent event) {
    return TeamActivityCursor.from(scope(), event);
  }

  private static ActivityEvent event(long sequence) {
    return event(sequence, ActivityVisibility.TEAM_MEMBERS);
  }

  private static ActivityEvent event(long sequence, ActivityVisibility visibility) {
    UUID domainEventId = UUID.nameUUIDFromBytes(
        ("m9b-a07-probe-" + sequence).getBytes(StandardCharsets.UTF_8));
    ActivityPayloadSchema schema = new ActivityPayloadSchema(
        "activity.work-item-created",
        SchemaVersion.V1,
        Set.of("itemKey"),
        Set.of("title"));
    return ActivityEvent.project(
        domainEventId,
        ORGANIZATION_ID,
        TEAM_ID,
        PROJECTION_NAME,
        ProjectionGeneration.FIRST,
        SchemaVersion.V1,
        new TeamSequence(sequence),
        new EventType("WORK_ITEM_CREATED"),
        ActivityCategory.WORK_ITEM,
        visibility,
        new ActivitySubject(ActivitySubjectType.WORK_ITEM, WORK_ITEM_ID.value()),
        new ActivityActor(EventActorType.USER, Optional.of(ACTOR_ID)),
        List.of(
            new ActivityReference(ActivityReferenceType.TEAM, TEAM_ID.value()),
            new ActivityReference(ActivityReferenceType.WORK_ITEM, WORK_ITEM_ID.value())),
        UtcTimestamp.from(NOW),
        schema.createPayload(Map.of("itemKey", "CS-" + sequence, "title", "Activity")));
  }

  private static String encodedKey() {
    byte[] key = new byte[32];
    for (int index = 0; index < key.length; index++) {
      key[index] = (byte) (index + 23);
    }
    return Base64.getEncoder().encodeToString(key);
  }

  private static final class FakeStore implements TeamRealtimeEventStore {

    private final CopyOnWriteArrayList<ActivityEvent> events = new CopyOnWriteArrayList<>();
    private volatile ProjectionGeneration activeGeneration = ProjectionGeneration.FIRST;

    void add(long... sequences) {
      for (long sequence : sequences) {
        events.add(event(sequence));
      }
      events.sort(Comparator.comparing(ActivityEvent::teamSequence));
    }

    @Override
    public TeamActivitySnapshot snapshot(TeamActivitySnapshotRequest request) {
      ActivityCursorScope currentScope = scope();
      List<ActivityEvent> visible = events.stream()
          .filter(request.filter()::matches)
          .limit(request.limit())
          .toList();
      Optional<TeamActivityCursor> highWater = events.stream()
          .max(Comparator.comparing(ActivityEvent::teamSequence))
          .map(event -> TeamActivityCursor.from(currentScope, event));
      return new TeamActivitySnapshot(
          request,
          currentScope,
          visible,
          highWater,
          events.stream().filter(request.filter()::matches).count() > visible.size());
    }

    @Override
    public ActivityPage read(ActivityQuery query) {
      if (!query.cursorScope().projectionGeneration().equals(activeGeneration)) {
        throw new io.crewscope.application.activity.TeamActivityCursorExpiredException();
      }
      long after = query.after()
          .map(cursor -> cursor.teamSequence().value())
          .orElse(0L);
      List<ActivityEvent> candidates = events.stream()
          .filter(event -> event.teamSequence().value() > after)
          .filter(query.filter()::matches)
          .toList();
      List<ActivityEvent> selected = new ArrayList<>(
          candidates.subList(0, Math.min(candidates.size(), query.limit())));
      return new ActivityPage(query, selected, candidates.size() > selected.size());
    }
  }
}
