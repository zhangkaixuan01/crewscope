package io.crewscope.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.crewscope.infrastructure.testcontainers.SchemaTemplates;
import io.crewscope.server.teambeta.AbstractTeamBetaPostgresRedisContainerIntegrationTest;
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
import org.springframework.http.CacheControl;
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
 * The M12-I03 SLI gate over real HTTP: the six-indicator contract shape, the
 * AUDIT_READ_ORG capability (an ORG_AUDITOR reads this but not the settings-flavoured
 * quota surface — the capability split this endpoint exists to prove), and the
 * malformed-identifier rejection. Shared world, no truncation: every structural
 * assertion is numeric or enum-valued, never a count of another test's data.
 */
@Tag("integration")
@ActiveProfiles("team-beta")
@TypeExcludeFilters(OrganizationSliHttpIT.ExcludeTestClasspathConfigurations.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrganizationSliHttpIT extends AbstractTeamBetaPostgresRedisContainerIntegrationTest {

    /** team-beta provisions exactly this organization and its bootstrap operator. */
    private static final String ORGANIZATION_ID = "0198a475-0831-7000-8000-000000000001";
    private static final String OPERATOR_USERNAME = "crewscope-monitor";
    private static final String BOOTSTRAP_PASSWORD = "m12a01-governance-bootstrap-4b8e2f6a90d3";
    private static final String PASSWORD = "m12i03-sli-password-7c1a";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    @TempDir
    static Path artifactRoot;

    /** The coding sandbox roots the deployment contract requires to resolve (never touched). */
    static final Path CODING_ROOT = createDirectory("crewscope-m12i03-coding");

    private static final String TEST_CREDENTIAL_KEYS = "v1=7fxmDni7ah4GVkFpXlFp/WJ9Xvlk4lVlEuMO5YMmYPw=";
    private static final String TEST_ACTIVITY_KEY = "qy+UtChs7KTKHsPDCKXhhm41lKIf8/3GaSKmClfeqwM=";
    private static final String TEST_LOGIN_DEFENSE_KEY = "gL9T+TUAof/s+bhqjftsy17/a4NSctPXA+/6pIZzA1o=";
    private static final String TEST_TASK_TOKEN_KEY = "u1WSa5SkDSf8N10uJ2sJE72U/u+ZDGrjaffROM17X5U=";
    private static final String TEST_INVITATION_KEY = "asKlHnh1ld0Le3fJyzEj1VDm2poi7ZMyuzisUyIpg6k=";

    /** Private database for this class: a physical clone of the migration chain's tip. */
    private static final SchemaTemplates.Lease LEASE =
            SchemaTemplates.forContainer(POSTGRES).lease(SchemaTemplates.TIP);

    @DynamicPropertySource
    static void testDeploymentProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", LEASE::jdbcUrl);
        registry.add("spring.datasource.username", LEASE::username);
        registry.add("spring.datasource.password", LEASE::password);
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
        registry.add("crewscope.security.bootstrap.password", () -> BOOTSTRAP_PASSWORD);
    }

    @LocalServerPort
    private int port;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    private WebTestClient client;
    private String sliUri;

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
        sliUri = "/api/v1/organizations/" + ORGANIZATION_ID + "/sli";
    }

    @Test
    void sliPresentsAllSixIndicatorsWithHonestWindows() {
        CookieJar operator = loginOperator();
        JsonNode view = bodyOf(getWithCookies(sliUri, operator)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().cacheControl(CacheControl.noStore()));

        assertThat(view.path("organizationId").asText()).isEqualTo(ORGANIZATION_ID);
        assertThat(view.path("generatedAt").asText()).isNotBlank();
        assertThat(view.path("reportingZone").asText()).isEqualTo("Asia/Shanghai");
        List<String> ids = new java.util.ArrayList<>();
        view.path("metrics").forEach(metric -> ids.add(metric.path("id").asText()));
        assertThat(ids).containsExactly(
                "api.latency.p95",
                "agent.first.token",
                "queueing.latency",
                "execution.success.rate",
                "notification.delivery.rate",
                "knowledge.retrieval.latency.p95");

        for (JsonNode metric : view.path("metrics")) {
            assertThat(metric.path("objective").asText()).contains("ADR-037");
            assertThat(metric.path("dataSource").asText()).isNotBlank();
            assertThat(metric.path("windows").size()).isGreaterThan(0);
            for (JsonNode window : metric.path("windows")) {
                assertThat(window.path("window").asText()).isIn(
                        "process_lifetime", "rolling_28d", "current_day", "snapshot");
                assertThat(window.path("availability").asText()).isIn("ok", "degraded", "no_data");
                if ("degraded".equals(window.path("availability").asText())) {
                    assertThat(window.path("value").isNull()).isTrue();
                }
                if ("no_data".equals(window.path("availability").asText())) {
                    assertThat(window.path("value").isNull()).isTrue();
                }
            }
        }
        // The honest split, stated structurally: Timer metrics carry a degraded 28-day
        // entry; table-backed rates carry both real windows.
        JsonNode api = metricById(view, "api.latency.p95");
        assertThat(windowKinds(api)).contains("process_lifetime", "rolling_28d");
        assertThat(windowByKind(api, "rolling_28d").path("availability").asText())
                .isEqualTo("degraded");
        JsonNode execution = metricById(view, "execution.success.rate");
        assertThat(windowKinds(execution)).containsExactly("rolling_28d", "current_day");
        JsonNode queueing = metricById(view, "queueing.latency");
        assertThat(windowKinds(queueing)).contains("snapshot");
        JsonNode snapshot = windowByKind(queueing, "snapshot");
        assertThat(snapshot.path("scope").asText()).isEqualTo("organization");
        assertThat(snapshot.path("unit").asText()).isEqualTo("seconds");
        assertThat(snapshot.path("value").isNumber()).isTrue();
    }

    @Test
    void sliReadRequiresTheOrganizationAuditCapability() {
        UUID outsiderPrincipal = insertPrincipal("M12I03 Team Owner");
        String outsiderUsername = "m12i03-teamowner-" + suffix8();
        seedLocalAccountBoundTo(outsiderPrincipal, outsiderUsername);
        insertTeamWithOwnerRole(outsiderPrincipal);
        CookieJar outsider = new CookieJar();
        login(outsider, outsiderUsername, PASSWORD);

        // The strongest team vocabulary grants no organization surface at all.
        getWithCookies(sliUri, outsider)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void malformedOrganizationIdIsRejected() {
        CookieJar operator = loginOperator();
        getWithCookies("/api/v1/organizations/not-a-uuid/sli", operator)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    void auditorReadsSliButNotTheQuotaConfigurationSurface() {
        UUID auditorPrincipal = insertPrincipal("M12I03 Auditor");
        String auditorUsername = "m12i03-auditor-" + suffix8();
        seedLocalAccountBoundTo(auditorPrincipal, auditorUsername);
        jdbc.update(
                """
                INSERT INTO crewscope.organization_role_grant (
                    id, organization_id, principal_id, role, status,
                    granted_by_principal_id, version,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, 'ORG_AUDITOR', 'ACTIVE', ?, 0, NOW(), ?, NOW(), ?)
                """,
                UUID.randomUUID(), UUID.fromString(ORGANIZATION_ID), auditorPrincipal,
                auditorPrincipal, auditorPrincipal, auditorPrincipal);
        CookieJar auditor = new CookieJar();
        login(auditor, auditorUsername, PASSWORD);

        bodyOf(getWithCookies(sliUri, auditor)
                .exchange()
                .expectStatus().isOk());
        // The same principal holds no SETTINGS_READ: the capability split is real.
        getWithCookies("/api/v1/organizations/" + ORGANIZATION_ID + "/quota", auditor)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    // ------------------------------------------------------------------ JSON helpers

    private JsonNode metricById(JsonNode view, String id) {
        for (JsonNode metric : view.path("metrics")) {
            if (id.equals(metric.path("id").asText())) {
                return metric;
            }
        }
        throw new AssertionError("metric " + id + " missing from the view");
    }

    private List<String> windowKinds(JsonNode metric) {
        List<String> kinds = new java.util.ArrayList<>();
        metric.path("windows").forEach(window -> kinds.add(window.path("window").asText()));
        return kinds;
    }

    private JsonNode windowByKind(JsonNode metric, String window) {
        for (JsonNode candidate : metric.path("windows")) {
            if (window.equals(candidate.path("window").asText())) {
                return candidate;
            }
        }
        throw new AssertionError("window " + window + " missing from " + metric.path("id"));
    }

    // ------------------------------------------------------------------ HTTP plumbing

    private CookieJar loginOperator() {
        CookieJar operator = new CookieJar();
        login(operator, OPERATOR_USERNAME, BOOTSTRAP_PASSWORD);
        return operator;
    }

    private void login(CookieJar jar, String identifier, String password) {
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
                .bodyValue(Map.of("identifier", identifier, "password", password));
        jar.apply(loginRequest);
        loginRequest
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("authenticated").isEqualTo(true)
                .consumeWith(jar::capture);
    }

    private WebTestClient.RequestHeadersSpec<?> getWithCookies(String uri, CookieJar jar) {
        WebTestClient.RequestHeadersSpec<?> request = client.get().uri(uri);
        jar.apply(request);
        return request;
    }

    private JsonNode bodyOf(WebTestClient.ResponseSpec response) {
        EntityExchangeResult<String> result = response
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody(String.class)
                .returnResult();
        return readTree(result.getResponseBody());
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception malformed) {
            throw new AssertionError("malformed JSON: " + body, malformed);
        }
    }

    // ------------------------------------------------------------------ seed helpers

    private static String suffix8() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private UUID insertPrincipal(String displayName) {
        UUID principalId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO crewscope.principal (id, organization_id, principal_type,"
                        + " display_name, status) VALUES (?, ?, 'USER', ?, 'ACTIVE')",
                principalId, UUID.fromString(ORGANIZATION_ID), displayName);
        return principalId;
    }

    private void seedLocalAccountBoundTo(UUID principalId, String username) {
        UUID accountId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crewscope.user_account (
                    id, username, username_normalized, email, email_normalized, display_name,
                    status, platform_role, security_version, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', 'USER', 1, 0, NOW(), NOW())
                """,
                accountId, username, username,
                username + "@m12i03.test", username + "@m12i03.test", "M12I03 SLI Actor");
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
    }

    /** The four hand-fed team tables that make the principal a TEAM_OWNER — the strongest
     *  team vocabulary — without granting any organization surface. */
    private void insertTeamWithOwnerRole(UUID ownerPrincipalId) {
        UUID teamId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status)"
                        + " VALUES (?, ?, ?, 'ACTIVE')",
                teamId, UUID.fromString(ORGANIZATION_ID),
                "M12I03 Team " + suffix8());
        UUID memberId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crewscope.team_member (
                    id, organization_id, team_id, user_principal_id, status, join_method, joined_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', 'BOOTSTRAP', NOW())
                """,
                memberId, UUID.fromString(ORGANIZATION_ID), teamId, ownerPrincipalId);
        UUID roleId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crewscope.team_role (
                    id, organization_id, team_id, role_key, name, built_in, permissions,
                    scope_type, status)
                VALUES (?, ?, ?, 'TEAM_OWNER', 'Team Owner', TRUE, CAST('[]' AS JSONB),
                        'TEAM', 'ACTIVE')
                """,
                roleId, UUID.fromString(ORGANIZATION_ID), teamId);
        jdbc.update("""
                INSERT INTO crewscope.team_member_role (
                    id, organization_id, team_id, team_member_id, team_role_id, scope_type,
                    granted_by_principal_id, status)
                VALUES (?, ?, ?, ?, ?, 'TEAM', ?, 'ACTIVE')
                """,
                UUID.randomUUID(), UUID.fromString(ORGANIZATION_ID), teamId, memberId, roleId,
                ownerPrincipalId);
    }

    /** WebTestClient keeps no cookie jar; the login dance and every guarded call share one. */
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
