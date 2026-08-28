package io.crewscope.server.config;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.server.security.AuthenticationSubjectExtractor;
import io.crewscope.server.security.TaskTokenWebFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfiguration {

  @Bean
  AuthenticationSubjectExtractor authenticationSubjectExtractor(
      @Value("${crewscope.security.oidc.organization-id:}") String oidcOrganizationId) {
    return new AuthenticationSubjectExtractor(oidcOrganizationId);
  }

  @Bean("bootstrapPasswordEncoder")
  @Primary
  PasswordEncoder bootstrapPasswordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  @Bean
  @Primary
  MapReactiveUserDetailsService bootstrapUsers(
      @Qualifier("bootstrapPasswordEncoder") PasswordEncoder passwordEncoder,
      @Value("${crewscope.security.bootstrap.username}") String username,
      @Value("${crewscope.security.bootstrap.password}") String password) {
    UserDetails administrator =
        User.withUsername(username)
            .password(passwordEncoder.encode(password))
            .roles("ADMIN")
            .build();
    return new MapReactiveUserDetailsService(administrator);
  }

  @Bean("bootstrapAuthenticationManager")
  @Primary
  ReactiveAuthenticationManager bootstrapAuthenticationManager(
      @Qualifier("bootstrapUsers") MapReactiveUserDetailsService users,
      @Qualifier("bootstrapPasswordEncoder") PasswordEncoder passwordEncoder) {
    UserDetailsRepositoryReactiveAuthenticationManager manager =
        new UserDetailsRepositoryReactiveAuthenticationManager(users);
    manager.setPasswordEncoder(passwordEncoder);
    return manager;
  }

  @Bean("monitoringPasswordEncoder")
  PasswordEncoder monitoringPasswordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  @Bean("monitoringUsers")
  MapReactiveUserDetailsService monitoringUsers(
      @Qualifier("monitoringPasswordEncoder") PasswordEncoder passwordEncoder,
      @Value("${crewscope.security.monitoring.username:crewscope-prometheus}") String username,
      @Value("${crewscope.security.monitoring.password:crewscope-monitoring}") String password) {
    UserDetails monitoring =
        User.withUsername(username)
            .password(passwordEncoder.encode(password))
            .roles("MONITORING")
            .build();
    return new MapReactiveUserDetailsService(monitoring);
  }

  @Bean("monitoringAuthenticationManager")
  ReactiveAuthenticationManager monitoringAuthenticationManager(
      @Qualifier("monitoringUsers") MapReactiveUserDetailsService users,
      @Qualifier("monitoringPasswordEncoder") PasswordEncoder passwordEncoder) {
    UserDetailsRepositoryReactiveAuthenticationManager manager =
        new UserDetailsRepositoryReactiveAuthenticationManager(users);
    manager.setPasswordEncoder(passwordEncoder);
    return manager;
  }

  /** Isolates the machine scrape credential from every browser and business API route. */
  @Bean("monitoringSecurityWebFilterChain")
  @Order(0)
  SecurityWebFilterChain monitoringSecurityWebFilterChain(
      ServerHttpSecurity http,
      @Qualifier("monitoringAuthenticationManager")
          ReactiveAuthenticationManager authenticationManager) {
    return http.securityMatcher(ServerWebExchangeMatchers.pathMatchers("/actuator/prometheus"))
        .authorizeExchange(exchange -> exchange.anyExchange().hasRole("MONITORING"))
        .authenticationManager(authenticationManager)
        .csrf(ServerHttpSecurity.CsrfSpec::disable)
        .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
        .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .httpBasic(Customizer.withDefaults())
        .build();
  }

  @Bean("applicationSecurityWebFilterChain")
  @Order(1)
  SecurityWebFilterChain applicationSecurityWebFilterChain(
      ServerHttpSecurity http,
      @Qualifier("bootstrapAuthenticationManager")
          ReactiveAuthenticationManager authenticationManager,
      ObjectProvider<TaskTokenWebFilter> taskTokenFilter,
      ObjectProvider<WebSessionServerSecurityContextRepository> browserSecurityContexts,
      @Value("${crewscope.security.mode:bootstrap}") String configuredMode,
      @Value("${crewscope.security.oidc.organization-id:}") String oidcOrganizationId) {
    SecurityMode mode = SecurityMode.from(configuredMode);
    http.authorizeExchange(
            exchange ->
                exchange
                    // Kubernetes/Compose probes expose only the health status; component details
                    // remain suppressed by Actuator configuration.
                    .pathMatchers(
                        "/actuator/health",
                        "/actuator/health/liveness",
                        "/actuator/health/readiness",
                        "/actuator/info",
                        "/api/v1/system/info")
                    .permitAll()
                    .pathMatchers("/api/internal/v1/worker/**")
                    .hasAuthority("TASK_RUNTIME")
                    .anyExchange()
                    .authenticated())
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable);
    taskTokenFilter.ifAvailable(filter ->
        http.addFilterAt(filter, SecurityWebFiltersOrder.AUTHENTICATION));
    if (mode == SecurityMode.BOOTSTRAP) {
      // Bootstrap is an operator-controlled API profile and does not use browser cookies.
      http.authenticationManager(authenticationManager)
          .csrf(ServerHttpSecurity.CsrfSpec::disable)
          .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
          .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
          .httpBasic(basic -> basic.authenticationEntryPoint((exchange, failure) -> {
            // Explicit Basic credentials remain migration-compatible, but ordinary Web clients
            // must never receive a browser-triggering Basic challenge.
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
          }));
    } else {
      requireOidcOrganizationId(oidcOrganizationId);
      WebSessionServerSecurityContextRepository browserSecurityContextRepository =
          browserSecurityContexts.getIfAvailable(() -> {
            throw new IllegalStateException(
                "browser Session SecurityContext repository is required in oidc mode");
          });
      // OIDC uses a browser session, so state-changing requests retain CSRF protection.
      http.csrf(
              csrf -> csrf.csrfTokenRepository(CookieServerCsrfTokenRepository.withHttpOnlyFalse()))
          .securityContextRepository(browserSecurityContextRepository)
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
