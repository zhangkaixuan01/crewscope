package io.crewscope.server.config.application;

import io.crewscope.application.activity.TeamRealtimeEventStore;
import io.crewscope.server.api.TeamActivityCursorCodec;
import io.crewscope.server.api.TeamActivityCursorKeyRing;
import io.crewscope.server.api.TeamActivityRealtimeStream;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Conditional wiring for the M6 Team realtime engine; M6-I01 contributes the durable Store. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TeamActivityRealtimeProperties.class)
@ConditionalOnProperty(
    prefix = "crewscope.team-activity-realtime",
    name = "enabled",
    havingValue = "true")
public class TeamActivityRealtimeConfiguration {

  @Bean
  TeamActivityCursorKeyRing teamActivityCursorKeyRing(
      TeamActivityRealtimeProperties properties) {
    return new TeamActivityCursorKeyRing(properties.getCurrentKeyId(), properties.getKeys());
  }

  @Bean
  TeamActivityCursorCodec teamActivityCursorCodec(
      TeamActivityCursorKeyRing keyRing, TeamActivityRealtimeProperties properties) {
    return new TeamActivityCursorCodec(
        keyRing,
        Clock.systemUTC(),
        properties.getCursorMaximumAge(),
        properties.getCursorFutureSkew());
  }

  @Bean
  TeamActivityRealtimeStream teamActivityRealtimeStream(
      TeamRealtimeEventStore store,
      TeamActivityCursorCodec cursorCodec,
      TeamActivityRealtimeProperties properties) {
    return new TeamActivityRealtimeStream(store, cursorCodec, properties);
  }
}
