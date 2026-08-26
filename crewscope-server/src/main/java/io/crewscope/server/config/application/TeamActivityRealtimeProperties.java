package io.crewscope.server.config.application;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded polling, heartbeat and signed-cursor policy for Team Activity realtime sessions. */
@ConfigurationProperties(prefix = "crewscope.team-activity-realtime")
public class TeamActivityRealtimeProperties {

  private boolean enabled;
  private Duration pollInterval = Duration.ofMillis(500);
  private Duration heartbeatInterval = Duration.ofSeconds(15);
  private int batchSize = 100;
  private Duration cursorMaximumAge = Duration.ofHours(24);
  private Duration cursorFutureSkew = Duration.ofSeconds(30);
  private String currentKeyId = "";
  private Map<String, String> keys = new LinkedHashMap<>();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Duration getPollInterval() {
    return pollInterval;
  }

  public void setPollInterval(Duration pollInterval) {
    this.pollInterval = requirePositive(pollInterval, "pollInterval", Duration.ofMinutes(1));
  }

  public Duration getHeartbeatInterval() {
    return heartbeatInterval;
  }

  public void setHeartbeatInterval(Duration heartbeatInterval) {
    this.heartbeatInterval =
        requirePositive(heartbeatInterval, "heartbeatInterval", Duration.ofMinutes(5));
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    if (batchSize < 1 || batchSize > 200) {
      throw new IllegalArgumentException("batchSize must be between 1 and 200");
    }
    this.batchSize = batchSize;
  }

  public Duration getCursorMaximumAge() {
    return cursorMaximumAge;
  }

  public void setCursorMaximumAge(Duration cursorMaximumAge) {
    this.cursorMaximumAge =
        requireAtLeastOneSecond(cursorMaximumAge, "cursorMaximumAge", Duration.ofDays(30));
  }

  public Duration getCursorFutureSkew() {
    return cursorFutureSkew;
  }

  public void setCursorFutureSkew(Duration cursorFutureSkew) {
    this.cursorFutureSkew =
        requireAtLeastOneSecond(cursorFutureSkew, "cursorFutureSkew", Duration.ofMinutes(5));
  }

  public String getCurrentKeyId() {
    return currentKeyId;
  }

  public void setCurrentKeyId(String currentKeyId) {
    this.currentKeyId = currentKeyId;
  }

  public Map<String, String> getKeys() {
    return keys;
  }

  public void setKeys(Map<String, String> keys) {
    this.keys = keys;
  }

  private static Duration requirePositive(Duration value, String name, Duration maximum) {
    if (value == null || value.isZero() || value.isNegative() || value.compareTo(maximum) > 0) {
      throw new IllegalArgumentException(name + " must be positive and at most " + maximum);
    }
    return value;
  }

  private static Duration requireAtLeastOneSecond(
      Duration value, String name, Duration maximum) {
    if (value == null
        || value.compareTo(Duration.ofSeconds(1)) < 0
        || value.compareTo(maximum) > 0) {
      throw new IllegalArgumentException(
          name + " must be at least one second and at most " + maximum);
    }
    return value;
  }
}
