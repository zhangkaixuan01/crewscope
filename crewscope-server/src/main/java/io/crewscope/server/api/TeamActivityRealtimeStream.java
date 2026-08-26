package io.crewscope.server.api;

import io.crewscope.application.activity.ActivityCursorScope;
import io.crewscope.application.activity.ActivityPage;
import io.crewscope.application.activity.ActivityQuery;
import io.crewscope.application.activity.TeamActivityCursor;
import io.crewscope.application.activity.TeamActivitySnapshot;
import io.crewscope.application.activity.TeamRealtimeEventStore;
import io.crewscope.domain.activity.ActivityEvent;
import io.crewscope.server.config.application.TeamActivityRealtimeProperties;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Generation-aware Team Activity snapshot and SSE recovery engine.
 *
 * <p>Only empty polling opportunities and heartbeats are coalesced. Activity rows are read and
 * delivered one bounded page at a time in TeamSequence order, so a slow subscriber cannot cause a
 * business event to be dropped or an unbounded database-prefetch queue to grow.
 */
public final class TeamActivityRealtimeStream {

  private final TeamRealtimeEventStore store;
  private final TeamActivityCursorCodec cursorCodec;
  private final TeamActivityRealtimeProperties properties;

  public TeamActivityRealtimeStream(
      TeamRealtimeEventStore store,
      TeamActivityCursorCodec cursorCodec,
      TeamActivityRealtimeProperties properties) {
    this.store = Objects.requireNonNull(store, "store");
    this.cursorCodec = Objects.requireNonNull(cursorCodec, "cursorCodec");
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  /** Reads rows, Pointer and high-water Cursor before an HTTP adapter commits its response. */
  public Mono<TeamActivitySnapshotEnvelope> snapshot(TeamActivityStreamRequest request) {
    TeamActivityStreamRequest required = Objects.requireNonNull(request, "request");
    return blocking(() -> store.snapshot(required.snapshotRequest(properties.getBatchSize())))
        .map(
            snapshot ->
                new TeamActivitySnapshotEnvelope(
                    snapshot, snapshot.snapshotCursor().map(cursorCodec::encode)));
  }

  /**
   * Preloads the initial durable batch before returning a single-subscriber SSE session.
   *
   * <p>With a resume token, the Store revalidates Generation, schema and retention. Without one, a
   * bounded current snapshot is emitted; a truncated snapshot drains from its last visible row,
   * while a complete snapshot starts polling from its high-water position.
   */
  public Mono<TeamActivitySseSession> open(
      TeamActivityStreamRequest request, Optional<String> resumeToken) {
    TeamActivityStreamRequest required = Objects.requireNonNull(request, "request");
    Optional<String> token = Objects.requireNonNull(resumeToken, "resumeToken")
        .filter(value -> !value.isBlank());
    if (token.isPresent()) {
      TeamActivityCursor cursor =
          cursorCodec.decode(
              token.orElseThrow(),
              required.organizationId(),
              required.teamId(),
              required.projectionName(),
              required.filter());
      ActivityQuery query = query(required, cursor.scope(), Optional.of(cursor));
      return blocking(() -> store.read(query))
          .map(page -> session(required, Seed.fromPage(page, cursor)));
    }
    return blocking(() -> store.snapshot(required.snapshotRequest(properties.getBatchSize())))
        .map(snapshot -> session(required, Seed.fromSnapshot(snapshot)));
  }

  private TeamActivitySseSession session(
      TeamActivityStreamRequest request, Seed seed) {
    AtomicBoolean subscribed = new AtomicBoolean();
    Flux<ServerSentEvent<TeamActivityStreamEvent>> body =
        Flux.defer(
            () -> {
              if (!subscribed.compareAndSet(false, true)) {
                return Flux.error(
                    new IllegalStateException("Team Activity SSE session supports one subscriber"));
              }
              ConnectionState state = new ConnectionState(seed.position());
              Flux<ServerSentEvent<TeamActivityStreamEvent>> initial =
                  frames(seed.events(), seed.scope(), state);
              Flux<ServerSentEvent<TeamActivityStreamEvent>> initialRemainder =
                  seed.hasMore()
                      ? drain(request, seed.scope(), seed.pageCursor(), state)
                      : Flux.empty();
              Flux<ServerSentEvent<TeamActivityStreamEvent>> updates =
                  Flux.interval(properties.getPollInterval())
                      // Backpressure coalesces polling signals; it never drops an Activity row.
                      .onBackpressureDrop()
                      .concatMap(
                          ignored -> poll(request, seed.scope(), state), 1);
              return Flux.concat(initial, initialRemainder, updates);
            });
    return new TeamActivitySseSession(seed.scope(), body);
  }

  private Flux<ServerSentEvent<TeamActivityStreamEvent>> poll(
      TeamActivityStreamRequest request,
      ActivityCursorScope scope,
      ConnectionState state) {
    return drain(request, scope, Optional.ofNullable(state.position().get()), state)
        .switchIfEmpty(Mono.defer(() -> state.heartbeatIfDue(properties)));
  }

  private Flux<ServerSentEvent<TeamActivityStreamEvent>> drain(
      TeamActivityStreamRequest request,
      ActivityCursorScope scope,
      Optional<TeamActivityCursor> after,
      ConnectionState state) {
    ActivityQuery query = query(request, scope, after);
    return blocking(() -> store.read(query))
        .flatMapMany(
            page -> {
              Flux<ServerSentEvent<TeamActivityStreamEvent>> current =
                  frames(page.events(), scope, state);
              if (!page.hasMore()) {
                return current;
              }
              TeamActivityCursor next = page.resumeCursor().orElseThrow();
              return current.concatWith(
                  Flux.defer(() -> drain(request, scope, Optional.of(next), state)));
            });
  }

  private Flux<ServerSentEvent<TeamActivityStreamEvent>> frames(
      List<ActivityEvent> events,
      ActivityCursorScope scope,
      ConnectionState state) {
    return Flux.fromIterable(events)
        .map(
            event -> {
              TeamActivityCursor eventCursor =
                  TeamActivityCursor.from(scope, event);
              state.advance(eventCursor);
              return ServerSentEvent.<TeamActivityStreamEvent>builder(
                      TeamActivityStreamEvent.from(event))
                  .id(cursorCodec.encode(eventCursor))
                  .event(event.eventType().value())
                  .build();
            });
  }

  private ActivityQuery query(
      TeamActivityStreamRequest request,
      ActivityCursorScope scope,
      Optional<TeamActivityCursor> after) {
    return new ActivityQuery(scope, request.filter(), after, properties.getBatchSize());
  }

  private static <T> Mono<T> blocking(Callable<T> action) {
    return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
  }

  private record Seed(
      ActivityCursorScope scope,
      List<ActivityEvent> events,
      Optional<TeamActivityCursor> pageCursor,
      TeamActivityCursor position,
      boolean hasMore) {

    private Seed {
      scope = Objects.requireNonNull(scope, "scope");
      events = List.copyOf(Objects.requireNonNull(events, "events"));
      pageCursor = Objects.requireNonNull(pageCursor, "pageCursor");
      if (hasMore && pageCursor.isEmpty()) {
        throw new IllegalArgumentException("A continued Team Activity page requires a cursor");
      }
    }

    static Seed fromPage(ActivityPage page, TeamActivityCursor requestedCursor) {
      Optional<TeamActivityCursor> pageCursor = page.resumeCursor();
      return new Seed(
          page.query().cursorScope(),
          page.events(),
          pageCursor,
          pageCursor.orElse(requestedCursor),
          page.hasMore());
    }

    static Seed fromSnapshot(TeamActivitySnapshot snapshot) {
      Optional<TeamActivityCursor> visibleCursor = snapshot.events().isEmpty()
          ? Optional.empty()
          : Optional.of(TeamActivityCursor.from(
              snapshot.cursorScope(),
              snapshot.events().get(snapshot.events().size() - 1)));
      // A direct SSE open must drain a truncated snapshot instead of jumping to high-water.
      Optional<TeamActivityCursor> continuation = snapshot.hasMore()
          ? visibleCursor
          : Optional.empty();
      return new Seed(
          snapshot.cursorScope(),
          snapshot.events(),
          continuation,
          snapshot.hasMore()
              ? visibleCursor.orElse(null)
              : snapshot.snapshotCursor().orElse(null),
          snapshot.hasMore());
    }
  }

  private static final class ConnectionState {

    private final AtomicReference<TeamActivityCursor> position;
    private final AtomicLong lastEmissionNanos = new AtomicLong(System.nanoTime());

    private ConnectionState(TeamActivityCursor position) {
      this.position = new AtomicReference<>(position);
    }

    AtomicReference<TeamActivityCursor> position() {
      return position;
    }

    void advance(TeamActivityCursor cursor) {
      position.updateAndGet(
          current ->
              current == null || cursor.teamSequence().isAfter(current.teamSequence())
                  ? cursor
                  : current);
      lastEmissionNanos.set(System.nanoTime());
    }

    Mono<ServerSentEvent<TeamActivityStreamEvent>> heartbeatIfDue(
        TeamActivityRealtimeProperties properties) {
      long elapsed = System.nanoTime() - lastEmissionNanos.get();
      if (elapsed < properties.getHeartbeatInterval().toNanos()) {
        return Mono.empty();
      }
      lastEmissionNanos.set(System.nanoTime());
      return Mono.just(
          ServerSentEvent.<TeamActivityStreamEvent>builder()
              .event("heartbeat")
              .comment("heartbeat")
              .build());
    }
  }
}
