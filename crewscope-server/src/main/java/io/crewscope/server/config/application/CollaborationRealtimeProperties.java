package io.crewscope.server.config.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Connection- and subscription-level policy for the collaboration WebSocket channel
 * (M11-I01a/I01b). Heartbeat and idle constants mirror ADR-032: a 15s server ping and a 30s
 * inbound-idle disconnect, aligned with the Team Activity SSE heartbeat this channel
 * coexists with. Presence carries the ADR-032 45s TTL (3 x heartbeat, tolerating two lost
 * pings) plus the periodic sweep covering scopes nobody reads.
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
  private int maxSubscriptionsPerConnection = 32;
  private Duration presenceTtl = Duration.ofSeconds(45);
  private Duration presenceSweepInterval = Duration.ofSeconds(60);
  private String environment = "development";

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

  public int getMaxSubscriptionsPerConnection() {
    return maxSubscriptionsPerConnection;
  }

  public void setMaxSubscriptionsPerConnection(int maxSubscriptionsPerConnection) {
    if (maxSubscriptionsPerConnection < 1 || maxSubscriptionsPerConnection > 256) {
      throw new IllegalArgumentException("maxSubscriptionsPerConnection must be between 1 and 256");
    }
    this.maxSubscriptionsPerConnection = maxSubscriptionsPerConnection;
  }

  public Duration getPresenceTtl() {
    return presenceTtl;
  }

  public void setPresenceTtl(Duration presenceTtl) {
    this.presenceTtl = requirePositive(presenceTtl, "presenceTtl", Duration.ofMinutes(10));
  }

  public Duration getPresenceSweepInterval() {
    return presenceSweepInterval;
  }

  public void setPresenceSweepInterval(Duration presenceSweepInterval) {
    this.presenceSweepInterval =
        requirePositive(presenceSweepInterval, "presenceSweepInterval", Duration.ofHours(1));
  }

  public String getEnvironment() {
    return environment;
  }

  public void setEnvironment(String environment) {
    if (environment == null || environment.strip().isEmpty()) {
      throw new IllegalArgumentException("environment must not be blank");
    }
    this.environment = environment;
  }

  private static Duration requirePositive(Duration value, String name, Duration maximum) {
    if (value == null || value.isZero() || value.isNegative() || value.compareTo(maximum) > 0) {
      throw new IllegalArgumentException(name + " must be positive and at most " + maximum);
    }
    return value;
  }
}
