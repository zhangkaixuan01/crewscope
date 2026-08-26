package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.activity.ActivityCursorScope;
import io.crewscope.application.activity.ActivityFilter;
import io.crewscope.application.activity.ActivityPage;
import io.crewscope.application.activity.ActivityQuery;
import io.crewscope.application.activity.TeamActivityCursor;
import io.crewscope.application.activity.TeamActivityCursorExpiredException;
import io.crewscope.application.activity.TeamActivitySnapshot;
import io.crewscope.application.activity.TeamActivitySnapshotRequest;
import io.crewscope.application.activity.TeamRealtimeEventStore;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.test.StepVerifier;

/** Dual connection, disconnect, gap, heartbeat and backpressure proof for M6-E05 SSE. */
class TeamActivityRealtimeStreamM6E05Test {

  private static final Instant NOW = Instant.parse("2026-08-26T01:00:00Z");
  private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
  private static final TeamId TEAM_ID = TeamId.generate();
  private static final ProjectionName PROJECTION_NAME = new ProjectionName("team-activity");
  private static final ActivityFilter FILTER = ActivityFilter.ALL;
  private static final WorkItemId WORK_ITEM_ID = WorkItemId.generate();
  private static final PrincipalId ACTOR_ID = PrincipalId.generate();

  private FakeStore store;
  private TeamActivityCursorCodec codec;
  private TeamActivityRealtimeStream stream;
  private TeamActivityStreamRequest request;

  @BeforeEach
  void setUp() {
    store = new FakeStore();
    TeamActivityRealtimeProperties properties = new TeamActivityRealtimeProperties();
    properties.setBatchSize(2);
    properties.setPollInterval(Duration.ofMillis(10));
    properties.setHeartbeatInterval(Duration.ofMillis(25));
    codec = new TeamActivityCursorCodec(
        new TeamActivityCursorKeyRing("k1", Map.of("k1", encodedKey())),
        Clock.fixed(NOW, ZoneOffset.UTC),
        Duration.ofHours(24),
        Duration.ofSeconds(30));
    stream = new TeamActivityRealtimeStream(store, codec, properties);
    request = new TeamActivityStreamRequest(
        ORGANIZATION_ID, TEAM_ID, PROJECTION_NAME, FILTER);
  }

  @Test
  void givesTwoConnectionsIndependentPositionsAndDrainsEveryBoundedGapPage() {
    store.add(1, 2, 3, 4, 5, 6);
    String afterOne = codec.encode(cursor(event(1)));

    TeamActivitySseSession first = stream.open(request, Optional.of(afterOne)).block();
    TeamActivitySseSession second = stream.open(request, Optional.of(afterOne)).block();
    assertNotNull(first);
    assertNotNull(second);

    List<Long> firstSequences = first.body().take(5).map(this::sequence).collectList()
        .block(Duration.ofSeconds(2));
    List<Long> secondSequences = second.body().take(5).map(this::sequence).collectList()
        .block(Duration.ofSeconds(2));

    assertEquals(List.of(2L, 3L, 4L, 5L, 6L), firstSequences);
    assertEquals(firstSequences, secondSequences);
    assertTrue(store.maximumRequestedLimit() <= 2);
  }

  @Test
  void reconnectsFromTheLastAppliedSseIdWithoutLosingNewRows() {
    store.add(1, 2);
    String afterOne = codec.encode(cursor(event(1)));
    ServerSentEvent<TeamActivityStreamEvent> delivered = stream
        .open(request, Optional.of(afterOne))
        .block()
        .body()
        .next()
        .block(Duration.ofSeconds(1));
    assertNotNull(delivered);
    assertEquals(2L, sequence(delivered));

    store.add(3, 4);
    List<Long> resumed = stream
        .open(request, Optional.of(delivered.id()))
        .block()
        .body()
        .take(2)
        .map(this::sequence)
        .collectList()
        .block(Duration.ofSeconds(2));

    assertEquals(List.of(3L, 4L), resumed);
  }

  @Test
  void preservesBusinessRowsForASlowSubscriberAndOrdersSameMicrosecondEventsBySequence() {
    store.add(1, 2, 3, 4, 5);
    String afterOne = codec.encode(cursor(event(1)));
    TeamActivitySseSession session = stream.open(request, Optional.of(afterOne)).block();

    StepVerifier.create(session.body().take(4).map(this::sequence), 0)
        .thenRequest(1)
        .expectNext(2L)
        .thenAwait(Duration.ofMillis(40))
        .thenRequest(1)
        .expectNext(3L)
        .thenRequest(2)
        .expectNext(4L, 5L)
        .verifyComplete();
  }

  @Test
  void sendsCommentHeartbeatsWithoutAdvancingTheDurableCursor() {
    store.add(1);
    String afterOne = codec.encode(cursor(event(1)));
    TeamActivitySseSession session = stream.open(request, Optional.of(afterOne)).block();

    ServerSentEvent<TeamActivityStreamEvent> heartbeat = session.body()
        .filter(frame -> frame.data() == null)
        .next()
        .block(Duration.ofSeconds(1));

    assertNotNull(heartbeat);
    assertEquals("heartbeat", heartbeat.event());
    assertEquals(null, heartbeat.id());
  }

  @Test
  void closesAnExistingConnectionWhenItsProjectionGenerationExpires() {
    store.add(1);
    String afterOne = codec.encode(cursor(event(1)));
    TeamActivitySseSession session = stream.open(request, Optional.of(afterOne)).block();
    store.activate(new ProjectionGeneration(2));

    StepVerifier.create(session.body())
        .expectError(TeamActivityCursorExpiredException.class)
        .verify(Duration.ofSeconds(1));
  }

  @Test
  void closesTheSnapshotToSseRaceWithItsSignedHighWaterCursor() {
    store.add(1, 2);
    TeamActivitySnapshotEnvelope snapshot = stream.snapshot(request).block();
    assertNotNull(snapshot);
    assertEquals(List.of(1L, 2L), snapshot.snapshot().events().stream()
        .map(event -> event.teamSequence().value())
        .toList());

    store.add(3);
    List<Long> gap = stream
        .open(request, snapshot.snapshotCursor())
        .block()
        .body()
        .take(1)
        .map(this::sequence)
        .collectList()
        .block(Duration.ofSeconds(1));

    assertEquals(List.of(3L), gap);
  }

  private long sequence(ServerSentEvent<TeamActivityStreamEvent> frame) {
    return frame.data().activity().teamSequence().value();
  }

  private static TeamActivityCursor cursor(ActivityEvent event) {
    return TeamActivityCursor.from(scope(ProjectionGeneration.FIRST), event);
  }

  private static ActivityCursorScope scope(ProjectionGeneration generation) {
    return ActivityCursorScope.of(
        ORGANIZATION_ID,
        TEAM_ID,
        PROJECTION_NAME,
        generation,
        SchemaVersion.V1,
        FILTER);
  }

  private static ActivityEvent event(long sequence) {
    UUID domainEventId = UUID.nameUUIDFromBytes(
        ("m6-e05-stream-" + sequence).getBytes(StandardCharsets.UTF_8));
    ActivityPayloadSchema schema = new ActivityPayloadSchema(
        "work-item.changed", SchemaVersion.V1, Set.of("workItemKey"), Set.of());
    return ActivityEvent.project(
        domainEventId,
        ORGANIZATION_ID,
        TEAM_ID,
        PROJECTION_NAME,
        ProjectionGeneration.FIRST,
        SchemaVersion.V1,
        new TeamSequence(sequence),
        new EventType("WORK_ITEM_STATUS_CHANGED"),
        ActivityCategory.WORK_ITEM,
        ActivityVisibility.TEAM_MEMBERS,
        new ActivitySubject(ActivitySubjectType.WORK_ITEM, WORK_ITEM_ID.value()),
        new ActivityActor(EventActorType.USER, Optional.of(ACTOR_ID)),
        List.of(
            new ActivityReference(ActivityReferenceType.TEAM, TEAM_ID.value()),
            new ActivityReference(ActivityReferenceType.WORK_ITEM, WORK_ITEM_ID.value())),
        // Identical PostgreSQL microsecond timestamps prove ordering does not use occurredAt.
        UtcTimestamp.parse("2026-08-26T01:00:00.123456Z"),
        schema.createPayload(Map.of("workItemKey", "CS-42")));
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
    private final CopyOnWriteArrayList<Integer> requestedLimits = new CopyOnWriteArrayList<>();
    private volatile ProjectionGeneration activeGeneration = ProjectionGeneration.FIRST;

    void add(long... sequences) {
      for (long sequence : sequences) {
        events.add(event(sequence));
      }
      events.sort(Comparator.comparing(ActivityEvent::teamSequence));
    }

    void activate(ProjectionGeneration generation) {
      activeGeneration = generation;
    }

    int maximumRequestedLimit() {
      return requestedLimits.stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    @Override
    public TeamActivitySnapshot snapshot(TeamActivitySnapshotRequest request) {
      ActivityCursorScope currentScope = scope(activeGeneration);
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
      requestedLimits.add(query.limit());
      if (!query.cursorScope().projectionGeneration().equals(activeGeneration)) {
        throw new TeamActivityCursorExpiredException();
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
