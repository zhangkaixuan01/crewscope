package io.crewscope.server.config.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Connection-level policy for the collaboration WebSocket channel (M11-I01a). Heartbeat and
 * idle constants mirror ADR-032: a 15s server ping and a 30s inbound-idle disconnect, aligned
 * with the Team Activity SSE heartbeat this channel coexists with.
 */
@ConfigurationProperties(prefix = "crewscope.collaboration-realtime")
public class CollaborationRealtimeProperties {

  private boolean enabled;
  private Duration heartbeatInterval = Duration.ofSeconds(15);
  private Duration inboundIdleTimeout = Duration.ofSeconds(30);
  private int maxConnectionsPerPrincipal = 16;
  private int softConnectionBudget = 200;
  private int hardConnectionLimit = 500;
  private int outboundFrameBufferLimit = 256;
  private DataSize maxInboundFrameSize = DataSize.ofKilobytes(8);

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Duration getHeartbeatInterval() {
    return heartbeatInterval;
  }

  public void setHeartbeatInterval(Duration heartbeatInterval) {
    this.heartbeatInterval =
        requirePositive(heartbeatInterval, "heartbeatInterval", Duration.ofMinutes(5));
  }

  public Duration getInboundIdleTimeout() {
    return inboundIdleTimeout;
  }

  public void setInboundIdleTimeout(Duration inboundIdleTimeout) {
    this.inboundIdleTimeout =
        requirePositive(inboundIdleTimeout, "inboundIdleTimeout", Duration.ofMinutes(30));
  }

  public int getMaxConnectionsPerPrincipal() {
    return maxConnectionsPerPrincipal;
  }

  public void setMaxConnectionsPerPrincipal(int maxConnectionsPerPrincipal) {
    if (maxConnectionsPerPrincipal < 1 || maxConnectionsPerPrincipal > 64) {
      throw new IllegalArgumentException("maxConnectionsPerPrincipal must be between 1 and 64");
    }
    this.maxConnectionsPerPrincipal = maxConnectionsPerPrincipal;
  }

  public int getSoftConnectionBudget() {
    return softConnectionBudget;
  }

  public void setSoftConnectionBudget(int softConnectionBudget) {
    if (softConnectionBudget < 1 || softConnectionBudget > 10_000) {
      throw new IllegalArgumentException("softConnectionBudget must be between 1 and 10000");
    }
    this.softConnectionBudget = softConnectionBudget;
  }

  public int getHardConnectionLimit() {
    return hardConnectionLimit;
  }

  public void setHardConnectionLimit(int hardConnectionLimit) {
    if (hardConnectionLimit < 1 || hardConnectionLimit > 20_000) {
      throw new IllegalArgumentException("hardConnectionLimit must be between 1 and 20000");
    }
    if (hardConnectionLimit <= softConnectionBudget) {
      throw new IllegalArgumentException("hardConnectionLimit must exceed softConnectionBudget");
    }
    this.hardConnectionLimit = hardConnectionLimit;
  }

  public int getOutboundFrameBufferLimit() {
    return outboundFrameBufferLimit;
  }

  public void setOutboundFrameBufferLimit(int outboundFrameBufferLimit) {
    if (outboundFrameBufferLimit < 1 || outboundFrameBufferLimit > 4_096) {
      throw new IllegalArgumentException("outboundFrameBufferLimit must be between 1 and 4096");
    }
    this.outboundFrameBufferLimit = outboundFrameBufferLimit;
  }

  public DataSize getMaxInboundFrameSize() {
    return maxInboundFrameSize;
  }

  public void setMaxInboundFrameSize(DataSize maxInboundFrameSize) {
    if (maxInboundFrameSize == null
        || maxInboundFrameSize.compareTo(DataSize.ofKilobytes(1)) < 0
        || maxInboundFrameSize.compareTo(DataSize.ofKilobytes(64)) > 0) {
      throw new IllegalArgumentException("maxInboundFrameSize must be between 1KB and 64KB");
    }
    this.maxInboundFrameSize = maxInboundFrameSize;
  }

  private static Duration requirePositive(Duration value, String name, Duration maximum) {
    if (value == null || value.isZero() || value.isNegative() || value.compareTo(maximum) > 0) {
      throw new IllegalArgumentException(name + " must be positive and at most " + maximum);
    }
    return value;
  }
}
