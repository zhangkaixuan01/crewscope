package io.crewscope.server.config.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded database-polling policy for Conversation Event SSE subscriptions. */
@ConfigurationProperties(prefix = "crewscope.conversation-events")
public class ConversationEventStreamProperties {

  private Duration pollInterval = Duration.ofMillis(500);
  private int batchSize = 100;

  public Duration getPollInterval() {
    return pollInterval;
  }

  public void setPollInterval(Duration pollInterval) {
    if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
      throw new IllegalArgumentException("pollInterval must be positive");
    }
    this.pollInterval = pollInterval;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    if (batchSize < 1 || batchSize > 100) {
      throw new IllegalArgumentException("batchSize must be between 1 and 100");
    }
    this.batchSize = batchSize;
  }
}
