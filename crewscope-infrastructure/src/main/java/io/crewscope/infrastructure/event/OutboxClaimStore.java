package io.crewscope.infrastructure.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Transactional store for leasing and conditionally completing Outbox publications. */
public interface OutboxClaimStore {

    /** Maximum persisted publisher identity length defined by the Outbox schema. */
    int MAX_WORKER_ID_LENGTH = 200;

    /** Reaps expired leases and claims the currently publishable partition heads. */
    List<ClaimedOutboxEvent> claimAvailable(
            String workerId, Instant now, OutboxDeliveryPolicy policy);

    /** Completes a live lease; false means the token or lease is stale. */
    boolean markDelivered(UUID outboxId, UUID claimToken, Instant deliveredAt);

    /** Records a transport failure on a live lease; false means the token or lease is stale. */
    boolean markFailed(
            UUID outboxId,
            UUID claimToken,
            Instant failedAt,
            String errorCode,
            OutboxDeliveryPolicy policy);
}
