package io.crewscope.application.observability.sli;

import java.util.Optional;

/**
 * Deployment-scoped Timer percentiles for the M12-I03 SLI surface. The window is the
 * process lifetime — honest about what an in-process registry can offer — and each
 * method names its exclusion discipline (the API latency methods exclude actuator
 * routes and file-download streams). Empty means "no samples yet", never zero.
 */
public interface SliTimerStatistics {

    /** API request latency P95 excluding /actuator/* and file-download route templates. */
    Optional<Distribution> apiLatencyP95();

    /** 5xx share of the same excluded API population. */
    Optional<Double> apiServerErrorRatio();

    /** Model-call initiation to the first response event (model side of first token). */
    Optional<Distribution> firstTokenModelSideP95();

    /** Invocation acceptance to the first model-call initiation (application overhead). */
    Optional<Distribution> firstTokenAppOverheadP95();

    /** Outbox claim-to-delivery duration. */
    Optional<Distribution> outboxDeliveryP95();

    /** Projection replay duration. */
    Optional<Distribution> projectionReplayP95();

    /** Unified knowledge retrieval duration (embedding chain plus both vector searches). */
    Optional<Distribution> knowledgeRetrievalP95();

    /** One deployment-wide latency reading merged across series. */
    record Distribution(double percentile95Millis, long sampleCount) {}
}
