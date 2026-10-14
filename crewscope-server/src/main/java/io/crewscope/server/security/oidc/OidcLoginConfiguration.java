package io.crewscope.server.security.oidc;

import io.crewscope.application.identity.LoginDefense;
import io.crewscope.application.identity.OidcAccountAuthenticationService;
import io.crewscope.server.config.BrowserOrganizationAnchor;
import io.crewscope.server.config.RegistrationProperties;
import io.crewscope.server.security.ApiSecurityResponseWriter;
import io.crewscope.server.security.login.ControlledNetworkSourceResolver;
import io.crewscope.server.security.session.BrowserSessionLifecycle;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;

/**
 * Browser-facing SSO wiring. The entry projection is unconditional because every security mode
 * serves GET /auth/session; the protocol-flow beans (PKCE, the defense start gate and the
 * Session-establishing handlers) assemble only when {@code crewscope.security.mode=oidc}.
 * SecurityConfiguration falls back to framework defaults when they are absent, which only its
 * own unit tests rely on — a production oidc deployment missing this wiring fails fast.
 */
@Configuration(proxyBeanMethods = false)
public class OidcLoginConfiguration {

  @Bean
  OidcLoginEntry oidcLoginEntry(
      ObjectProvider<ReactiveClientRegistrationRepository> repositories) {
    return new OidcLoginEntry(repositories);
  }

  @Bean
  @ConditionalOnProperty(name = "crewscope.security.mode", havingValue = "oidc")
  OidcPkceAuthorizationRequestResolver oidcPkceAuthorizationRequestResolver(
      ReactiveClientRegistrationRepository clientRegistrations) {
    return new OidcPkceAuthorizationRequestResolver(clientRegistrations);
  }

  @Bean
  @ConditionalOnProperty(name = "crewscope.security.mode", havingValue = "oidc")
  OidcAuthenticationSuccessHandler oidcAuthenticationSuccessHandler(
      OidcAccountAuthenticationService authentication,
      BrowserSessionLifecycle sessions,
      LoginDefense defense,
      RegistrationProperties registration,
      BrowserOrganizationAnchor organizationAnchor) {
    return new OidcAuthenticationSuccessHandler(
        authentication, sessions, defense, registration, organizationAnchor);
  }

  @Bean
  @ConditionalOnProperty(name = "crewscope.security.mode", havingValue = "oidc")
  OidcAuthenticationFailureHandler oidcAuthenticationFailureHandler() {
    return new OidcAuthenticationFailureHandler();
  }

  @Bean
  @ConditionalOnProperty(name = "crewscope.security.mode", havingValue = "oidc")
  // A plain WebFilter ordered ahead of the security chain proxy — the framework's redirect
  // filter short-circuits the start path inside the chain, which would skip the defense gate.
  OidcAuthorizationStartDefenseWebFilter oidcAuthorizationStartDefenseWebFilter(
      LoginDefense defense, ControlledNetworkSourceResolver networkSources) {
    return new OidcAuthorizationStartDefenseWebFilter(
        defense, networkSources, new ApiSecurityResponseWriter());
  }
}
