package io.crewscope.server.config;

import java.util.Objects;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Binds and validates the closed local registration policy before authentication APIs start. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RegistrationProperties.class)
public class RegistrationConfiguration {

  @Bean
  InitializingBean registrationModeGuard(RegistrationProperties properties) {
    return () -> Objects.requireNonNull(properties.getMode(), "registration mode");
  }
}
