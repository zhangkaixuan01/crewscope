package io.crewscope.server.security.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.crewscope.infrastructure.testcontainers.SchemaTemplates;
import io.crewscope.server.teambeta.AbstractTeamBetaPostgresRedisContainerIntegrationTest;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.filter.annotation.TypeExcludeFilters;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The sso-only deployment matrix over real HTTP (M12-I01): local password entry points refuse
 * with their dedicated 403 codes before the login defense admits anything, while the federated
 * login keeps working — an enterprise that turned passwords off must not have locked everybody
 * out at the same time.
 */
@Tag("integration")
@ActiveProfiles("team-beta")
@TypeExcludeFilters(OidcSsoOnlyHttpM12I01IntegrationTest.ExcludeTestClasspathConfigurations.class)
@SpringBootTest(
        classes = io.crewscope.server.CrewScopeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OidcSsoOnlyHttpM12I01IntegrationTest
        extends AbstractTeamBetaPostgresRedisContainerIntegrationTest {

    private static final String ORGANIZATION_ID = "0198a475-0831-7000-8000-000000000001";
    private static final String PROVIDER_KEY = "oidc/company";

    @TempDir
    static Path artifactRoot;

    static final Path CODING_ROOT = createDirectory("crewscope-m12i01-ssonly");

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

    /** Private database for this class: a physical clone of the migration chain's tip. */
    private static final SchemaTemplates.Lease LEASE =
            SchemaTemplates.forContainer(POSTGRES).lease(SchemaTemplates.TIP);

    @DynamicPropertySource
    static void ssoOnlyDeploymentProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", LEASE::jdbcUrl);
        registry.add("spring.datasource.username", LEASE::username);
        registry.add("spring.datasource.password", LEASE::password);
        registry.add("crewscope.security.mode", () -> "oidc");
        registry.add("crewscope.security.identity-policy", () -> "sso-only");
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
                () -> "m12i01-ssonly-bootstrap-8e2d7c4b1a9f");
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

    @AfterAll
    static void stopIdp() {
        IDP.close();
    }

    @LocalServerPort
    private int port;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    private WebTestClient client;

    @BeforeEach
    void bindClient() {
        IDP.reset();
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void passwordLoginIsRefusedWithItsDedicatedCode() {
        EntityExchangeResult<byte[]> body = csrfProtectedPost(
                "/api/v1/auth/login",
                Map.of("identifier", "anyone@example.com", "password", "whatever-password"))
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN.value())
                .expectBody(byte[].class)
                .returnResult();
        assertThat(readJson(body).path("code").asText()).isEqualTo("local_login_disabled");
    }

    @Test
    void localRegistrationIsRefusedWithItsDedicatedCode() {
        EntityExchangeResult<byte[]> body = csrfProtectedPost(
                "/api/v1/auth/register",
                Map.of(
                        "username", "anyone@example.com",
                        "email", "anyone@example.com",
                        "password", "whatever-password-123",
                        "displayName", "Anyone"))
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN.value())
                .expectBody(byte[].class)
                .returnResult();
        assertThat(readJson(body).path("code").asText()).isEqualTo("local_registration_disabled");
    }

    @Test
    void federatedLoginStillProvisionsAndEstablishesASession() {
        String subject = "sso-only-subject";
        IDP.subjectClaims(Map.of(
                "sub", subject,
                "name", "SSO Only",
                "preferred_username", "sso.only",
                "email", "sso.only@example.com",
                "email_verified", true));

        // The authorization request (state, nonce) lives in the browser Session, so the
        // callback hop must carry the session cookie the start hop established.
        EntityExchangeResult<byte[]> start = client.get()
                .uri("/oauth2/authorization/company")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FOUND.value())
                .expectBody(byte[].class)
                .returnResult();
        java.util.List<org.springframework.http.ResponseCookie> sessionCookies =
                start.getResponseCookies().get("CREWSCOPE_SESSION");
        assertThat(sessionCookies).as("the start hop must set a session cookie").isNotNull();
        EntityExchangeResult<byte[]> consent = client.get()
                .uri(start.getResponseHeaders().getLocation())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FOUND.value())
                .expectBody(byte[].class)
                .returnResult();
        EntityExchangeResult<byte[]> callback = client.get()
                .uri(consent.getResponseHeaders().getLocation())
                .cookie("CREWSCOPE_SESSION",
                        sessionCookies.get(sessionCookies.size() - 1).getValue())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FOUND.value())
                .expectBody(byte[].class)
                .returnResult();

        assertThat(callback.getResponseHeaders().getLocation()).hasPath("/");
        assertThat(count("""
                SELECT COUNT(*) FROM crewscope.login_identity
                WHERE provider = ? AND subject = ?
                """, PROVIDER_KEY, subject)).isEqualTo(1);
    }

    /** Both refusals must still ride the ordinary CSRF channel: the policy gate is inside. */
    private WebTestClient.ResponseSpec csrfProtectedPost(String path, Map<String, String> body) {
        java.util.List<org.springframework.http.ResponseCookie> cookies = client.get()
                .uri("/api/v1/auth/session")
                .exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class)
                .returnResult()
                .getResponseCookies()
                .get("XSRF-TOKEN");
        assertThat(cookies).isNotNull();
        String token = cookies.get(0).getValue();
        return client.post()
                .uri(path)
                .cookie("XSRF-TOKEN", token)
                .header("X-XSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    private JsonNode readJson(EntityExchangeResult<byte[]> result) {
        try {
            return objectMapper.readTree(result.getResponseBody());
        } catch (Exception malformed) {
            throw new AssertionError("malformed JSON response", malformed);
        }
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
    /** Keeps @Configuration classes hosted inside test sources off the component scan. */
    static final class ExcludeTestClasspathConfigurations extends TypeExcludeFilter {
        private static final Pattern TEST_CLASSPATH_HOST =
                Pattern.compile("^io\\.crewscope\\.(?:infrastructure\\.testsupport\\..*"
                        + "|persistence\\.taskruntime\\.TaskRuntimePackageConfiguration"
                        + "|persistence\\.review\\.ReviewPackageConfiguration"
                        + "|.*(?:TestServer|Test)(?:\\$.*)?)$");

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