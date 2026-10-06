package io.crewscope.application.observability;

import java.util.List;
import java.util.UUID;

/**
 * Persistence Port scanning persisted {@code MODEL_USAGE_FACT_RECORDED} events for the
 * rollup rebuild (M10-F03). The rebuild reads the canonical event log directly — not the
 * dispatcher — so existing consumer receipts keep blocking the incremental path while
 * the projection is recomputed.
 */
public interface ModelUsageFactScan {

    /** One persisted event: its id (the keyset cursor) and canonical envelope JSON. */
    record ScannedUsageFact(UUID eventId, String eventJson) {}

    /**
     * Returns up to {@code limit} facts with {@code eventId} strictly after
     * {@code afterEventId} (null starts from the beginning), in event id order.
     */
    List<ScannedUsageFact> findFactsAfter(UUID afterEventId, int limit);
}
