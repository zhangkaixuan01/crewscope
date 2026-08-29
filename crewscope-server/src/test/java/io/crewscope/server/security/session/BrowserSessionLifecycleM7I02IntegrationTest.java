package io.crewscope.server.security.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.data.redis.ReactiveRedisIndexedSessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;

/** Real Redis contract for M7-I02 Session lifecycle, sharing, expiry and failure behavior. */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@Execution(ExecutionMode.SAME_THREAD)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = BrowserSessionLifecycleM7I02IntegrationTest.TestApplication.class,
        properties = {
            "spring.main.web-application-type=reactive",
            "spring.session.timeout=4s",
            "spring.session.data.redis.namespace=crewscope:m7-i02",
            "spring.session.data.redis.repository-type=indexed",
            "spring.session.data.redis.save-mode=on-set-attribute",
            "server.reactive.session.timeout=4s",
            "server.reactive.session.cookie.name=CREWSCOPE_SESSION",
            "server.reactive.session.cookie.http-only=true",
            "server.reactive.session.cookie.path=/",
            "server.reactive.session.cookie.same-site=lax",
            "crewscope.security.session.ttl=4s",
            "crewscope.security.session.maximum-sessions=2",
            "crewscope.security.session.namespace=crewscope:m7-i02",
            "crewscope.security.session.enabled=true",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                    + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                    + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
                    + "org.springframework.boot.session.data.redis.autoconfigure."
                    + "SessionDataRedisAutoConfiguration"
        })
class BrowserSessionLifecycleM7I02IntegrationTest {

    private static final int REDIS_PORT = 6379;
    private static final String COOKIE = "CREWSCOPE_SESSION";
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000701");

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
        registry.add("spring.data.redis.timeout", () -> "500ms");
        registry.add("spring.data.redis.connect-timeout", () -> "500ms");
    }

    @Autowired private ReactiveRedisIndexedSessionRepository sessions;
    @Autowired private ReactiveRedisConnectionFactory redisConnections;

    private final WebTestClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    BrowserSessionLifecycleM7I02IntegrationTest(@LocalServerPort int port) {
        this.client = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .build();
    }

    @Test
    void rotatesPersistsRenewsAndInvalidatesOneCredentialFreeSession() throws Exception {
        CookieJar browser = new CookieJar();
        browser.capture(get("/test/session/anonymous", browser, HttpStatus.OK));
        String anonymousId = browser.required(COOKIE);

        browser.capture(post("/test/session/establish/" + ALICE, browser, HttpStatus.NO_CONTENT));
        String authenticatedId = browser.required(COOKIE);
        assertThat(authenticatedId).isNotEqualTo(anonymousId);
        assertAuthenticated(browser, ALICE, 3);

        Session beforeRenewal = sessions.findById(authenticatedId).block(Duration.ofSeconds(3));
        assertThat(beforeRenewal).isNotNull();
        Instant firstAccess = beforeRenewal.getLastAccessedTime();
        Thread.sleep(1100);
        assertAuthenticated(browser, ALICE, 3);
        Session afterRenewal = sessions.findById(authenticatedId).block(Duration.ofSeconds(3));
        assertThat(afterRenewal).isNotNull();
        assertThat(afterRenewal.getLastAccessedTime()).isAfter(firstAccess);

        String redisPayload = REDIS.execInContainer(
                        "redis-cli",
                        "--raw",
                        "hgetall",
                        "crewscope:m7-i02:sessions:" + authenticatedId)
                .getStdout()
                .toLowerCase(java.util.Locale.ROOT);
        assertThat(redisPayload)
                .contains(ALICE.toString())
                .doesNotContain("example-password", "example-password-hash");

        browser.capture(post("/test/session/logout", browser, HttpStatus.NO_CONTENT));
        assertThat(sessions.findById(authenticatedId).block(Duration.ofSeconds(3))).isNull();
        assertAnonymous(browser);
    }

    @Test
    void sharesAcrossIndependentRepositoryInstancesAndSurvivesTheirRestart() throws Exception {
        CookieJar browser = established(ALICE);
        String sessionId = browser.required(COOKIE);

        ReactiveRedisIndexedSessionRepository second = independentRepository();
        try {
            Session shared = second.findById(sessionId).block(Duration.ofSeconds(3));
            assertThat(shared).isNotNull();
            assertThat(shared.getAttributeNames()).contains(
                    WebSessionServerSecurityContextRepository.DEFAULT_SPRING_SECURITY_CONTEXT_ATTR_NAME);
        } finally {
            second.destroy();
        }

        ReactiveRedisIndexedSessionRepository restarted = independentRepository();
        try {
            assertThat(restarted.findById(sessionId).block(Duration.ofSeconds(3))).isNotNull();
            assertAuthenticated(browser, ALICE, 3);
        } finally {
            restarted.destroy();
        }
    }

    @Test
    void evictsTheLeastRecentlyUsedSessionAtThePerAccountLimit() throws Exception {
        CookieJar first = established(ALICE);
        Thread.sleep(20);
        CookieJar second = established(ALICE);
        Thread.sleep(20);
        CookieJar third = established(ALICE);

        assertAnonymous(first);
        assertAuthenticated(second, ALICE, 3);
        assertAuthenticated(third, ALICE, 3);
        Map<String, ? extends Session> active = sessions.findByPrincipalName(ALICE.toString())
                .block(Duration.ofSeconds(3));
        assertThat(active).hasSize(2);
    }

    @Test
    void invalidatesEveryIndexedSessionForOnlyTheTargetAccount() throws Exception {
        CookieJar firstAlice = established(ALICE);
        CookieJar secondAlice = established(ALICE);
        UUID bob = UUID.randomUUID();
        CookieJar bobBrowser = established(bob);

        post("/test/session/revoke-all/" + ALICE, new CookieJar(), HttpStatus.NO_CONTENT);

        assertAnonymous(firstAlice);
        assertAnonymous(secondAlice);
        assertAuthenticated(bobBrowser, bob, 3);
        assertThat(sessions.findByPrincipalName(ALICE.toString())
                .block(Duration.ofSeconds(3))).isEmpty();
    }

    @Test
    void expiresWithoutFallingBackToAClientIdentity() throws Exception {
        CookieJar browser = established(UUID.randomUUID());
        String sessionId = browser.required(COOKIE);

        Thread.sleep(4300);

        assertThat(sessions.findById(sessionId).block(Duration.ofSeconds(3))).isNull();
        assertAnonymous(browser);
    }

    @Test
    void failsClosedWhenAnIndependentRedisClientCannotReadSessionTruth() {
        org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory unavailable =
                new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(
                        "127.0.0.1", 1);
        unavailable.afterPropertiesSet();
        unavailable.start();
        ReactiveRedisIndexedSessionRepository repository = new ReactiveRedisIndexedSessionRepository(
                new org.springframework.data.redis.core.ReactiveRedisTemplate<>(
                        unavailable,
                        sessions.getSessionRedisOperations().getSerializationContext()),
                new ReactiveStringRedisTemplate(unavailable));
        repository.setRedisKeyNamespace("crewscope:m7-i02");
        try {
            assertThatThrownBy(() -> {
                        repository.afterPropertiesSet();
                        repository.findById("unavailable").block(Duration.ofSeconds(3));
                    })
                    .isInstanceOf(RuntimeException.class);
        } finally {
            repository.destroy();
            unavailable.destroy();
        }
    }

    private CookieJar established(UUID accountId) throws Exception {
        CookieJar browser = new CookieJar();
        browser.capture(get("/test/session/anonymous", browser, HttpStatus.OK));
        browser.capture(post(
                "/test/session/establish/" + accountId, browser, HttpStatus.NO_CONTENT));
        assertAuthenticated(browser, accountId, 3);
        return browser;
    }

    private void assertAuthenticated(CookieJar browser, UUID accountId, long securityVersion)
            throws Exception {
        Map<String, Object> body = body(get("/test/session/current", browser, HttpStatus.OK));
        assertThat(body)
                .containsEntry("authenticated", true)
                .containsEntry("accountId", accountId.toString())
                .containsEntry("securityVersion", (int) securityVersion);
    }

    private void assertAnonymous(CookieJar browser) throws Exception {
        assertThat(body(get("/test/session/current", browser, HttpStatus.OK)))
                .containsEntry("authenticated", false);
    }

    private EntityExchangeResult<byte[]> get(String path, CookieJar jar, HttpStatus status) {
        WebTestClient.RequestHeadersSpec<?> request = client.get().uri(path);
        jar.apply(request);
        EntityExchangeResult<byte[]> result = request.exchange()
                .expectStatus().isEqualTo(status)
                .expectBody()
                .returnResult();
        jar.capture(result);
        return result;
    }

    private EntityExchangeResult<byte[]> post(String path, CookieJar jar, HttpStatus status) {
        WebTestClient.RequestHeadersSpec<?> request = client.post().uri(path);
        jar.apply(request);
        EntityExchangeResult<byte[]> result = request.exchange()
                .expectStatus().isEqualTo(status)
                .expectBody()
                .returnResult();
        jar.capture(result);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(EntityExchangeResult<byte[]> result) throws Exception {
        return objectMapper.readValue(result.getResponseBody(), Map.class);
    }

    private ReactiveRedisIndexedSessionRepository independentRepository() throws Exception {
        ReactiveRedisIndexedSessionRepository repository = new ReactiveRedisIndexedSessionRepository(
                sessions.getSessionRedisOperations(),
                new ReactiveStringRedisTemplate(redisConnections));
        repository.setRedisKeyNamespace("crewscope:m7-i02");
        repository.setDefaultMaxInactiveInterval(Duration.ofSeconds(4));
        repository.afterPropertiesSet();
        return repository;
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

        String required(String name) {
            return java.util.Objects.requireNonNull(values.get(name), "Missing cookie " + name);
        }
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({
        BrowserSessionConfiguration.class,
        TestSecurityConfiguration.class,
        SessionTestController.class
    })
    static class TestApplication {}

    @Configuration(proxyBeanMethods = false)
    @EnableWebFluxSecurity
    static class TestSecurityConfiguration {

        @Bean
        SecurityWebFilterChain testSecurityWebFilterChain(
                ServerHttpSecurity http,
                WebSessionServerSecurityContextRepository securityContexts) {
            return http.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                    .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                    .csrf(ServerHttpSecurity.CsrfSpec::disable)
                    .securityContextRepository(securityContexts)
                    .authorizeExchange(exchange -> exchange.anyExchange().permitAll())
                    .build();
        }
    }

    @RestController
    @RequestMapping("/test/session")
    static class SessionTestController {

        private final BrowserSessionLifecycle sessions;

        SessionTestController(BrowserSessionLifecycle sessions) {
            this.sessions = sessions;
        }

        @GetMapping("/anonymous")
        Mono<Map<String, Object>> anonymous(ServerWebExchange exchange) {
            return exchange.getSession().map(session -> {
                session.start();
                return Map.of("authenticated", false);
            });
        }

        @PostMapping("/establish/{accountId}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        Mono<Void> establish(@PathVariable UUID accountId, ServerWebExchange exchange) {
            return sessions.establish(
                    exchange,
                    new BrowserSessionPrincipal(accountId, 3),
                    java.util.List.of("ROLE_USER"));
        }

        @GetMapping("/current")
        Mono<Map<String, Object>> current(ServerWebExchange exchange) {
            return exchange.getPrincipal()
                    .cast(Authentication.class)
                    .filter(Authentication::isAuthenticated)
                    .map(authentication -> (BrowserSessionPrincipal) authentication.getPrincipal())
                    .map(principal -> Map.<String, Object>of(
                            "authenticated", true,
                            "accountId", principal.accountId().toString(),
                            "securityVersion", principal.securityVersion()))
                    .defaultIfEmpty(Map.of("authenticated", false));
        }

        @PostMapping("/logout")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        Mono<Void> logout(ServerWebExchange exchange) {
            return sessions.invalidateCurrent(exchange);
        }

        @PostMapping("/revoke-all/{accountId}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        Mono<Void> revokeAll(@PathVariable UUID accountId) {
            return sessions.invalidateAll(accountId);
        }
    }
}
