package io.crewscope.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The M12-I01 session enumeration gate over real HTTP, Redis and Postgres. Enumeration and
 * per-device revocation are strictly account-scoped inside the lifecycle, so this gate pins the
 * ownership contract: another account's Session identifier is indistinguishable from a missing
 * one, deleting the current Session is a logout equivalent, and the /auth/session projection
 * exposes the provider set without subjects.
 */
@Tag("integration")
@ActiveProfiles("team-beta")
@TypeExcludeFilters(AuthenticationSessionApiM12I01IntegrationTest.ExcludeTestClasspathConfigurations.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthenticationSessionApiM12I01IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    /** team-beta provisions exactly this organization and its bootstrap operator. */
    private static final String ORGANIZATION_ID = "0198a475-0831-7000-8000-000000000001";
    private static final String PASSWORD = "m12i01-session-password-77c2";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final String SESSIONS_URI = "/api/v1/auth/sessions";

    @TempDir
    static Path artifactRoot;

    /** The coding sandbox roots the deployment contract requires to resolve (never touched). */
    static final Path CODING_ROOT = createDirectory("crewscope-m12i01-coding");

    private static final String TEST_CREDENTIAL_KEYS = "v1=7fxmDni7ah4GVkFpXlFp/WJ9Xvlk4lVlEuMO5YMmYPw=";
    private static final String TEST_ACTIVITY_KEY = "qy+UtChs7KTKHsPDCKXhhm41lKIf8/3GaSKmClfeqwM=";
    private static final String TEST_LOGIN_DEFENSE_KEY = "gL9T+TUAof/s+bhqjftsy17/a4NSctPXA+/6pIZzA1o=";
    private static final String TEST_TASK_TOKEN_KEY = "u1WSa5SkDSf8N10uJ2sJE72U/u+ZDGrjaffROM17X5U=";
    private static final String TEST_INVITATION_KEY = "asKlHnh1ld0Le3FJyzEj1VDm2poi7ZMyuzisUyIpg6k=";

    @DynamicPropertySource
    static void testDeploymentProperties(DynamicPropertyRegistry registry) {
        registry.add("crewscope.artifact.filesystem.root", () -> artifactRoot.toString());
        registry.add("crewscope.credential.encryption.keys", () -> TEST_CREDENTIAL_KEYS);
        registry.add("crewscope.team-activity-realtime.keys.v1", () -> TEST_ACTIVITY_KEY);
        registry.add("crewscope.security.login-defense.hmac-key", () -> TEST_LOGIN_DEFENSE_KEY);
        registry.add("crewscope.security.task-token.keys.v1", () -> TEST_TASK_TOKEN_KEY);
        registry.add("crewscope.invitation.token.hmac-key", () -> TEST_INVITATION_KEY);
        registry.add("crewscope.coding.repository.managed-root",
                () -> prepared(CODING_ROOT, "repositories"));
        registry.add("crewscope.coding.worktree.root",
                () -> prepared(CODING_ROOT, "worktrees"));
        registry.add("crewscope.coding.worktree.lock-root",
                () -> prepared(CODING_ROOT, "worktree-locks"));
        registry.add("crewscope.coding.git.command-home",
                () -> prepared(CODING_ROOT, "git-home"));
        registry.add("crewscope.runtime.redis.url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getFirstMappedPort());
        registry.add("crewscope.security.bootstrap.password",
                () -> "m12i01-session-bootstrap-9a41c0e5d2b7");
    }

    @LocalServerPort
    private int port;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    private WebTestClient client;

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

    @BeforeEach
    void bindClient() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void sessionEnumerationRequiresAuthentication() {
        client.get()
                .uri(SESSIONS_URI)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED.value());
        // The CSRF gate sits before authentication in the filter chain, so an unauthenticated
        // DELETE without a CSRF token is answered 403 fail-closed rather than 401.
        client.delete()
                .uri(SESSIONS_URI + "/" + UUID.randomUUID())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void listsBothDevicesWithExactlyOneCurrentMarker() {
        SeededAccount account = seedAccount();
        CookieJar first = login(account);
        CookieJar second = login(account);

        JsonNode listing = getJson(SESSIONS_URI, second);

        assertThat(listing.path("sessions").isArray()).isTrue();
        List<JsonNode> sessions = new java.util.ArrayList<>();
        listing.path("sessions").forEach(sessions::add);
        assertThat(sessions).hasSize(2);
        long currentCount = sessions.stream().filter(node -> node.path("current").asBoolean()).count();
        assertThat(currentCount).isEqualTo(1);
        for (JsonNode session : sessions) {
            assertThat(session.path("sessionId").asText()).isNotBlank();
            assertThat(session.path("createdAt").asText()).isNotBlank();
            assertThat(session.path("lastAccessedAt").asText()).isNotBlank();
        }
        // Ordering stays owner-reviewable: most recently accessed first.
        assertThat(sessions.get(0).path("lastAccessedAt").asText())
                .isGreaterThanOrEqualTo(sessions.get(1).path("lastAccessedAt").asText());
        // Each browser recognizes its own device.
        String secondSessionId = sessionIdOfCurrentMarker(listing.path("sessions"));
        JsonNode firstListing = getJson(SESSIONS_URI, first);
        assertThat(sessionIdOfCurrentMarker(firstListing.path("sessions")))
                .isNotEqualTo(secondSessionId);
    }

    @Test
    void sessionProjectionExposesTheProviderSetWithoutSubjects() {
        SeededAccount account = seedAccount();
        CookieJar jar = login(account);

        JsonNode session = getJson("/api/v1/auth/session", jar);

        assertThat(session.path("authenticated").asBoolean()).isTrue();
        assertThat(session.path("identityPolicy").asText()).isEqualTo("coexist");
        JsonNode identities = session.path("account").path("loginIdentities");
        assertThat(identities.isArray()).isTrue();
        assertThat(identities).hasSize(1);
        assertThat(identities.get(0).path("provider").asText()).isEqualTo("local");
        assertThat(identities.get(0).path("status").asText()).isEqualTo("ACTIVE");
        assertThat(identities.get(0).has("subject")).isFalse();
    }

    @Test
    void deletingAnotherAccountsSessionAnswersPlainNotFound() {
        SeededAccount owner = seedAccount();
        SeededAccount stranger = seedAccount();
        CookieJar ownerJar = login(owner);
        CookieJar strangerJar = login(stranger);
        String strangerSessionId = sessionIdOfCurrentMarker(getJson(SESSIONS_URI, strangerJar).path("sessions"));

        deleteSession(ownerJar, strangerSessionId)
                .expectStatus().isEqualTo(HttpStatus.NOT_FOUND.value())
                .expectBody()
                .jsonPath("code").isEqualTo("session_not_found");

        // The stranger's device survived the forged request untouched.
        JsonNode strangerSession = getJson("/api/v1/auth/session", strangerJar);
        assertThat(strangerSession.path("authenticated").asBoolean()).isTrue();
        assertThat(getJson(SESSIONS_URI, ownerJar).path("sessions").size()).isEqualTo(1);
    }

    @Test
    void deletingAnotherOwnDeviceEndsOnlyThatSession() {
        SeededAccount account = seedAccount();
        CookieJar keeper = login(account);
        CookieJar victim = login(account);
        String victimSessionId = sessionIdOfCurrentMarker(getJson(SESSIONS_URI, victim).path("sessions"));

        deleteSession(keeper, victimSessionId)
                .expectStatus().isEqualTo(HttpStatus.NO_CONTENT.value());

        JsonNode invalidated = getJson("/api/v1/auth/session", victim);
        assertThat(invalidated.path("authenticated").asBoolean()).isFalse();
        // The anonymous projection carries no account payload (null or absent, never a body).
        assertThat(invalidated.path("account").isEmpty()).isTrue();
        JsonNode keeperSession = getJson("/api/v1/auth/session", keeper);
        assertThat(keeperSession.path("authenticated").asBoolean()).isTrue();
        assertThat(getJson(SESSIONS_URI, keeper).path("sessions").size()).isEqualTo(1);
    }

    @Test
    void deletingTheCurrentSessionIsALogoutEquivalent() {
        SeededAccount account = seedAccount();
        CookieJar jar = login(account);
        String currentSessionId = sessionIdOfCurrentMarker(getJson(SESSIONS_URI, jar).path("sessions"));

        deleteSession(jar, currentSessionId)
                .expectStatus().isEqualTo(HttpStatus.NO_CONTENT.value());

        JsonNode anonymous = getJson("/api/v1/auth/session", jar);
        assertThat(anonymous.path("authenticated").asBoolean()).isFalse();
        // The device list itself is guarded again.
        client.get()
                .uri(SESSIONS_URI)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void deletingAnUnknownSessionIdentifierStaysNotFound() {
        SeededAccount account = seedAccount();
        CookieJar jar = login(account);

        deleteSession(jar, UUID.randomUUID().toString())
                .expectStatus().isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    // ------------------------------------------------------------------ request helpers

    private WebTestClient.ResponseSpec deleteSession(CookieJar jar, String sessionId) {
        WebTestClient.RequestHeadersSpec<?> request = client.delete()
                .uri(SESSIONS_URI + "/" + sessionId)
                .header(CSRF_HEADER, jar.required(CSRF_COOKIE));
        jar.apply(request);
        return request.exchange();
    }

    private JsonNode getJson(String uri, CookieJar jar) {
        WebTestClient.RequestHeadersSpec<?> request = client.get().uri(uri);
        jar.apply(request);
        return bodyOf(request.exchange().expectStatus().isOk());
    }

    private JsonNode bodyOf(WebTestClient.ResponseSpec response) {
        EntityExchangeResult<String> result = response
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody(String.class)
                .returnResult();
        try {
            return objectMapper.readTree(result.getResponseBody());
        } catch (Exception malformed) {
            throw new AssertionError("malformed JSON: " + result.getResponseBody(), malformed);
        }
    }

    private static String sessionIdOfCurrentMarker(JsonNode sessions) {
        for (JsonNode session : sessions) {
            if (session.path("current").asBoolean()) {
                return session.path("sessionId").asText();
            }
        }
        throw new AssertionError("no current session marker in the listing");
    }

    // ------------------------------------------------------------------ seed helpers

    private record SeededAccount(String username) {}

    private SeededAccount seedAccount() {
        String username = "m12i01-session-" + UUID.randomUUID().toString().substring(0, 8);
        UUID accountId = UUID.randomUUID();
        UUID principalId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO crewscope.principal (id, organization_id, principal_type,"
                        + " display_name, status) VALUES (?, ?, 'USER', ?, 'ACTIVE')",
                principalId, UUID.fromString(ORGANIZATION_ID), "M12-I01 Session Actor");
        jdbc.update("""
                INSERT INTO crewscope.user_account (
                    id, username, username_normalized, email, email_normalized, display_name,
                    status, platform_role, security_version, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', 'USER', 1, 0, NOW(), NOW())
                """,
                accountId, username, username,
                username + "@m12i01.test", username + "@m12i01.test", "M12-I01 Session Actor");
        jdbc.update("""
                INSERT INTO crewscope.login_identity (
                    id, account_id, provider, subject, last_authenticated_at
                ) VALUES (?, ?, 'local', ?, NULL)
                """,
                UUID.randomUUID(), accountId, accountId.toString());
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD);
        jdbc.update("""
                INSERT INTO crewscope.local_credential (
                    id, account_id, password_hash, algorithm, credential_version,
                    password_changed_at, created_at, updated_at
                ) VALUES (?, ?, ?, 'bcrypt', 1, NOW(), NOW(), NOW())
                """,
                UUID.randomUUID(), accountId, hash);
        jdbc.update("""
                INSERT INTO crewscope.account_organization_binding (
                    id, account_id, organization_id, principal_id
                ) VALUES (?, ?, ?, ?)
                """,
                UUID.randomUUID(), accountId, UUID.fromString(ORGANIZATION_ID), principalId);
        return new SeededAccount(username);
    }

    private CookieJar login(SeededAccount account) {
        CookieJar jar = new CookieJar();
        jar.clear();
        WebTestClient.RequestHeadersSpec<?> sessionRequest = client.get().uri("/api/v1/auth/session");
        jar.apply(sessionRequest);
        sessionRequest
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .consumeWith(jar::capture);
        WebTestClient.RequestHeadersSpec<?> loginRequest = client.post()
                .uri("/api/v1/auth/login")
                .header(CSRF_HEADER, jar.required(CSRF_COOKIE))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("identifier", account.username(), "password", PASSWORD));
        jar.apply(loginRequest);
        loginRequest
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("authenticated").isEqualTo(true)
                .consumeWith(jar::capture);
        return jar;
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
            String value = values.get(name);
            if (value == null) {
                throw new AssertionError("cookie " + name + " missing from the jar");
            }
            return value;
        }

        void clear() {
            values.clear();
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
