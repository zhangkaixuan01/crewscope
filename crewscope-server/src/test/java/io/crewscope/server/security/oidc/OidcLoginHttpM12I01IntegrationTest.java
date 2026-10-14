package io.crewscope.server.security.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.crewscope.application.identity.AuthenticationFlow;
import io.crewscope.application.identity.LoginDefense;
import io.crewscope.domain.identity.LoginAttemptPolicy;
import io.crewscope.domain.identity.RegistrationMode;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.filter.annotation.TypeExcludeFilters;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The M12-I01 OIDC login flow over real HTTP against an embedded provider: three browser hops
 * (authorization start, IdP consent redirect, code callback) driven with an explicit cookie jar,
 * a real PostgreSQL/Redis stack, and defense admission. Each scenario logs in a distinct
 * subject, so the shared team-beta world needs no truncation between tests.
 */
@Tag("integration")
@ActiveProfiles("team-beta")
@TypeExcludeFilters(OidcLoginHttpM12I01IntegrationTest.ExcludeTestClasspathConfigurations.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(
        classes = io.crewscope.server.CrewScopeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OidcLoginHttpM12I01IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final String ORGANIZATION_ID = "0198a475-0831-7000-8000-000000000001";
    private static final String PROVIDER_KEY = "oidc/company";
    private static final String SESSION_COOKIE = "SESSION";

    @TempDir
    static Path artifactRoot;

    static final Path CODING_ROOT = createDirectory("crewscope-m12i01-oidc");

    private static final String TEST_CREDENTIAL_KEYS =
            "v1=7fxmDni7ah4GVkFpXlFp/WJ9Xvlk4lVlEuMO5YMmYPw=";
    private static final String TEST_ACTIVITY_KEY =
            "qy+UtChs7KTKHsPDCKXhhm41lKIf8/3GaSKmClfeqwM=";
    private static final String TEST_LOGIN_DEFENSE_KEY =
            "gL9T+TUAof/s+bhqjftsy17/a4NSctPXA+/6pIZzA1o=";
    private static final String TEST_TASK_TOKEN_KEY =
            "u1WSa5SkDSf8N10uJ2sJE72U/u+ZDGrjaffROM17X5U=";
    private static final String TEST_INVITATION_KEY =
            "asKlHnh1ld0Le3FJyzEj1VDm2poi7ZMyuzisUyIpg6k=";

    private static final EmbeddedOidcIdp IDP = EmbeddedOidcIdp.start();

    @DynamicPropertySource
    static void oidcDeploymentProperties(DynamicPropertyRegistry registry) {
        registry.add("crewscope.security.mode", () -> "oidc");
        registry.add("crewscope.security.oidc.organization-id", () -> ORGANIZATION_ID);
        registry.add("crewscope.artifact.filesystem.root", () -> artifactRoot.toString());
        registry.add("crewscope.credential.encryption.keys", () -> TEST_CREDENTIAL_KEYS);
        registry.add("crewscope.team-activity-realtime.keys.v1", () -> TEST_ACTIVITY_KEY);
        registry.add("crewscope.security.login-defense.hmac-key", () -> TEST_LOGIN_DEFENSE_KEY);
        registry.add("crewscope.security.task-token.keys.v1", () -> TEST_TASK_TOKEN_KEY);
        registry.add("crewscope.invitation.token.hmac-key", () -> TEST_INVITATION_KEY);
        registry.add("crewscope.coding.repository.managed-root",
                () -> prepared(CODING_ROOT, "repositories"));
        registry.add("crewscope.coding.worktree.root", () -> prepared(CODING_ROOT, "worktrees"));
        registry.add("crewscope.coding.worktree.lock-root",
                () -> prepared(CODING_ROOT, "worktree-locks"));
        registry.add("crewscope.coding.git.command-home", () -> prepared(CODING_ROOT, "git-home"));
        registry.add("crewscope.runtime.redis.url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getFirstMappedPort());
        registry.add("crewscope.security.bootstrap.password",
                () -> "m12i01-oidc-bootstrap-4f6a9b1c2d3e");
        registry.add("spring.security.oauth2.client.registration.company.client-id",
                IDP::clientId);
        registry.add("spring.security.oauth2.client.registration.company.client-secret",
                IDP::clientSecret);
        registry.add("spring.security.oauth2.client.registration.company"
                        + ".authorization-grant-type", () -> "authorization_code");
        registry.add("spring.security.oauth2.client.registration.company.redirect-uri",
                () -> "{baseUrl}/login/oauth2/code/company");
        registry.add("spring.security.oauth2.client.registration.company.scope", () -> "openid");
        registry.add("spring.security.oauth2.client.provider.company.issuer-uri", IDP::issuer);
        // The outage scenario needs Lettuce to fail fast instead of hanging on the default 60s
        // command timeout: the start gate must answer 503 well inside the client read timeout.
        registry.add("spring.data.redis.timeout", () -> "2s");
    }

    @BeforeAll
    static void verifyIdpIsReachable() {
        // Fail fast with a clear message if the embedded provider failed to bind.
        assertThat(IDP.issuer()).startsWith("http://127.0.0.1:");
    }

    @AfterAll
    static void stopIdp() {
        IDP.close();
    }

    @LocalServerPort
    private int port;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LoginDefense defense;
    @Autowired private io.crewscope.server.config.RegistrationProperties registration;

    private WebTestClient client;

    @BeforeEach
    void bindClient() {
        IDP.reset();
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    @Order(1)
    void firstLoginProvisionsTheAccountSetAndEstablishesASession() {
        String subject = "http-subject-first-login";
        subjectClaims(subject, "Ada SSO", "ada.sso@example.com");

        CookieJar jar = new CookieJar();
        Hop landing = driveThreeHops(jar, subject);

        assertThat(landing.location()).hasPath("/");
        UUID accountId = linkedAccountId(subject);
        assertThat(accountId).isNotNull();

        // The four-part provisioning set, one row each, anchored on the linked identity.
        assertThat(count("SELECT COUNT(*) FROM crewscope.user_account WHERE id = ?",
                accountId)).isEqualTo(1);
        assertThat(count("""
                SELECT COUNT(*) FROM crewscope.login_identity
                WHERE provider = ? AND subject = ? AND status = 'ACTIVE'
                """, PROVIDER_KEY, subject)).isEqualTo(1);
        assertThat(count("""
                SELECT COUNT(*) FROM crewscope.account_organization_binding WHERE account_id = ?
                """, accountId)).isEqualTo(1);
        assertThat(count("""
                SELECT COUNT(*) FROM crewscope.domain_event
                WHERE organization_id = ? AND event_type = 'USER_ACCOUNT_REGISTERED'
                """, UUID.fromString(ORGANIZATION_ID))).isEqualTo(1);
        assertThat(count("""
                SELECT COUNT(*) FROM crewscope.outbox_event o
                JOIN crewscope.domain_event e ON e.event_id = o.domain_event_id
                WHERE e.organization_id = ?
                  AND e.event_type IN ('USER_ACCOUNT_REGISTERED', 'AUTHENTICATION_SUCCEEDED')
                """, UUID.fromString(ORGANIZATION_ID))).isEqualTo(2);

        // The Session is the same shape a local login produces: /session resolves the browser
        // principal, projects the linked identities and names the SSO entry point.
        JsonNode session = sessionView(jar);
        assertThat(session.path("authenticated").asBoolean()).isTrue();
        assertThat(session.path("account").path("accountId").asText())
                .isEqualTo(accountId.toString());
        JsonNode identities = session.path("account").path("loginIdentities");
        assertThat(identities).hasSize(1);
        assertThat(identities.get(0).path("provider").asText()).isEqualTo(PROVIDER_KEY);
        assertThat(identities.get(0).path("status").asText()).isEqualTo("ACTIVE");
        assertThat(session.path("ssoAuthorizationUrl").asText())
                .isEqualTo("/oauth2/authorization/company");
    }

    @Test
    @Order(2)
    void secondLoginReauthenticatesTheLinkedIdentity() {
        String subject = "http-subject-reauth";
        subjectClaims(subject, "Grace SSO", "grace.sso@example.com");

        CookieJar firstJar = new CookieJar();
        driveThreeHops(firstJar, subject);
        OffsetDateTime firstLogin = lastAuthenticatedAt(subject);
        assertThat(firstLogin).isNotNull();

        CookieJar secondJar = new CookieJar();
        Hop secondLanding = driveThreeHops(secondJar, subject);

        assertThat(secondLanding.location()).hasPath("/");
        assertThat(count("""
                SELECT COUNT(*) FROM crewscope.login_identity
                WHERE provider = ? AND subject = ?
                """, PROVIDER_KEY, subject)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM crewscope.user_account WHERE id = ?",
                linkedAccountId(subject))).isEqualTo(1);
        OffsetDateTime secondLogin = lastAuthenticatedAt(subject);
        assertThat(secondLogin.isAfter(firstLogin))
                .as("re-authentication moves last_authenticated_at forward")
                .isTrue();
    }

    @Test
    @Order(3)
    void inviteOnlyDeploymentBlocksFirstLoginsWithoutSideEffects() {
        String subject = "http-subject-invite";
        subjectClaims(subject, "Invited SSO", "invited.sso@example.com");
        registration.setMode(RegistrationMode.INVITE_ONLY);
        try {
            Hop landing = driveThreeHops(new CookieJar(), subject);
            assertThat(landing.location().toString())
                    .isEqualTo("/login?sso_error=registration_blocked");
            assertThat(count("""
                    SELECT COUNT(*) FROM crewscope.login_identity
                    WHERE provider = ? AND subject = ?
                    """, PROVIDER_KEY, subject)).isZero();
        } finally {
            registration.setMode(RegistrationMode.OPEN);
        }
    }

    @Test
    @Order(4)
    void lockedKnownAccountRefusesANewSession() {
        String subject = "http-subject-locked";
        subjectClaims(subject, "Locked SSO", "locked.sso@example.com");
        driveThreeHops(new CookieJar(), subject);
        UUID accountId = linkedAccountId(subject);
        OffsetDateTime established = lastAuthenticatedAt(subject);

        io.crewscope.domain.identity.UserAccountId lockedAccount =
                new io.crewscope.domain.identity.UserAccountId(accountId);
        for (int failure = 0;
                failure < LoginAttemptPolicy.standard().accountFailureLimit();
                failure++) {
            defense.recordFailure(lockedAccount).toCompletableFuture().join();
        }

        Hop landing = driveThreeHops(new CookieJar(), subject);
        assertThat(landing.location().toString()).isEqualTo("/login?sso_error=locked");
        assertThat(lastAuthenticatedAt(subject)).isEqualTo(established);
    }

    @Test
    @Order(99)
    void exhaustedNetworkWindowFailsTheStartGateClosed() {
        // The test client may connect over either loopback family (127.0.0.1 or ::1) and each
        // family hashes to its own defense bucket, so exhausting only the HTTP path could land
        // on whichever family the connection pool happened to favor. Exhaust both buckets
        // directly, then one real start must meet the closed gate over either family.
        // 127.0.0.1/24 canonicalizes with the host octet already cleared: 7f000000, not 7f000001.
        exhaustNetworkWindow("ipv4:7f000000/24");
        exhaustNetworkWindow("ipv6:0000000000000000/64");

        EntityExchangeResult<byte[]> start = client.get()
                .uri("/oauth2/authorization/company")
                .exchange()
                .expectBody(byte[].class)
                .returnResult();
        assertThat(start.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(readJson(start).path("code").asText()).isEqualTo("too_many_requests");
    }

    private void exhaustNetworkWindow(String canonicalNetwork) {
        io.crewscope.application.identity.ControlledNetworkResource network =
                io.crewscope.application.identity.ControlledNetworkResource.ofCanonical(
                        canonicalNetwork);
        int limit = LoginAttemptPolicy.standard().controlledNetworkAttemptLimit();
        for (int attempt = 0; attempt <= limit + 5; attempt++) {
            io.crewscope.application.identity.LoginResourceAdmission admission =
                    defense.admitNetwork(AuthenticationFlow.OIDC, network)
                            .toCompletableFuture().join();
            if (!admission.allowed()) {
                return;
            }
        }
        throw new IllegalStateException("network window " + canonicalNetwork + " never closed");
    }

    @Test
    @Order(100)
    void redisOutageFailsTheStartGateClosed() throws Exception {
        REDIS.stop();
        try {
            EntityExchangeResult<byte[]> start = client.get()
                    .uri("/oauth2/authorization/company")
                    .exchange()
                    .expectBody(byte[].class)
                    .returnResult();
            assertThat(start.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(readJson(start).path("code").asText())
                    .isEqualTo("login_defense_unavailable");
        } finally {
            REDIS.start();
        }
    }

    // --- driving helpers -------------------------------------------------------

    private record Hop(URI location) { }

    /** Drives the full browser round-trip: start, IdP authorize, code callback. */
    private Hop driveThreeHops(CookieJar jar, String subject) {
        EntityExchangeResult<byte[]> start = getWithJar("/oauth2/authorization/company", jar)
                .expectStatus().isEqualTo(HttpStatus.FOUND.value())
                .expectBody(byte[].class)
                .returnResult();
        jar.captureCookies(start);
        URI authorizeUri = start.getResponseHeaders().getLocation();
        // The product resolver must force PKCE on every start (C3 re-checks under attack).
        assertThat(authorizeUri.getRawQuery()).contains("code_challenge=");
        assertThat(authorizeUri.getRawQuery()).contains("code_challenge_method=S256");

        EntityExchangeResult<byte[]> consent = client.get()
                .uri(authorizeUri)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FOUND.value())
                .expectBody(byte[].class)
                .returnResult();
        assertThat(consent.getResponseHeaders().getLocation()).isNotNull();

        EntityExchangeResult<byte[]> callback = getWithJar(
                consent.getResponseHeaders().getLocation(), jar)
                .expectBody(byte[].class)
                .returnResult();
        jar.captureCookies(callback);
        assertThat(callback.getStatus()).isEqualTo(HttpStatus.FOUND);
        return new Hop(callback.getResponseHeaders().getLocation());
    }

    private WebTestClient.ResponseSpec getWithJar(Object uri, CookieJar jar) {
        // A String template merges with the client baseUrl; a URI must stay absolute because
        // WebClient.uri(URI) deliberately ignores the baseUrl.
        WebTestClient.RequestHeadersSpec<?> request = uri instanceof URI absolute
                ? client.get().uri(absolute)
                : client.get().uri(String.valueOf(uri));
        jar.apply(request);
        return request.exchange();
    }

    private JsonNode sessionView(CookieJar jar) {
        EntityExchangeResult<byte[]> body = getWithJar("/api/v1/auth/session", jar)
                .expectStatus().isOk()
                .expectBody(byte[].class)
                .returnResult();
        return readJson(body);
    }

    private JsonNode readJson(EntityExchangeResult<byte[]> result) {
        try {
            return objectMapper.readTree(result.getResponseBody());
        } catch (Exception malformed) {
            throw new AssertionError("malformed JSON response", malformed);
        }
    }

    private void subjectClaims(String subject, String name, String email) {
        IDP.subjectClaims(Map.of(
                "sub", subject,
                "name", name,
                "preferred_username", name.toLowerCase().replace(' ', '.') + "-p",
                "email", email,
                "email_verified", true));
    }

    private UUID linkedAccountId(String subject) {
        return jdbc.queryForObject("""
                SELECT account_id FROM crewscope.login_identity
                WHERE provider = ? AND subject = ?
                """, UUID.class, PROVIDER_KEY, subject);
    }

    private OffsetDateTime lastAuthenticatedAt(String subject) {
        return jdbc.queryForObject("""
                SELECT last_authenticated_at FROM crewscope.login_identity
                WHERE provider = ? AND subject = ?
                """, OffsetDateTime.class, PROVIDER_KEY, subject);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private static Path createDirectory(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (Exception failure) {
            throw new IllegalStateException("cannot create the coding sandbox root", failure);
        }
    }

    private static String prepared(Path root, String child) {
        try {
            return Files.createDirectories(root.resolve(child)).toString();
        } catch (Exception failure) {
            throw new IllegalStateException("cannot prepare " + child, failure);
        }
    }

    /** Minimal cookie jar preserving the rotating SESSION across the three hops. */
    private static final class CookieJar {
        private final Map<String, String> values = new LinkedHashMap<>();

        void apply(WebTestClient.RequestHeadersSpec<?> request) {
            values.forEach(request::cookie);
        }

        void captureCookies(EntityExchangeResult<?> result) {
            result.getResponseCookies().forEach((name, cookies) -> {
                ResponseCookie cookie = cookies.get(cookies.size() - 1);
                if (cookie.getMaxAge().isZero()) {
                    values.remove(name);
                } else {
                    values.put(name, cookie.getValue());
                }
            });
        }
    }
    /** Keeps @Configuration classes hosted inside test sources off the component scan. */
    static final class ExcludeTestClasspathConfigurations extends TypeExcludeFilter {
        private static final Pattern TEST_CLASSPATH_HOST =
                Pattern.compile("^io\\.crewscope\\..*(?:TestServer|Test)(?:\\$.*)?$");

        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory factory) {
            return TEST_CLASSPATH_HOST.matcher(reader.getClassMetadata().getClassName()).matches();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ExcludeTestClasspathConfigurations;
        }

        @Override
        public int hashCode() {
            return ExcludeTestClasspathConfigurations.class.hashCode();
        }
    }
}