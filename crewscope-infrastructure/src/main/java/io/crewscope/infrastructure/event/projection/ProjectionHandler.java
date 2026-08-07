package io.crewscope.infrastructure.event.projection;

/** One deterministic database projection advanced by the checkpointed runner. */
public interface ProjectionHandler {

    /** Stable name used in consumer receipts and persistent checkpoints. */
    String projectionName();

    /** Applies the projection side effect inside the runner's existing transaction. */
    void project(ProjectionEvent event);
}
