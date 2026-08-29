package io.crewscope.server.config;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.server.security.AuthenticationSubjectExtractor;
import io.crewscope.server.security.ApiSecurityResponseWriter;
import io.crewscope.server.security.AuthenticationRequestBodyLimitWebFilter;
import io.crewscope.server.security.SameOriginWebFilter;
import io.crewscope.server.security.TaskTokenWebFilter;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
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
import org.springframework.security.web.server.header.CrossOriginOpenerPolicyServerHttpHeadersWriter.CrossOriginOpenerPolicy;
import org.springframework.security.web.server.header.CrossOriginResourcePolicyServerHttpHeadersWriter.CrossOriginResourcePolicy;
import org.springframework.security.web.server.header.ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler;
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
    ApiSecurityResponseWriter securityResponses = new ApiSecurityResponseWriter();
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
                    // The Web build owns these SPA entries; permitting them here prevents a
                    // future co-located static deployment from being intercepted by Security.
                    .pathMatchers(
                        HttpMethod.GET,
                        "/",
                        "/index.html",
                        "/login",
                        "/register",
                        "/invite",
                        "/favicon.ico",
                        "/robots.txt",
                        "/manifest.webmanifest",
                        "/assets/**")
                    .permitAll()
                    .pathMatchers(HttpMethod.POST, "/api/v1/auth/register")
                    .permitAll()
                    .pathMatchers(HttpMethod.POST, "/api/v1/auth/login")
                    .permitAll()
                    .pathMatchers(HttpMethod.GET, "/api/v1/auth/session")
                    .permitAll()
                    .pathMatchers(HttpMethod.POST, "/api/v1/invitations/preview")
                    .permitAll()
                    .pathMatchers("/api/internal/v1/worker/**")
                    .hasAuthority("TASK_RUNTIME")
                    .anyExchange()
                    .authenticated())
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        // CrewScope intentionally does not support credentialed cross-origin browser APIs.
        .cors(ServerHttpSecurity.CorsSpec::disable)
        .exceptionHandling(exceptions -> exceptions
            .authenticationEntryPoint((exchange, failure) ->
                securityResponses.authenticationRequired(exchange))
            .accessDeniedHandler((exchange, failure) ->
                securityResponses.accessDenied(exchange)))
        .headers(headers -> headers
            .contentSecurityPolicy(csp -> csp.policyDirectives(
                "default-src 'self'; base-uri 'self'; frame-ancestors 'none'; "
                    + "form-action 'self'; object-src 'none'"))
            .permissionsPolicy(policy ->
                policy.policy("camera=(), microphone=(), geolocation=()"))
            .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.SAME_ORIGIN))
            .crossOriginOpenerPolicy(opener -> opener.policy(CrossOriginOpenerPolicy.SAME_ORIGIN))
            .crossOriginResourcePolicy(resource ->
                resource.policy(CrossOriginResourcePolicy.SAME_ORIGIN))
            .hsts(hsts -> hsts
                .maxAge(Duration.ofDays(365))
                .includeSubdomains(true)
                .preload(false)));
    http.addFilterBefore(
        new SameOriginWebFilter(securityResponses), SecurityWebFiltersOrder.CSRF);
    http.addFilterAfter(
        new AuthenticationRequestBodyLimitWebFilter(securityResponses),
        SecurityWebFiltersOrder.CSRF);
    taskTokenFilter.ifAvailable(filter ->
        http.addFilterAt(filter, SecurityWebFiltersOrder.AUTHENTICATION));
    if (mode == SecurityMode.BOOTSTRAP) {
      // Bootstrap is an operator-controlled API profile and does not use browser cookies.
      http.authenticationManager(authenticationManager)
          .csrf(ServerHttpSecurity.CsrfSpec::disable)
          .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
          .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
          .httpBasic(basic -> basic.authenticationEntryPoint((exchange, failure) ->
              securityResponses.authenticationRequired(exchange)));
    } else if (mode == SecurityMode.LOCAL) {
      WebSessionServerSecurityContextRepository browserSecurityContextRepository =
          requiredBrowserSecurityContexts(browserSecurityContexts, "local");
      CookieServerCsrfTokenRepository csrfRepository =
          CookieServerCsrfTokenRepository.withHttpOnlyFalse();
      csrfRepository.setCookiePath("/");
      http.csrf(csrf -> csrf
              .csrfTokenRepository(csrfRepository)
              // The Vue client echoes the raw cookie token in X-XSRF-TOKEN.
              .csrfTokenRequestHandler(new ServerCsrfTokenRequestAttributeHandler())
              .accessDeniedHandler((exchange, failure) ->
                  securityResponses.csrfRejected(exchange)))
          .securityContextRepository(browserSecurityContextRepository)
          .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
          .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable);
    } else {
      requireOidcOrganizationId(oidcOrganizationId);
      WebSessionServerSecurityContextRepository browserSecurityContextRepository =
          requiredBrowserSecurityContexts(browserSecurityContexts, "oidc");
      CookieServerCsrfTokenRepository csrfRepository =
          CookieServerCsrfTokenRepository.withHttpOnlyFalse();
      csrfRepository.setCookiePath("/");
      // OIDC uses a browser session, so state-changing requests retain CSRF protection.
      http.csrf(
              csrf -> csrf
                  .csrfTokenRepository(csrfRepository)
                  .csrfTokenRequestHandler(new ServerCsrfTokenRequestAttributeHandler())
                  .accessDeniedHandler((exchange, failure) ->
                      securityResponses.csrfRejected(exchange)))
          .securityContextRepository(browserSecurityContextRepository)
          .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
          .oauth2Login(Customizer.withDefaults());
    }
    return http.build();
  }

  private static WebSessionServerSecurityContextRepository requiredBrowserSecurityContexts(
      ObjectProvider<WebSessionServerSecurityContextRepository> contexts, String mode) {
    return contexts.getIfAvailable(() -> {
      throw new IllegalStateException(
          "browser Session SecurityContext repository is required in " + mode + " mode");
    });
  }

  private static OrganizationId requireOidcOrganizationId(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(
          "crewscope.security.oidc.organization-id is required in oidc mode");
    }
    return OrganizationId.from(value.strip());
  }
}
