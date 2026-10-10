package io.crewscope.server.collaboration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.crewscope.domain.shared.id.TeamId;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.crewscope.server.security.ApiSecurityResponseWriter;
import io.crewscope.server.security.AuthenticationSubjectExtractor;
import io.crewscope.server.security.SameOriginWebFilter;
import io.crewscope.server.security.session.BrowserSessionPrincipal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.http.client.HttpClient;

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
  static final UUID BOB_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

  /** Fixture organization and the team coordinates the fake authorizer reasons about. */
  static final UUID ORGANIZATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");
  static final UUID ALICE_TEAM_1 = UUID.fromString("00000000-0000-0000-0000-000000000012");
  static final UUID ALICE_TEAM_2 = UUID.fromString("00000000-0000-0000-0000-000000000013");
  static final UUID BOB_TEAM = UUID.fromString("00000000-0000-0000-0000-000000000014");

  private static final int MAX_LOGIN_BODY_BYTES = 8 * 1024;
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> FRAME_TYPE = new TypeReference<>() {};
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
  @Import({
    CollaborationWsTestSupport.SecurityFixture.class,
    CollaborationWsTestSupport.SubscriptionFixture.class,
    CollaborationWebSocketConfiguration.class
  })
  static class SpikeApplication {}

  /**
   * Fake subscription authorization (I01b evidence honesty): the real policy behaviors have
   * their own tests, and the cross-team / revocation attack sets belong to Q01's product-level
   * verification — what the protocol tests need is a deterministic principal-to-team table so
   * forbidden_scope, subscription_limit, and the presence lifecycle can be observed. It never
   * checks resource granularity: the fake answers team membership only. The table is mutable
   * so an A01 scenario can revoke a team mid-session and watch the revalidation layer react.
   */
  @Profile(FIXTURE_PROFILE)
  @Configuration(proxyBeanMethods = false)
  static class SubscriptionFixture {

    /** Deterministic principal-to-team table; mutable for revocation scenarios. */
    static final Map<UUID, Set<TeamId>> ALLOWED_TEAMS = new ConcurrentHashMap<>();

    static {
      resetTeams();
    }

    /** Restores the default membership table — every scenario starts from a clean slate. */
    static void resetTeams() {
      ALLOWED_TEAMS.clear();
      ALLOWED_TEAMS.put(
          ALICE_ACCOUNT_ID, Set.of(new TeamId(ALICE_TEAM_1), new TeamId(ALICE_TEAM_2)));
      ALLOWED_TEAMS.put(BOB_ACCOUNT_ID, Set.of(new TeamId(BOB_TEAM)));
    }

    /** Revokes one team from one account — the A01 revocation-scenario lever. */
    static void revokeTeam(UUID accountId, UUID teamId) {
      ALLOWED_TEAMS.computeIfPresent(
          accountId,
          (id, teams) -> {
            Set<TeamId> remaining = new HashSet<>(teams);
            remaining.remove(new TeamId(teamId));
            return Set.copyOf(remaining);
          });
    }

    @Bean
    CollaborationSubscriptionAuthorizer collaborationSubscriptionAuthorizer() {
      return (authentication, scope) ->
          Mono.fromSupplier(
              () -> {
                if (!(authentication.getPrincipal() instanceof BrowserSessionPrincipal session)) {
                  return CollaborationSubscriptionAuthorizer.Decision.DENIED;
                }
                if (!scope.organizationId().value().equals(ORGANIZATION_ID)) {
                  return CollaborationSubscriptionAuthorizer.Decision.DENIED;
                }
                return ALLOWED_TEAMS
                        .getOrDefault(session.accountId(), Set.of())
                        .contains(scope.teamId())
                    ? CollaborationSubscriptionAuthorizer.Decision.ALLOWED
                    : CollaborationSubscriptionAuthorizer.Decision.DENIED;
              });
    }

    /**
     * The production display-name resolver needs the JPA account snapshot reader this fixture
     * excludes; a stable per-account table serves the signal frames just as well.
     */
    @Bean
    CollaborationDisplayNameResolver collaborationDisplayNameResolver() {
      return authentication -> {
        if (!(authentication.getPrincipal() instanceof BrowserSessionPrincipal session)) {
          return Mono.just("");
        }
        return Mono.just(
            session.accountId().equals(ALICE_ACCOUNT_ID) ? "Alice" : "Bob");
      };
    }
  }

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

  /** One real reactor-netty WebSocket client connection with frame and close-code accessors. */
  static final class WsConnection implements AutoCloseable {

    private final LinkedBlockingQueue<String> frames = new LinkedBlockingQueue<>();
    private final Sinks.Many<String> toSend = Sinks.many().unicast().onBackpressureBuffer();
    private final CompletableFuture<Integer> closeCode = new CompletableFuture<>();
    private final Disposable connection;

    /**
     * The handle lambda captures this before the constructor finishes, but only the three
     * already-initialized sinks above — the connection disposable itself is never touched
     * inside it, so the escape is safe.
     */
    private WsConnection(int port, String sessionCookieValue) {
      this.connection =
          HttpClient.create()
              .headers(
                  headers ->
                      headers.set(
                          "Cookie",
                          CollaborationWsTestSupport.SESSION_COOKIE + "=" + sessionCookieValue))
              .websocket()
              .uri("ws://127.0.0.1:" + port + CollaborationWsTestSupport.WS_PATH)
              .handle(
                  (inbound, outbound) -> {
                    Mono<Void> receiving =
                        inbound
                            .receiveFrames()
                            .doOnNext(
                                frame -> {
                                  if (frame instanceof TextWebSocketFrame text) {
                                    frames.add(text.text());
                                  }
                                })
                            .then();
                    Mono<Void> closing =
                        inbound
                            .receiveCloseStatus()
                            .doOnNext(status -> closeCode.complete(status.code()))
                            .then()
                            .onErrorResume(
                                error -> {
                                  closeCode.complete(-1);
                                  return Mono.empty();
                                });
                    Mono<Void> sending =
                        outbound
                            .sendString(toSend.asFlux(), StandardCharsets.UTF_8)
                            .then();
                    return sending.and(receiving).and(closing);
                  })
              .subscribe();
    }

    static WsConnection open(int port, String sessionCookieValue) {
      return new WsConnection(port, sessionCookieValue);
    }

    Map<String, Object> nextFrameJson(Duration timeout) throws Exception {
      return JSON.readValue(nextFrame(timeout), FRAME_TYPE);
    }

    String nextFrame(Duration timeout) {
      String frame;
      try {
        frame = frames.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while waiting for a frame");
      }
      if (frame == null) {
        throw new IllegalStateException("no frame arrived within " + timeout);
      }
      return frame;
    }

    void sendText(String frame) {
      toSend.tryEmitNext(frame);
    }

    int awaitCloseCode(Duration timeout) throws Exception {
      try {
        return closeCode.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (TimeoutException timeoutException) {
        throw new IllegalStateException("connection not closed within " + timeout);
      }
    }

    @Override
    public void close() {
      connection.dispose();
    }
  }
}
