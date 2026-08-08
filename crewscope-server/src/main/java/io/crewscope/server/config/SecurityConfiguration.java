package io.crewscope.server.config;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.server.security.AuthenticationSubjectExtractor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfiguration {

  @Bean
  AuthenticationSubjectExtractor authenticationSubjectExtractor(
      @Value("${crewscope.security.oidc.organization-id:}") String oidcOrganizationId) {
    return new AuthenticationSubjectExtractor(oidcOrganizationId);
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  @Bean
  MapReactiveUserDetailsService bootstrapUsers(
      PasswordEncoder passwordEncoder,
      @Value("${crewscope.security.bootstrap.username}") String username,
      @Value("${crewscope.security.bootstrap.password}") String password) {
    UserDetails administrator =
        User.withUsername(username)
            .password(passwordEncoder.encode(password))
            .roles("ADMIN")
            .build();
    return new MapReactiveUserDetailsService(administrator);
  }

  @Bean
  ReactiveAuthenticationManager authenticationManager(
      MapReactiveUserDetailsService users, PasswordEncoder passwordEncoder) {
    UserDetailsRepositoryReactiveAuthenticationManager manager =
        new UserDetailsRepositoryReactiveAuthenticationManager(users);
    manager.setPasswordEncoder(passwordEncoder);
    return manager;
  }

  @Bean
  SecurityWebFilterChain securityWebFilterChain(
      ServerHttpSecurity http,
      ReactiveAuthenticationManager authenticationManager,
      @Value("${crewscope.security.mode:bootstrap}") String configuredMode,
      @Value("${crewscope.security.oidc.organization-id:}") String oidcOrganizationId) {
    SecurityMode mode = SecurityMode.from(configuredMode);
    http.authorizeExchange(
            exchange ->
                exchange
                    .pathMatchers("/actuator/health", "/actuator/info", "/api/v1/system/info")
                    .permitAll()
                    .anyExchange()
                    .authenticated())
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable);
    if (mode == SecurityMode.BOOTSTRAP) {
      // Bootstrap is an operator-controlled API profile and does not use browser cookies.
      http.authenticationManager(authenticationManager)
          .csrf(ServerHttpSecurity.CsrfSpec::disable)
          .httpBasic(Customizer.withDefaults());
    } else {
      requireOidcOrganizationId(oidcOrganizationId);
      // OIDC uses a browser session, so state-changing requests retain CSRF protection.
      http.csrf(
              csrf -> csrf.csrfTokenRepository(CookieServerCsrfTokenRepository.withHttpOnlyFalse()))
          .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
          .oauth2Login(Customizer.withDefaults());
    }
    return http.build();
  }

  private static OrganizationId requireOidcOrganizationId(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(
          "crewscope.security.oidc.organization-id is required in oidc mode");
    }
    return OrganizationId.from(value.strip());
  }
}
