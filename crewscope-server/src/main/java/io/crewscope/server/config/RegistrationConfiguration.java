package io.crewscope.server.config;

import io.crewscope.domain.shared.id.OrganizationId;
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
    return () -> {
      Objects.requireNonNull(properties.getMode(), "registration mode");
      String organizationId =
          Objects.requireNonNull(properties.getOrganizationId(), "registration organization id");
      if (!organizationId.isBlank()) {
        OrganizationId.from(organizationId.strip());
      }
    };
  }
}
