package io.crewscope.server.security.oidc;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;

/** Browser-facing SSO entry wiring; the OIDC protocol flow joins this package in M12-I01 C2. */
@Configuration(proxyBeanMethods = false)
public class OidcLoginConfiguration {

  @Bean
  OidcLoginEntry oidcLoginEntry(
      ObjectProvider<ReactiveClientRegistrationRepository> repositories) {
    return new OidcLoginEntry(repositories);
  }
}
