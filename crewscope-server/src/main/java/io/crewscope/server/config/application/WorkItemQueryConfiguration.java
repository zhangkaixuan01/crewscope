package io.crewscope.server.config.application;

import io.crewscope.server.api.TeamActivityCursorKeyRing;
import io.crewscope.server.api.WorkItemCursorCodec;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Signed-cursor wiring for the M9b-A06 WorkItem list read model.
 *
 * <p>The cursor reuses the Team Activity key ring (rotatable HMAC keys) under its own signing
 * domain, so work-item tokens and activity tokens are never interchangeable even under one key.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkItemQueryProperties.class)
public class WorkItemQueryConfiguration {

  @Bean
  WorkItemCursorCodec workItemCursorCodec(
      TeamActivityCursorKeyRing keyRing, WorkItemQueryProperties properties) {
    return new WorkItemCursorCodec(
        keyRing, Clock.systemUTC(), properties.getCursorMaximumAge());
  }
}
