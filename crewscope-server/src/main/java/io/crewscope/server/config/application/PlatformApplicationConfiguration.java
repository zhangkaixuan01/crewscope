package io.crewscope.server.config.application;

import io.crewscope.domain.shared.time.TimeProvider;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provides shared application dependencies that are owned by the Spring Boot composition root. */
@Configuration(proxyBeanMethods = false)
public class PlatformApplicationConfiguration {

  /** Uses UTC as the single production clock for domain and application services. */
  @Bean
  TimeProvider crewscopeTimeProvider() {
    return TimeProvider.from(Clock.systemUTC());
  }
}
