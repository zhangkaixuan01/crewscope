package io.crewscope.server.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.crewscope.server.security.ApiSecurityResponseWriter;
import io.crewscope.server.security.AuthenticationSubjectExtractor;
import io.crewscope.server.security.SameOriginWebFilter;
import io.crewscope.server.security.session.BrowserSessionPrincipal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.session.data.redis.config.annotation.web.server.EnableRedisIndexedWebSession;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Minimal bootstrap context for the collaboration WebSocket tests: a real Redis-backed browser
 * session whose login produces the production {@link BrowserSessionPrincipal}, the production
 * {@link SameOriginWebFilter} on /api/, and the product {@link CollaborationWebSocketConfiguration}
 * (active only when the test sets crewscope.collaboration-realtime.enabled). Authentication
 * otherwise mirrors LocalSessionSecurityM7S01IntegrationTest; CSRF stays disabled because none of
 * these scenarios exercises a state-changing POST.
 */
final class CollaborationWsTestSupport {

  /**
   * Profile gating the fixture configurations below. They live inside the production component
   * scan (io.crewscope.server.**), so a full-context test such as WorkQueryScaleHttpIntegrationTest
   * would otherwise pick them up off the test classpath and collide with the production
   * SecurityConfiguration beans. The profile keeps them invisible to every context that does not
   * explicitly activate it.
   */
  static final String FIXTURE_PROFILE = "m11-i01a";

  static final String WS_PATH = "/api/v1/collaboration/ws";
  static final String SESSION_COOKIE = "CREWSCOPE_SESSION";
  static final UUID ALICE_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

  private static final int MAX_LOGIN_BODY_BYTES = 8 * 1024;
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Map<String, String> PASSWORDS =
      Map.of("alice", "alice-password", "bob", "bob-password");
  private static final Map<String, UUID> ACCOUNTS =
      Map.of(
          "alice", ALICE_ACCOUNT_ID,
          "bob", UUID.fromString("00000000-0000-0000-0000-0000000000b2"));

  private CollaborationWsTestSupport() {}

  @Profile(FIXTURE_PROFILE)
  @SpringBootConfiguration(proxyBeanMethods = false)
  @EnableAutoConfiguration(
      exclude = {
        org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
        org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration.class,
        org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration.class,
        org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration.class
      })
  @Import({CollaborationWsTestSupport.SecurityFixture.class, CollaborationWebSocketConfiguration.class})
  static class SpikeApplication {}

  @Profile(FIXTURE_PROFILE)
  @Configuration(proxyBeanMethods = false)
  @EnableWebFluxSecurity
  @EnableRedisIndexedWebSession(
      maxInactiveIntervalInSeconds = 900,
      redisNamespace = "crewscope:m11-i01a")
  static class SecurityFixture {

    @Bean
    WebSessionServerSecurityContextRepository securityContextRepository() {
      return new WebSessionServerSecurityContextRepository();
    }

    @Bean
    AuthenticationSubjectExtractor authenticationSubjectExtractor() {
      return new AuthenticationSubjectExtractor((String) null);
    }

    @Bean
    ReactiveAuthenticationManager authenticationManager() {
      return authentication -> {
        String expected = PASSWORDS.get(authentication.getName());
        if (expected == null || !expected.equals(authentication.getCredentials())) {
          return Mono.error(new BadCredentialsException("invalid credentials"));
        }
        BrowserSessionPrincipal principal =
            new BrowserSessionPrincipal(ACCOUNTS.get(authentication.getName()), 1L);
        return Mono.just(
            UsernamePasswordAuthenticationToken.authenticated(
                principal, "[PROTECTED]", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
      };
    }

    @Bean
    AuthenticationWebFilter jsonLoginFilter(
        ReactiveAuthenticationManager authenticationManager,
        WebSessionServerSecurityContextRepository securityContextRepository) {
      AuthenticationWebFilter filter = new AuthenticationWebFilter(authenticationManager);
      filter.setRequiresAuthenticationMatcher(
          new PathPatternParserServerWebExchangeMatcher("/api/v1/auth/login", HttpMethod.POST));
      filter.setSecurityContextRepository(securityContextRepository);
      filter.setServerAuthenticationConverter(
          exchange ->
              DataBufferUtils.join(exchange.getRequest().getBody(), MAX_LOGIN_BODY_BYTES)
                  .flatMap(buffer -> parseLoginRequest(buffer))
                  .onErrorMap(
                      error ->
                          error instanceof BadCredentialsException
                              ? error
                              : new BadCredentialsException("invalid credentials", error)));
      filter.setAuthenticationSuccessHandler(
          (filterExchange, authentication) ->
              filterExchange
                  .getExchange()
                  .getSession()
                  .flatMap(session -> session.changeSessionId())
                  .then(
                      writeJson(
                          filterExchange.getExchange(),
                          HttpStatus.OK,
                          Map.of("authenticated", true))));
      filter.setAuthenticationFailureHandler(
          (filterExchange, failure) ->
              writeJson(
                  filterExchange.getExchange(),
                  HttpStatus.UNAUTHORIZED,
                  Map.of("code", "invalid_credentials")));
      return filter;
    }

    @Bean
    SecurityWebFilterChain collaborationSecurityWebFilterChain(
        ServerHttpSecurity http,
        AuthenticationWebFilter jsonLoginFilter,
        WebSessionServerSecurityContextRepository securityContextRepository) {
      return http
          .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
          .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
          .csrf(ServerHttpSecurity.CsrfSpec::disable)
          .securityContextRepository(securityContextRepository)
          .addFilterBefore(
              new SameOriginWebFilter(new ApiSecurityResponseWriter()),
              SecurityWebFiltersOrder.CSRF)
          .addFilterAt(jsonLoginFilter, SecurityWebFiltersOrder.AUTHENTICATION)
          .authorizeExchange(
              spec ->
                  spec.pathMatchers("/api/v1/auth/login").permitAll().anyExchange().authenticated())
          .exceptionHandling(
              spec ->
                  spec.authenticationEntryPoint(
                      (exchange, denied) ->
                          writeJson(
                              exchange,
                              HttpStatus.UNAUTHORIZED,
                              Map.of("code", "authentication_required"))))
          .build();
    }

    private static Mono<Authentication> parseLoginRequest(DataBuffer buffer) {
      try {
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        LoginRequest request = JSON.readValue(bytes, LoginRequest.class);
        return Mono.just(
            UsernamePasswordAuthenticationToken.unauthenticated(
                request.username(), request.password()));
      } catch (Exception exception) {
        return Mono.error(new BadCredentialsException("invalid credentials", exception));
      } finally {
        DataBufferUtils.release(buffer);
      }
    }

    private static Mono<Void> writeJson(
        ServerWebExchange exchange, HttpStatus status, Map<String, ?> body) {
      try {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return exchange
            .getResponse()
            .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
      } catch (Exception exception) {
        return Mono.error(exception);
      }
    }
  }

  record LoginRequest(String username, String password) {}
}
