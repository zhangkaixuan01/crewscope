package io.crewscope.application.activity;

/**
 * Durable source shared by Team Activity snapshots, JSON gaps and realtime SSE delivery.
 *
 * <p>An adapter must read snapshot rows, the active Projection Pointer and the high-water position
 * from one PostgreSQL read snapshot. {@link #read(ActivityQuery)} must reject retired Generations,
 * incompatible projection schemas and positions removed by retention with {@link
 * TeamActivityCursorExpiredException}.
 */
public interface TeamRealtimeEventStore {

    TeamActivitySnapshot snapshot(TeamActivitySnapshotRequest request);

    ActivityPage read(ActivityQuery query);
}
