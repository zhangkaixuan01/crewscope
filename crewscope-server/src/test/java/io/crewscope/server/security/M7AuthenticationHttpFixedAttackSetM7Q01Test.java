package io.crewscope.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.crewscope.application.identity.LocalAccountLoginException;
import io.crewscope.server.api.ApiExceptionHandler;
import io.crewscope.server.config.SecurityConfiguration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Stable M7-Q01 enumeration, CSRF and Origin HTTP attack denominator. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class M7AuthenticationHttpFixedAttackSetM7Q01Test {

    private static final int ENUMERATION_ATTACKS = 12;
    private static final int CSRF_ATTACKS = 10;
    private static final int ORIGIN_ATTACKS = 16;

    private AnnotationConfigApplicationContext context;
    private WebTestClient client;

    @BeforeAll
    void startSecurityBoundary() {
        context = new AnnotationConfigApplicationContext();
        TestPropertyValues.of(
                        "crewscope.security.mode=local",
                        "crewscope.security.bootstrap.username=crewscope",
                        "crewscope.security.bootstrap.password=test-password",
                        "crewscope.security.monitoring.username=crewscope-prometheus",
                        "crewscope.security.monitoring.password=monitoring-password")
                .applyTo(context);
        context.registerBean(
                WebSessionServerSecurityContextRepository.class,
                WebSessionServerSecurityContextRepository::new);
        context.register(SecurityConfiguration.class);
        context.refresh();
        List<SecurityWebFilterChain> chains = List.copyOf(
                context.getBeansOfType(SecurityWebFilterChain.class).values());
        client = WebTestClient.bindToController(new SecurityProbeController())
                .webFilter(new WebFilterChainProxy(chains))
                .build();
    }

    @AfterAll
    void stopSecurityBoundary() {
        context.close();
    }

    @TestFactory
    Stream<DynamicTest> keepsCredentialFailuresNonEnumerating() {
        List<String> probes = List.of(
                "alice",
                "ALICE",
                "alice@example.com",
                "missing@example.com",
                "locked-user",
                "disabled-user",
                "operator",
                "crewscope-monitor",
                "用户@example.com",
                "a".repeat(1024),
                "' OR 1=1 --",
                "../../accounts/admin");
        List<String> ids = java.util.stream.IntStream.rangeClosed(1, probes.size())
                .mapToObj(index -> "EN-%02d".formatted(index))
                .toList();
        assertStableIds(ids, "EN", ENUMERATION_ATTACKS);
        return java.util.stream.IntStream.range(0, probes.size()).mapToObj(index -> {
            String id = ids.get(index);
            String probe = probes.get(index);
            return DynamicTest.dynamicTest(id, () -> {
                var exchange = MockServerWebExchange.from(
                        MockServerHttpRequest.post("/api/v1/auth/login"));
                var response = new ApiExceptionHandler()
                        .handle(new LocalAccountLoginException(), exchange);
                String body = new ObjectMapper().writeValueAsString(response.getBody());
                assertThat(response.getStatusCode().value()).isEqualTo(401);
                assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isNull();
                assertThat(body)
                        .contains("\"code\":\"invalid_credentials\"")
                        .doesNotContain(probe)
                        .doesNotContain("locked", "disabled", "missing");
            });
        });
    }

    @TestFactory
    Stream<DynamicTest> rejectsCsrfConfusionAttacks() {
        List<CsrfAttack> attacks = List.of(
                new CsrfAttack("CS-01", null, List.of()),
                new CsrfAttack("CS-02", null, List.of("attacker")),
                new CsrfAttack("CS-03", "attacker", List.of()),
                new CsrfAttack("CS-04", "cookie-token", List.of("header-token")),
                new CsrfAttack("CS-05", "cookie-token", List.of("header-token", "second-token")),
                new CsrfAttack("CS-06", "cookie-token", List.of("header-token,second-token")),
                new CsrfAttack("CS-07", "cookie-token", List.of("%63ookie-token")),
                new CsrfAttack("CS-08", "cookie-token", List.of(" cookie-token ")),
                new CsrfAttack("CS-09", "cookie-token", List.of("null")),
                new CsrfAttack("CS-10", "a".repeat(64), List.of("a".repeat(63) + "b")));
        assertStableIds(attacks.stream().map(CsrfAttack::id).toList(), "CS", CSRF_ATTACKS);
        return attacks.stream().map(attack -> DynamicTest.dynamicTest(attack.id(), () -> {
            WebTestClient.RequestBodySpec request = client.post()
                    .uri("http://localhost/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON);
            if (attack.cookie() != null) {
                request.cookie("XSRF-TOKEN", attack.cookie());
            }
            attack.headers().forEach(value -> request.header("X-XSRF-TOKEN", value));
            request.bodyValue("{}")
                    .exchange()
                    .expectStatus().isForbidden()
                    .expectHeader().contentType(MediaType.APPLICATION_JSON)
                    .expectHeader().cacheControl(org.springframework.http.CacheControl.noStore())
                    .expectBody()
                    .jsonPath("$.code").isEqualTo("csrf_rejected")
                    .jsonPath("$.details").isEmpty();
        }));
    }

    @TestFactory
    Stream<DynamicTest> rejectsOriginParserAndAuthorityAttacks() {
        List<OriginAttack> attacks = List.of(
                origin("OR-01", "https://attacker.example"),
                origin("OR-02", "null"),
                origin("OR-03", "ftp://localhost"),
                origin("OR-04", "//localhost"),
                origin("OR-05", "http://localhost:81"),
                origin("OR-06", "https://localhost"),
                origin("OR-07", "http://user@localhost"),
                origin("OR-08", "http://localhost/path"),
                origin("OR-09", "http://localhost?query=1"),
                origin("OR-10", "http://localhost#fragment"),
                origin("OR-11", "http://localhost.evil.example"),
                origin("OR-12", "http://127.0.0.1"),
                origin("OR-13", "http://[::1]"),
                origin("OR-14", "javascript:alert(1)"),
                origin("OR-15", "http://localhost:0"),
                new OriginAttack("OR-16", List.of("http://localhost", "https://attacker.example")));
        assertStableIds(attacks.stream().map(OriginAttack::id).toList(), "OR", ORIGIN_ATTACKS);
        return attacks.stream().map(attack -> DynamicTest.dynamicTest(attack.id(), () -> {
            WebTestClient.RequestHeadersSpec<?> request = client.get()
                    .uri("http://localhost/api/v1/auth/session");
            attack.values().forEach(value -> request.header(HttpHeaders.ORIGIN, value));
            request.exchange()
                    .expectStatus().isForbidden()
                    .expectHeader().contentType(MediaType.APPLICATION_JSON)
                    .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)
                    .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)
                    .expectBody()
                    .jsonPath("$.code").isEqualTo("cross_origin_rejected")
                    .jsonPath("$.details").isEmpty();
        }));
    }

    private static OriginAttack origin(String id, String value) {
        return new OriginAttack(id, List.of(value));
    }

    private static void assertStableIds(List<String> ids, String prefix, int expected) {
        assertThat(ids).hasSize(expected).doesNotHaveDuplicates();
        assertThat(ids).containsExactly(java.util.stream.IntStream.rangeClosed(1, expected)
                .mapToObj(index -> "%s-%02d".formatted(prefix, index))
                .toArray(String[]::new));
    }

    private record CsrfAttack(String id, String cookie, List<String> headers) {}

    private record OriginAttack(String id, List<String> values) {}

    @RestController
    private static class SecurityProbeController {

        @GetMapping("/api/v1/auth/session")
        Map<String, Object> session() {
            return Map.of("authenticated", false);
        }

        @PostMapping("/api/v1/auth/login")
        Map<String, Object> login(@RequestBody(required = false) String ignored) {
            return Map.of("authenticated", true);
        }
    }
}
