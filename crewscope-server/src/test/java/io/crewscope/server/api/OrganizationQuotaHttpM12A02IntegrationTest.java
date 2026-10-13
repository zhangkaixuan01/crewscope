package io.crewscope.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
 * The M12-A02 quota configuration gate over real HTTP. Shared world, no truncation: the quota
 * row is the organization's single mutable fact, so every test opens by resetting both
 * dimensions to null and asserts the change it makes against the version it read.
 *
 * <p>The admission hook's own arbitration (count boundary, ledger accumulation, rollback
 * atomicity) is pinned by the application and infrastructure suites; this gate pins the
 * configuration surface — capabilities, optimistic If-Match, idempotent replay, limit
 * guardrails and the reviewed audit fact.
 */
@Tag("integration")
@ActiveProfiles("team-beta")
@TypeExcludeFilters(OrganizationQuotaHttpM12A02IntegrationTest.ExcludeTestClasspathConfigurations.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrganizationQuotaHttpM12A02IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    /** team-beta provisions exactly this organization and its bootstrap operator. */
    private static final String ORGANIZATION_ID = "0198a475-0831-7000-8000-000000000001";
    private static final String OPERATOR_USERNAME = "crewscope-monitor";
    private static final String BOOTSTRAP_PASSWORD = "m12a01-governance-bootstrap-4b8e2f6a90d3";
    private static final String PASSWORD = "m12a02-quota-password-53c7";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    @TempDir
    static Path artifactRoot;

    /** The coding sandbox roots the deployment contract requires to resolve (never touched). */
    static final Path CODING_ROOT = createDirectory("crewscope-m12a02-coding");

    private static final String TEST_CREDENTIAL_KEYS = "v1=7fxmDni7ah4GVkFpXlFp/WJ9Xvlk4lVlEuMO5YMmYPw=";
    private static final String TEST_ACTIVITY_KEY = "qy+UtChs7KTKHsPDCKXhhm41lKIf8/3GaSKmClfeqwM=";
    private static final String TEST_LOGIN_DEFENSE_KEY = "gL9T+TUAof/s+bhqjftsy17/a4NSctPXA+/6pIZzA1o=";
    private static final String TEST_TASK_TOKEN_KEY = "u1WSa5SkDSf8N10uJ2sJE72U/u+ZDGrjaffROM17X5U=";
    private static final String TEST_INVITATION_KEY = "asKlHnh1ld0Le3fJyzEj1VDm2poi7ZMyuzisUyIpg6k=";

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
        registry.add("crewscope.security.bootstrap.password", () -> BOOTSTRAP_PASSWORD);
    }

    @LocalServerPort
    private int port;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    private WebTestClient client;
    private String quotaUri;

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
        quotaUri = "/api/v1/organizations/" + ORGANIZATION_ID + "/quota";
    }

    @Test
    void quotaDefaultsToUnconfiguredUntilTheFirstWrite() {
        CookieJar operator = loginOperator();
        resetQuota();

        JsonNode initial = getJson(quotaUri, operator);
        assertThat(absent(initial, "monthlyTaskIntakeLimit")).isTrue();
        assertThat(absent(initial, "monthlyTokenBudget")).isTrue();
        assertThat(initial.path("version").asLong()).isZero();

        putJson(quotaUri, operator)
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m12a02-first-" + UUID.randomUUID())
                .header(ApiHeaders.IF_MATCH, "\"0\"")
                .bodyValue(Map.of("monthlyTaskIntakeLimit", 5, "monthlyTokenBudget", 1_000_000L))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.ACCEPTED.value());

        JsonNode configured = getJson(quotaUri, operator);
        assertThat(configured.path("monthlyTaskIntakeLimit").asInt()).isEqualTo(5);
        assertThat(configured.path("monthlyTokenBudget").asLong()).isEqualTo(1_000_000L);
        // First write materializes at version 0; the ETag grows only with later changes.
        assertThat(configured.path("version").asLong()).isZero();
        // Configuring the budget never touches the ledger: reservations are admission-only.
        assertThat(ledgerRowCount()).isZero();
    }

    @Test
    void quotaReadsAndWritesRequireTheOrganizationSettingsCapability() {
        CookieJar operator = loginOperator();
        resetQuota();
        UUID outsiderPrincipal = insertPrincipal("M12A02 Team Owner");
        String outsiderUsername = "m12a02-teamowner-" + suffix8();
        seedLocalAccountBoundTo(outsiderPrincipal, outsiderUsername);
        insertTeamWithOwnerRole(outsiderPrincipal);
        CookieJar outsider = new CookieJar();
        login(outsider, outsiderUsername, PASSWORD);

        int eventsBefore = auditCount("ORGANIZATION_QUOTA_CONFIGURATION_CHANGED");
        getWithCookies(quotaUri, outsider)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN.value());
        putJson(quotaUri, outsider)
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m12a02-denied-" + UUID.randomUUID())
                .header(ApiHeaders.IF_MATCH, "\"0\"")
                .bodyValue(Map.of("monthlyTaskIntakeLimit", 5))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN.value());
        // The denial leaves no configuration row and no reviewed fact behind.
        assertThat(quotaRowCount()).isZero();
        assertThat(auditCount("ORGANIZATION_QUOTA_CONFIGURATION_CHANGED")).isEqualTo(eventsBefore);
    }

    @Test
    void staleIfMatchConflictsAndSubOneLimitsReject() {
        CookieJar operator = loginOperator();
        resetQuota();
        putJson(quotaUri, operator)
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m12a02-seed-" + UUID.randomUUID())
                .header(ApiHeaders.IF_MATCH, "\"0\"")
                .bodyValue(Map.of("monthlyTaskIntakeLimit", 5, "monthlyTokenBudget", 1_000_000L))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.ACCEPTED.value());

        // The row now sits at version 0: an If-Match for a phantom earlier version conflicts.
        putJson(quotaUri, operator)
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m12a02-stale-" + UUID.randomUUID())
                .header(ApiHeaders.IF_MATCH, "\"9\"")
                .bodyValue(Map.of("monthlyTaskIntakeLimit", 6))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT.value());

        // Zero is a configuration error, not "block everything".
        putJson(quotaUri, operator)
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m12a02-zero-intake-" + UUID.randomUUID())
                .header(ApiHeaders.IF_MATCH, "\"0\"")
                .bodyValue(Map.of("monthlyTaskIntakeLimit", 0))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_REQUEST.value());
        putJson(quotaUri, operator)
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m12a02-zero-budget-" + UUID.randomUUID())
                .header(ApiHeaders.IF_MATCH, "\"0\"")
                .bodyValue(Map.of("monthlyTokenBudget", 0L))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_REQUEST.value());

        JsonNode unchanged = getJson(quotaUri, operator);
        assertThat(unchanged.path("monthlyTaskIntakeLimit").asInt()).isEqualTo(5);
        assertThat(unchanged.path("monthlyTokenBudget").asLong()).isEqualTo(1_000_000L);
    }

    @Test
    void quotaChangesReplayTheirReceiptAndAuditOneReviewedFact() {
        CookieJar operator = loginOperator();
        resetQuota();
        String idempotencyKey = "m12a02-replay-" + UUID.randomUUID();
        int eventsBefore = auditCount("ORGANIZATION_QUOTA_CONFIGURATION_CHANGED");

        JsonNode first = bodyOf(
                putJson(quotaUri, operator)
                        .header(ApiHeaders.IDEMPOTENCY_KEY, idempotencyKey)
                        .header(ApiHeaders.IF_MATCH, "\"0\"")
                        .bodyValue(Map.of("monthlyTaskIntakeLimit", 3, "monthlyTokenBudget", 2_000_000L))
                        .exchange()
                        .expectStatus().isEqualTo(HttpStatus.ACCEPTED.value()));

        EntityExchangeResult<String> replay = putJson(quotaUri, operator)
                .header(ApiHeaders.IDEMPOTENCY_KEY, idempotencyKey)
                .header(ApiHeaders.IF_MATCH, "\"0\"")
                .bodyValue(Map.of("monthlyTaskIntakeLimit", 3, "monthlyTokenBudget", 2_000_000L))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.ACCEPTED.value())
                .expectHeader().valueEquals(ApiHeaders.IDEMPOTENCY_REPLAYED, "true")
                .expectBody(String.class)
                .returnResult();
        assertThat(readTree(replay.getResponseBody()).path("commandId").asText())
                .isEqualTo(first.path("commandId").asText());

        // Exactly one reviewed fact, carrying both after-values at its payload.
        assertThat(auditCount("ORGANIZATION_QUOTA_CONFIGURATION_CHANGED"))
                .isEqualTo(eventsBefore + 1);
        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM crewscope.audit_event"
                        + " WHERE organization_id = ? AND event_type = ?"
                        + " ORDER BY occurred_at DESC LIMIT 1",
                String.class, UUID.fromString(ORGANIZATION_ID),
                "ORGANIZATION_QUOTA_CONFIGURATION_CHANGED");
        JsonNode audited = readTree(payload);
        assertThat(audited.path("monthlyTaskIntakeLimitAfter").asInt()).isEqualTo(3);
        assertThat(audited.path("monthlyTokenBudgetAfter").asLong()).isEqualTo(2_000_000L);
    }

    @Test
    void clearingBothDimensionsReturnsTheConfiguredRowToUnconfigured() {
        CookieJar operator = loginOperator();
        resetQuota();
        putJson(quotaUri, operator)
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m12a02-clear-seed-" + UUID.randomUUID())
                .header(ApiHeaders.IF_MATCH, "\"0\"")
                .bodyValue(Map.of("monthlyTaskIntakeLimit", 5, "monthlyTokenBudget", 1_000_000L))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.ACCEPTED.value());

        JsonNode before = getJson(quotaUri, operator);
        putJson(quotaUri, operator)
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m12a02-clear-" + UUID.randomUUID())
                .header(ApiHeaders.IF_MATCH, "\"" + before.path("version").asLong() + "\"")
                .bodyValue(Map.of())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.ACCEPTED.value());

        JsonNode cleared = getJson(quotaUri, operator);
        assertThat(absent(cleared, "monthlyTaskIntakeLimit")).isTrue();
        assertThat(absent(cleared, "monthlyTokenBudget")).isTrue();
        // The row stays materialized (version advanced): null dimensions are unconfigured, not absent.
        assertThat(cleared.path("version").asLong()).isGreaterThan(before.path("version").asLong());
        assertThat(quotaRowCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ quota world reset

    /**
     * The organization quota row is single and shared across tests. A PUT-based reset would
     * leave the row materialized forever (every later first-write If-Match "0" then 409s), so
     * the reset deletes the row directly — restoring the genuine no-row default state. Receipt
     * and audit history stay (their assertions are all relative to a per-test baseline).
     */
    private void resetQuota() {
        jdbc.update(
                "DELETE FROM crewscope.organization_quota_configuration WHERE organization_id = ?",
                UUID.fromString(ORGANIZATION_ID));
    }

    private int quotaRowCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.organization_quota_configuration"
                        + " WHERE organization_id = ?",
                Integer.class, UUID.fromString(ORGANIZATION_ID));
    }

    private int ledgerRowCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.organization_quota_token_ledger"
                        + " WHERE organization_id = ?",
                Integer.class, UUID.fromString(ORGANIZATION_ID));
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

    private JsonNode getJson(String uri, CookieJar jar) {
        return bodyOf(getWithCookies(uri, jar)
                .exchange()
                .expectStatus().isOk());
    }

    private WebTestClient.RequestHeadersSpec<?> getWithCookies(String uri, CookieJar jar) {
        WebTestClient.RequestHeadersSpec<?> request = client.get().uri(uri);
        jar.apply(request);
        return request;
    }

    private WebTestClient.RequestBodySpec putJson(String uri, CookieJar jar) {
        WebTestClient.RequestBodySpec request = client.put()
                .uri(uri)
                .header(CSRF_HEADER, jar.required(CSRF_COOKIE))
                .contentType(MediaType.APPLICATION_JSON);
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

    private static boolean absent(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull();
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

    private UUID seedLocalAccountBoundTo(UUID principalId, String username) {
        UUID accountId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crewscope.user_account (
                    id, username, username_normalized, email, email_normalized, display_name,
                    status, platform_role, security_version, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', 'USER', 1, 0, NOW(), NOW())
                """,
                accountId, username, username,
                username + "@m12a02.test", username + "@m12a02.test", "M12A02 Quota Actor");
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
        return accountId;
    }

    /** The four hand-fed team tables that make the principal a TEAM_OWNER — the strongest team
     *  vocabulary — without granting any organization surface. */
    private void insertTeamWithOwnerRole(UUID ownerPrincipalId) {
        UUID teamId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status)"
                        + " VALUES (?, ?, ?, 'ACTIVE')",
                teamId, UUID.fromString(ORGANIZATION_ID),
                "M12A02 Team " + suffix8());
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

    private int auditCount(String eventType) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.audit_event"
                        + " WHERE organization_id = ? AND event_type = ?",
                Integer.class, UUID.fromString(ORGANIZATION_ID), eventType);
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
