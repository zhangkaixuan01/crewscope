package io.crewscope.server.config.application;

import io.crewscope.server.api.TeamActivityCursorKeyRing;
import io.crewscope.server.api.WorkGraphBlockedCursorCodec;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Signed-cursor wiring for the M11-A02 blocked-work aggregation.
 *
 * <p>The cursor reuses the Team Activity key ring (rotatable HMAC keys) under its own signing
 * domain, so blocked-work-graph tokens and activity or work-item tokens are never
 * interchangeable even under one key. Thirty days matches the plan's cursor lifetime.
 */
@Configuration(proxyBeanMethods = false)
public class WorkGraphQueryConfiguration {

  @Bean
  WorkGraphBlockedCursorCodec workGraphBlockedCursorCodec(TeamActivityCursorKeyRing keyRing) {
    return new WorkGraphBlockedCursorCodec(keyRing, Clock.systemUTC(), Duration.ofDays(30));
  }
}
