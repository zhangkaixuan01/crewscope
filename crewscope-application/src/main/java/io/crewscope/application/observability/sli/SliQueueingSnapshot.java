package io.crewscope.application.observability.sli;

/**
 * Current queueing facts for one organization (M12-I03): outbox backlog depth with its
 * oldest entry's age, and the largest projection checkpoint lag. A snapshot answers
 * "how far behind right now"; planned rebuilds inflate projection lag and are called
 * out in the response notes rather than filtered here.
 */
public record SliQueueingSnapshot(
        long outboxBacklog, long outboxOldestAgeSeconds, long projectionMaxLagSeconds) {

    /** The worst current delay across the two queueing legs. */
    public long worstDelaySeconds() {
        return Math.max(outboxOldestAgeSeconds, projectionMaxLagSeconds);
    }
}
