package io.crewscope.server.security.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.crewscope.server.teambeta.AbstractTeamBetaPostgresRedisContainerIntegrationTest;
import io.crewscope.server.security.session.BrowserSessionProperties;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.filter.annotation.TypeExcludeFilters;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.Session;
import org.springframework.session.data.redis.ReactiveRedisIndexedSessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The M12-I01 revocation-delay contract under a real OIDC login: account-level disablement is
 * immediate (the very next request re-checks the security version, answers anonymous and drops
 * the Redis Session), while IdP-side invalidation is honestly bounded by the Session TTL —
 * CrewScope keeps no IdP tokens after establish, so no Session can outlive its TTL.
 */
@Tag("integration")
@ActiveProfiles("team-beta")
@TypeExcludeFilters(OidcRevocationDelayM12I01IntegrationTest.ExcludeTestClasspathConfigurations.class)
@SpringBootTest(
        classes = io.crewscope.server.CrewScopeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OidcRevocationDelayM12I01IntegrationTest
        extends AbstractTeamBetaPostgresRedisContainerIntegrationTest {

    private static final String ORGANIZATION_ID = "0198a475-0831-7000-8000-000000000001";
    private static final String SESSION_COOKIE = "CREWSCOPE_SESSION";

    @TempDir
    static Path artifactRoot;

    static final Path CODING_ROOT = createDirectory("crewscope-m12i01-revocation");

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
                () -> "m12i01-revocation-bootstrap-3f81a6d4c2b9");
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
    }

    @BeforeAll
    static void verifyIdpIsReachable() {
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
    @Autowired private ReactiveRedisIndexedSessionRepository sessions;
    @Autowired private BrowserSessionProperties sessionProperties;

    private WebTestClient client;

    @BeforeEach
    void bindClient() {
        IDP.reset();
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void disablingTheAccountRevokesEverySessionOnTheNextRequest() {
        String subject = "revocation-subject-disabled";
        subjectClaims(subject, "Disabled SSO", "disabled.sso@example.com");
        CookieJar desktop = new CookieJar();
        driveThreeHops(desktop);
        CookieJar phone = new CookieJar();
        driveThreeHops(phone);
        UUID accountId = linkedAccountId(subject);
        assertThat(sessionView(desktop).path("authenticated").asBoolean()).isTrue();
        assertThat(sessionView(phone).path("authenticated").asBoolean()).isTrue();

        // The operator disables the account the audited way: status flips and the security
        // version advances, so every established Session principal is instantly stale. The
        // guard trigger demands version+1 and a fresh updated_at on every mutation.
        jdbc.update("""
                UPDATE crewscope.user_account
                SET status = 'DISABLED',
                    security_version = security_version + 1,
                    version = version + 1,
                    updated_at = NOW()
                WHERE id = ?
                """, accountId);

        JsonNode desktopView = sessionView(desktop);
        assertThat(desktopView.path("authenticated").asBoolean()).isFalse();
        assertThat(desktopView.path("account").isEmpty()).isTrue();
        assertThat(storedSession(desktop)).as("the desktop Session is gone from Redis").isNull();
        JsonNode phoneView = sessionView(phone);
        assertThat(phoneView.path("authenticated").asBoolean()).isFalse();
        assertThat(storedSession(phone)).as("the phone Session is gone from Redis").isNull();

        // The device list is guarded again: an anonymous Session cannot enumerate anything.
        getWithJar("/api/v1/auth/sessions", desktop)
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void sessionTtlCapsIdpSideRevocationAtTwelveHours() {
        CookieJar jar = new CookieJar();
        driveThreeHops(jar);
        Session stored = storedSession(jar);

        // The deployment TTL is 12h and the Redis Session really carries it: CrewScope holds
        // no IdP tokens after establish, so IdP-side invalidation cannot outlive this TTL.
        assertThat(sessionProperties.getTtl()).isEqualByComparingTo(Duration.ofHours(12));
        assertThat(stored).isNotNull();
        assertThat(stored.getMaxInactiveInterval())
                .as("the stored Session expires with the configured TTL")
                .isEqualByComparingTo(Duration.ofHours(12));
    }

    // --- driving helpers -------------------------------------------------------

    /** Drives the full browser round-trip: start, IdP authorize, code callback. */
    private void driveThreeHops(CookieJar jar) {
        EntityExchangeResult<byte[]> start = getWithJar("/oauth2/authorization/company", jar)
                .expectStatus().isEqualTo(HttpStatus.FOUND.value())
                .expectBody(byte[].class)
                .returnResult();
        jar.captureCookies(start);

        EntityExchangeResult<byte[]> consent = client.get()
                .uri(start.getResponseHeaders().getLocation())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FOUND.value())
                .expectBody(byte[].class)
                .returnResult();
        URI callbackUri = consent.getResponseHeaders().getLocation();
        assertThat(callbackUri).isNotNull();

        EntityExchangeResult<byte[]> callback = getWithJar(callbackUri, jar)
                .expectBody(byte[].class)
                .returnResult();
        jar.captureCookies(callback);
        assertThat(callback.getStatus()).isEqualTo(HttpStatus.FOUND);
        assertThat(callback.getResponseHeaders().getLocation().getPath()).isEqualTo("/");
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
        // No cookie capture here: after a revocation the response expires the cookie, and the
        // jar must keep the old identifier so the Redis lookup can prove the Session is gone.
        EntityExchangeResult<byte[]> body = getWithJar("/api/v1/auth/session", jar)
                .expectStatus().isOk()
                .expectBody(byte[].class)
                .returnResult();
        return readJson(body);
    }

    /** Reads the Redis Session behind the browser cookie, or null once it is deleted. */
    private Session storedSession(CookieJar jar) {
        return sessions
                .findById(jar.required(SESSION_COOKIE))
                .block(Duration.ofSeconds(5));
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
                WHERE provider = 'oidc/company' AND subject = ?
                """, UUID.class, subject);
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

    /** Minimal cookie jar preserving the rotating Session across the three hops. */
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

        String required(String name) {
            String value = values.get(name);
            if (value == null) {
                throw new AssertionError("cookie " + name + " missing from the jar");
            }
            return value;
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
