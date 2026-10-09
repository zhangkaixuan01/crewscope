package io.crewscope.probe.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Probe-owned constants. Every value mirrors a constant frozen by ADR-032 or
 * ADR-038 section 3.1; the defaults in application.yml are the frozen values
 * and are not tuned per scenario.
 */
@ConfigurationProperties("probe")
public class ProbeProperties {

    /** Server-side ping cadence, aligned with the Team Activity SSE heartbeat. */
    private final Duration heartbeatInterval;

    /** A connection with no inbound frame for this long is closed. */
    private final Duration inboundIdleLimit;

    /** Presence key TTL: 3x the heartbeat, tolerating two lost heartbeats. */
    private final Duration presenceTtl;

    /** Membership revalidation cache bound, aligned with the SSE idle probe. */
    private final Duration revalidationCache;

    /** Per-connection outbound buffer; a client that lets it fill is closed. */
    private final int outboundBuffer;

    public ProbeProperties(Duration heartbeatInterval, Duration inboundIdleLimit,
            Duration presenceTtl, Duration revalidationCache, int outboundBuffer) {
        this.heartbeatInterval = heartbeatInterval;
        this.inboundIdleLimit = inboundIdleLimit;
        this.presenceTtl = presenceTtl;
        this.revalidationCache = revalidationCache;
        this.outboundBuffer = outboundBuffer;
    }

    public Duration heartbeatInterval() {
        return heartbeatInterval;
    }

    public Duration inboundIdleLimit() {
        return inboundIdleLimit;
    }

    public Duration presenceTtl() {
        return presenceTtl;
    }

    public Duration revalidationCache() {
        return revalidationCache;
    }

    public int outboundBuffer() {
        return outboundBuffer;
    }
}
