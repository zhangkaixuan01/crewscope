package io.crewscope.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
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
import org.springframework.security.web.server.authentication.ServerAuthenticationFailureHandler;
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.authentication.logout.WebSessionServerLogoutHandler;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;

/**
 * Proves the M7 browser-authentication topology without introducing the production account model.
 * The fixture deliberately uses in-memory users while Session and SecurityContext are real Redis
 * state; M7-I02 and M7-A02 replace only the fixture authentication source and public projections.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@Execution(ExecutionMode.SAME_THREAD)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = LocalSessionSecurityM7S01IntegrationTest.SpikeApplication.class,
        properties = {
            "spring.main.web-application-type=reactive",
            "spring.session.timeout=15m",
            "spring.session.data.redis.namespace=crewscope:m7-s01",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                    + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                    + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
                    + "org.springframework.boot.session.data.redis.autoconfigure."
                    + "SessionDataRedisAutoConfiguration"
        })
class LocalSessionSecurityM7S01IntegrationTest {

    private static final int REDIS_PORT = 6379;
    private static final String SESSION_COOKIE = "CREWSCOPE_SESSION";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final int MAX_LOGIN_BODY_BYTES = 8 * 1024;

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(REDIS_PORT)
                    .withCommand("redis-server", "--save", "", "--appendonly", "no")
                    .waitingFor(Wait.forListeningPort())
                    .withStartupTimeout(Duration.ofMinutes(2));

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.data.redis.url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(REDIS_PORT));
    }

    private final WebTestClient client;

    @Autowired
    LocalSessionSecurityM7S01IntegrationTest(@LocalServerPort int port) {
        this.client = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .build();
    }

    @Test
    void persistsRotatedSessionsAndIsolatesTwoBrowserUsers() throws Exception {
        CookieJar alice = new CookieJar();
        CookieJar bob = new CookieJar();

        EntityExchangeResult<byte[]> anonymousAlice = session(alice, null, HttpStatus.OK);
        String aliceSessionBeforeLogin = alice.required(SESSION_COOKIE);
        assertThat(alice.required(CSRF_COOKIE)).isNotBlank();
        assertThat(body(anonymousAlice)).containsEntry("authenticated", false);

        session(bob, null, HttpStatus.OK);
        String bobSessionBeforeLogin = bob.required(SESSION_COOKIE);
        assertThat(bobSessionBeforeLogin).isNotEqualTo(aliceSessionBeforeLogin);

        login(alice, "alice", "alice-password", HttpStatus.OK);
        String aliceSessionAfterLogin = alice.required(SESSION_COOKIE);
        assertThat(aliceSessionAfterLogin).isNotEqualTo(aliceSessionBeforeLogin);

        login(bob, "bob", "bob-password", HttpStatus.OK);
        String bobSessionAfterLogin = bob.required(SESSION_COOKIE);
        assertThat(bobSessionAfterLogin)
                .isNotEqualTo(bobSessionBeforeLogin)
                .isNotEqualTo(aliceSessionAfterLogin);

        assertThat(body(session(alice, null, HttpStatus.OK)))
                .containsEntry("authenticated", true)
                .containsEntry("username", "alice");
        assertThat(body(session(bob, null, HttpStatus.OK)))
                .containsEntry("authenticated", true)
                .containsEntry("username", "bob");

        CookieJar stolenPreLoginSession = new CookieJar();
        stolenPreLoginSession.put(SESSION_COOKIE, aliceSessionBeforeLogin);
        assertThat(body(session(stolenPreLoginSession, null, HttpStatus.OK)))
                .containsEntry("authenticated", false);

        write(alice, null, HttpStatus.FORBIDDEN);
        write(alice, "incorrect-token", HttpStatus.FORBIDDEN);
        assertThat(body(write(alice, alice.required(CSRF_COOKIE), HttpStatus.OK)))
                .containsEntry("actor", "alice");

        assertRedisContainsLiveSession(aliceSessionAfterLogin);
        assertRedisContainsLiveSession(bobSessionAfterLogin);

        logout(alice, alice.required(CSRF_COOKIE), HttpStatus.NO_CONTENT);
        assertThat(body(session(alice, null, HttpStatus.OK)))
                .containsEntry("authenticated", false);
        assertThat(body(session(bob, null, HttpStatus.OK)))
                .containsEntry("authenticated", true)
                .containsEntry("username", "bob");

        CookieJar failedLogin = new CookieJar();
        session(failedLogin, null, HttpStatus.OK);
        String failedSessionBeforeLogin = failedLogin.required(SESSION_COOKIE);
        EntityExchangeResult<byte[]> failure =
                login(failedLogin, "alice", "wrong-password", HttpStatus.UNAUTHORIZED);
        assertThat(failure.getResponseHeaders().getFirst("WWW-Authenticate")).isNull();
        assertThat(body(failure)).containsEntry("code", "invalid_credentials");
        assertThat(failedLogin.required(SESSION_COOKIE)).isEqualTo(failedSessionBeforeLogin);

        EntityExchangeResult<byte[]> oversized =
                login(failedLogin, "a".repeat(MAX_LOGIN_BODY_BYTES), "x", HttpStatus.UNAUTHORIZED);
        assertThat(oversized.getResponseHeaders().getFirst("WWW-Authenticate")).isNull();
        assertThat(body(oversized)).containsEntry("code", "invalid_credentials");
        assertThat(failedLogin.required(SESSION_COOKIE)).isEqualTo(failedSessionBeforeLogin);
    }

    private EntityExchangeResult<byte[]> session(
            CookieJar jar, String explicitSessionId, HttpStatus expectedStatus) {
        WebTestClient.RequestHeadersSpec<?> request = client.get().uri("/api/v1/auth/session");
        jar.apply(request);
        if (explicitSessionId != null) {
            request.cookie(SESSION_COOKIE, explicitSessionId);
        }
        EntityExchangeResult<byte[]> result = request.exchange()
                .expectStatus().isEqualTo(expectedStatus)
                .expectBody()
                .returnResult();
        jar.capture(result);
        return result;
    }

    private EntityExchangeResult<byte[]> login(
            CookieJar jar, String username, String password, HttpStatus expectedStatus) {
        WebTestClient.RequestBodySpec request = client.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header(CSRF_HEADER, jar.required(CSRF_COOKIE));
        jar.apply(request);
        EntityExchangeResult<byte[]> result = request.bodyValue(Map.of(
                        "username", username,
                        "password", password))
                .exchange()
                .expectStatus().isEqualTo(expectedStatus)
                .expectBody()
                .returnResult();
        jar.capture(result);
        return result;
    }

    private EntityExchangeResult<byte[]> write(
            CookieJar jar, String csrfToken, HttpStatus expectedStatus) {
        WebTestClient.RequestBodySpec request = client.post()
                .uri("/api/v1/spike/write")
                .contentType(MediaType.APPLICATION_JSON);
        if (csrfToken != null) {
            request.header(CSRF_HEADER, csrfToken);
        }
        jar.apply(request);
        EntityExchangeResult<byte[]> result = request.bodyValue(Map.of("value", "verified"))
                .exchange()
                .expectStatus().isEqualTo(expectedStatus)
                .expectBody()
                .returnResult();
        jar.capture(result);
        return result;
    }

    private EntityExchangeResult<byte[]> logout(
            CookieJar jar, String csrfToken, HttpStatus expectedStatus) {
        WebTestClient.RequestHeadersSpec<?> request = client.post()
                .uri("/api/v1/auth/logout")
                .header(CSRF_HEADER, csrfToken);
        jar.apply(request);
        EntityExchangeResult<byte[]> result = request.exchange()
                .expectStatus().isEqualTo(expectedStatus)
                .expectBody()
                .returnResult();
        jar.capture(result);
        return result;
    }

    private void assertRedisContainsLiveSession(String browserSessionId) throws Exception {
        String scan = REDIS.execInContainer(
                        "redis-cli", "--raw", "--scan", "--pattern", "crewscope:m7-s01:sessions:*")
                .getStdout();
        List<String> keys = scan.lines().filter(StringUtils::hasText).toList();
        assertThat(keys).isNotEmpty();

        boolean sessionFound = false;
        for (String key : keys) {
            String storedSessionId = key.substring(key.lastIndexOf(':') + 1);
            if (!storedSessionId.equals(browserSessionId)) {
                continue;
            }
            sessionFound = true;
            long ttl = Long.parseLong(REDIS.execInContainer("redis-cli", "--raw", "ttl", key)
                    .getStdout()
                    .trim());
            // Spring Session may add a five-minute cleanup grace period to the 15-minute session.
            assertThat(ttl).isPositive().isLessThanOrEqualTo(20 * 60L);
        }
        assertThat(sessionFound).as("Redis contains the browser's current Session ID").isTrue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(EntityExchangeResult<byte[]> result) throws Exception {
        byte[] bytes = result.getResponseBody();
        assertThat(bytes).isNotNull();
        return new ObjectMapper().readValue(bytes, Map.class);
    }

    private static final class CookieJar {

        private final Map<String, String> values = new LinkedHashMap<>();

        void apply(WebTestClient.RequestHeadersSpec<?> request) {
            values.forEach(request::cookie);
        }

        void capture(EntityExchangeResult<?> result) {
            result.getResponseCookies().forEach((name, cookies) -> {
                ResponseCookie cookie = cookies.get(cookies.size() - 1);
                if (cookie.getMaxAge().isZero()) {
                    values.remove(name);
                } else {
                    values.put(name, cookie.getValue());
                }
            });
        }

        void put(String name, String value) {
            values.put(name, value);
        }

        String required(String name) {
            return java.util.Objects.requireNonNull(values.get(name), "Missing cookie " + name);
        }
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({SpikeSecurityConfiguration.class, SpikeController.class})
    static class SpikeApplication {

        @Bean
        ObjectMapper legacyObjectMapper() {
            return new ObjectMapper();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebFluxSecurity
    @org.springframework.session.data.redis.config.annotation.web.server.EnableRedisIndexedWebSession(
            maxInactiveIntervalInSeconds = 900,
            redisNamespace = "crewscope:m7-s01")
    static class SpikeSecurityConfiguration {

        @Bean
        WebSessionServerSecurityContextRepository securityContextRepository() {
            return new WebSessionServerSecurityContextRepository();
        }

        @Bean
        ReactiveAuthenticationManager authenticationManager() {
            Map<String, String> passwords = Map.of(
                    "alice", "alice-password",
                    "bob", "bob-password");
            return authentication -> {
                String expected = passwords.get(authentication.getName());
                if (expected == null || !expected.equals(authentication.getCredentials())) {
                    return Mono.error(new BadCredentialsException("invalid credentials"));
                }
                return Mono.just(UsernamePasswordAuthenticationToken.authenticated(
                        authentication.getName(),
                        "[PROTECTED]",
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
            };
        }

        @Bean
        AuthenticationWebFilter jsonLoginFilter(
                ReactiveAuthenticationManager authenticationManager,
                WebSessionServerSecurityContextRepository securityContextRepository,
                ObjectMapper objectMapper) {
            AuthenticationWebFilter filter = new AuthenticationWebFilter(authenticationManager);
            filter.setRequiresAuthenticationMatcher(new PathPatternParserServerWebExchangeMatcher(
                    "/api/v1/auth/login", HttpMethod.POST));
            filter.setSecurityContextRepository(securityContextRepository);
            filter.setServerAuthenticationConverter(exchange -> DataBufferUtils.join(
                            exchange.getRequest().getBody(), MAX_LOGIN_BODY_BYTES)
                    .flatMap(buffer -> credentials(buffer, objectMapper))
                    .onErrorMap(error -> error instanceof BadCredentialsException
                            ? error
                            : new BadCredentialsException("invalid credentials", error)));
            filter.setAuthenticationSuccessHandler(loginSuccessHandler(objectMapper));
            filter.setAuthenticationFailureHandler(loginFailureHandler(objectMapper));
            return filter;
        }

        @Bean
        SecurityWebFilterChain spikeSecurityWebFilterChain(
                ServerHttpSecurity http,
                AuthenticationWebFilter jsonLoginFilter,
                WebSessionServerSecurityContextRepository securityContextRepository,
                ObjectMapper objectMapper) {
            CookieServerCsrfTokenRepository csrf = CookieServerCsrfTokenRepository.withHttpOnlyFalse();
            csrf.setCookiePath("/");

            return http
                    .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                    .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                    .securityContextRepository(securityContextRepository)
                    .csrf(spec -> spec
                            .csrfTokenRepository(csrf)
                            // Vue reads the raw cookie and echoes it in the configured header.
                            .csrfTokenRequestHandler(new ServerCsrfTokenRequestAttributeHandler())
                            .accessDeniedHandler((exchange, denied) -> writeJson(
                                    exchange,
                                    HttpStatus.FORBIDDEN,
                                    Map.of("code", "csrf_rejected"),
                                    objectMapper)))
                    .exceptionHandling(spec -> spec
                            .authenticationEntryPoint((exchange, denied) -> writeJson(
                                    exchange,
                                    HttpStatus.UNAUTHORIZED,
                                    Map.of("code", "authentication_required"),
                                    objectMapper))
                            .accessDeniedHandler((exchange, denied) -> writeJson(
                                    exchange,
                                    HttpStatus.FORBIDDEN,
                                    Map.of("code", "access_denied"),
                                    objectMapper)))
                    .authorizeExchange(spec -> spec
                            .pathMatchers("/api/v1/auth/session", "/api/v1/auth/login").permitAll()
                            .anyExchange().authenticated())
                    .logout(spec -> spec
                            .logoutUrl("/api/v1/auth/logout")
                            .logoutHandler(new WebSessionServerLogoutHandler())
                            .logoutSuccessHandler((exchange, authentication) -> {
                                exchange.getExchange().getResponse().setStatusCode(HttpStatus.NO_CONTENT);
                                return exchange.getExchange().getResponse().setComplete();
                            }))
                    .addFilterAt(jsonLoginFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                    .build();
        }

        private static Mono<Authentication> credentials(DataBuffer buffer, ObjectMapper objectMapper) {
            try {
                byte[] bytes = new byte[buffer.readableByteCount()];
                buffer.read(bytes);
                LoginRequest login = objectMapper.readValue(bytes, LoginRequest.class);
                return Mono.just(UsernamePasswordAuthenticationToken.unauthenticated(
                        login.username(), login.password()));
            } catch (Exception exception) {
                return Mono.error(new BadCredentialsException("invalid credentials", exception));
            } finally {
                DataBufferUtils.release(buffer);
            }
        }

        private static ServerAuthenticationSuccessHandler loginSuccessHandler(ObjectMapper objectMapper) {
            return (filterExchange, authentication) -> filterExchange
                    .getExchange()
                    .getSession()
                    .flatMap(WebSession::changeSessionId)
                    .then(writeJson(
                            filterExchange.getExchange(),
                            HttpStatus.OK,
                            Map.of("authenticated", true, "username", authentication.getName()),
                            objectMapper));
        }

        private static ServerAuthenticationFailureHandler loginFailureHandler(ObjectMapper objectMapper) {
            return (filterExchange, failure) -> writeJson(
                    filterExchange.getExchange(),
                    HttpStatus.UNAUTHORIZED,
                    Map.of("code", "invalid_credentials"),
                    objectMapper);
        }

        private static Mono<Void> writeJson(
                ServerWebExchange exchange,
                HttpStatus status,
                Map<String, ?> body,
                ObjectMapper objectMapper) {
            try {
                byte[] bytes = objectMapper.writeValueAsBytes(body);
                exchange.getResponse().setStatusCode(status);
                exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                return exchange.getResponse()
                        .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
            } catch (Exception exception) {
                return Mono.error(exception);
            }
        }
    }

    @RestController
    @RequestMapping("/api/v1")
    static class SpikeController {

        @GetMapping("/auth/session")
        Mono<Map<String, Object>> session(ServerWebExchange exchange, Authentication authentication) {
            Mono<CsrfToken> csrf = exchange.getAttribute(CsrfToken.class.getName());
            if (csrf == null) {
                return Mono.error(new IllegalStateException("CSRF token is not available"));
            }
            return Mono.zip(exchange.getSession(), csrf).map(tuple -> {
                // Establish the anonymous pre-login Session so rotation can be proven explicitly.
                tuple.getT1().start();
                Map<String, Object> response = new LinkedHashMap<>();
                boolean authenticated = authentication != null && authentication.isAuthenticated();
                response.put("authenticated", authenticated);
                response.put("username", authenticated ? authentication.getName() : null);
                response.put("csrfHeader", tuple.getT2().getHeaderName());
                response.put("csrfParameter", tuple.getT2().getParameterName());
                return response;
            });
        }

        @PostMapping("/spike/write")
        Map<String, Object> write(Authentication authentication) {
            return Map.of("accepted", true, "actor", authentication.getName());
        }
    }

    private record LoginRequest(String username, String password) {}
}
