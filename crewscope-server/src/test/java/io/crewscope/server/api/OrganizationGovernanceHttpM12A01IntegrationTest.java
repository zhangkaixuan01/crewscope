package io.crewscope.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.crewscope.application.organization.governance.OrganizationAuditRetentionSweeper;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import org.springframework.http.HttpHeaders;
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
 * The M12-A01 governance gate over real HTTP: the bootstrap operator's session carries the
 * ORG_OWNER projection, ORG roles never open a team write surface and team authority never
 * opens an organization one (the plan's orthogonality acceptance), and the three governance
 * workflows — export, account deletion, audit retention — run their full create→confirm→process
 * chains against the live database with their audit facts asserted where they are written
 * synchronously.
 *
 * <p>Shared world, no truncation: every scenario seeds its own uniquely-named accounts under the
 * bootstrap organization and asserts audit rows by event type, so test order never matters. The
 * deletion target's rows are only ever disabled (the V31 credential-history guards), which the
 * SQL assertions pin directly.
 */
@Tag("integration")
@ActiveProfiles("team-beta")
@TypeExcludeFilters(OrganizationGovernanceHttpM12A01IntegrationTest.ExcludeTestClasspathConfigurations.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrganizationGovernanceHttpM12A01IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    /** team-beta provisions exactly this organization and its bootstrap operator. */
    private static final String ORGANIZATION_ID = "0198a475-0831-7000-8000-000000000001";
    private static final String OPERATOR_USERNAME = "crewscope-monitor";
    private static final String BOOTSTRAP_PASSWORD = "m12a01-governance-bootstrap-4b8e2f6a90d3";
    private static final String PASSWORD = "m12a01-governance-password-53c7";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    /** The frozen non-vector export family count (S01 freeze: 24 families, 2 vector-borne). */
    private static final int EXPORT_FAMILY_COUNT = 22;
    /** The frozen deletion coordination checklist: 3 + 8 derived-row targets + identity + backup. */
    private static final int DELETION_STEP_COUNT = 13;

    @TempDir
    static Path artifactRoot;

    /** The coding sandbox roots the deployment contract requires to resolve (never touched). */
    static final Path CODING_ROOT = createDirectory("crewscope-m12a01-coding");

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

    /** Fixed throwaway keys for the fail-closed deployments team-beta enables; nothing asserted
     *  depends on their values, and no real key ever reaches a build log. */
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
    @Autowired private OrganizationAuditRetentionSweeper retentionSweeper;

    private WebTestClient client;

    @BeforeEach
    void bindClient() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void operatorSessionCarriesTheOrganizationProjection() {
        CookieJar operator = loginOperator();

        JsonNode session = getSession(operator);
        assertThat(session.path("authenticated").asBoolean()).isTrue();
        JsonNode organization = session.get("organization");
        assertThat(organization).as("the bootstrap operator must project its organization").isNotNull();
        assertThat(organization.path("organizationId").asText()).isEqualTo(ORGANIZATION_ID);
        assertThat(organization.path("role").asText()).isEqualTo("ORG_OWNER");
        assertThat(textList(session.get("permissions")))
                .contains("org:audit-read", "org:govern", "org:manage-settings");
    }

    @Test
    void orgRolesGrantNoTeamWriteSurface() {
        CookieJar operator = loginOperator();
        UUID adminPrincipal = insertPrincipal("M12A01 Org Admin");
        String adminUsername = "m12a01-admin-" + suffix8();
        seedLocalAccountBoundTo(adminPrincipal, adminUsername);

        postJson("/api/v1/organizations/" + ORGANIZATION_ID + "/role-grants", operator)
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m12a01-grant-" + UUID.randomUUID())
                .bodyValue(Map.of(
                        "principalId", adminPrincipal.toString(),
                        "role", "ORG_ADMIN",
                        "reason", "governance gate coverage"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.ACCEPTED.value());

        CookieJar admin = new CookieJar();
        login(admin, adminUsername, PASSWORD);
        JsonNode session = getSession(admin);
        assertThat(session.path("organization").path("role").asText()).isEqualTo("ORG_ADMIN");
        // A principal with an ORG grant and no team membership projects organization keys only:
        // org authority never leaks team capability vocabulary.
        List<String> permissions = textList(session.get("permissions"));
        assertThat(permissions).isNotEmpty();
        assertThat(permissions).allMatch(permission -> permission.startsWith("org:"));

        // The hard negative: a real team write endpoint refuses the org admin outright.
        UUID teamId = insertReadyTeam(operatorPrincipalId());
        postJson("/api/v1/organizations/" + ORGANIZATION_ID
                        + "/teams/" + teamId + "/invitations", admin)
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m12a01-invite-" + UUID.randomUUID())
                .bodyValue(Map.of(
                        "targetEmail", "stranger@example.com",
                        "targetRole", "TEAM_ADMIN",
                        "expiresInMinutes", 60))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void teamMembershipGrantsNoOrganizationSettingsWrite() {
        UUID teamOwnerPrincipal = insertPrincipal("M12A01 Team Owner");
        String teamOwnerUsername = "m12a01-teamowner-" + suffix8();
        seedLocalAccountBoundTo(teamOwnerPrincipal, teamOwnerUsername);
        insertTeamWithOwnerRole(teamOwnerPrincipal);

        CookieJar teamOwner = new CookieJar();
        login(teamOwner, teamOwnerUsername, PASSWORD);
        JsonNode session = getSession(teamOwner);
        // The strongest team role projects no organization surface at all.
        assertThat(absent(session, "organization")).isTrue();
        assertThat(textList(session.get("permissions")))
                .noneMatch(permission -> permission.startsWith("org:"));

        putJson("/api/v1/organizations/" + ORGANIZATION_ID + "/settings", teamOwner)
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m12a01-settings-" + UUID.randomUUID())
                .header(ApiHeaders.IF_MATCH, "\"0\"")
                .bodyValue(Map.of("auditEventRetentionDays", 90))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN.value());

        // Governance write denials are SECURITY facts: the 403 leaves an audit row behind.
        postJson("/api/v1/organizations/" + ORGANIZATION_ID + "/data-exports", teamOwner)
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m12a01-export-" + UUID.randomUUID())
                .bodyValue(Map.of("includeVectors", false))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(auditCount("ORG_GOVERNANCE_DENIED")).isEqualTo(1);
    }

    @Test
    void exportWorkflowFreezesAManifestAndAuditsEveryDownload() {
        CookieJar operator = loginOperator();
        String base = "/api/v1/organizations/" + ORGANIZATION_ID + "/data-exports";
        String idempotencyKey = "m12a01-export-" + UUID.randomUUID();

        JsonNode created = bodyOf(
                postJson(base, operator)
                        .header(ApiHeaders.IDEMPOTENCY_KEY, idempotencyKey)
                        .bodyValue(Map.of("includeVectors", false))
                        .exchange()
                        .expectStatus().isEqualTo(HttpStatus.ACCEPTED.value()));
        assertThat(created.path("status").asText()).isEqualTo("PENDING");
        assertThat(created.get("items")).hasSize(EXPORT_FAMILY_COUNT);

        // One command id queues at most one job: the replay answers with the receipt alone.
        postJson(base, operator)
                .header(ApiHeaders.IDEMPOTENCY_KEY, idempotencyKey)
                .bodyValue(Map.of("includeVectors", false))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.ACCEPTED.value())
                .expectHeader().valueEquals(ApiHeaders.IDEMPOTENCY_REPLAYED, "true");

        String jobId = created.path("id").asText();
        JsonNode processed = bodyOf(
                postJson(base + "/" + jobId + "/process", operator)
                        .exchange()
                        .expectStatus().isOk());
        assertThat(processed.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(statesOf(processed.get("items"))).isSubsetOf("DONE", "SKIPPED");

        JsonNode fetched = getJson(base + "/" + jobId, operator);
        assertThat(fetched.path("manifest").isObject()).isTrue();
        assertThat(fetched.path("manifest").path("families").isArray()).isTrue();
        assertThat(fetched.path("manifest").path("families").size()).isPositive();

        WebTestClient.RequestHeadersSpec<?> artifactsRequest =
                client.get().uri(base + "/" + jobId + "/artifacts");
        operator.apply(artifactsRequest);
        EntityExchangeResult<String> artifacts = artifactsRequest
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(
                        MediaType.parseMediaType("application/vnd.crewscope.governance-export+json"))
                .expectHeader().valueMatches(HttpHeaders.CONTENT_DISPOSITION, "attachment.*")
                .expectBody(String.class)
                .returnResult();
        JsonNode artifactsBody = readTree(artifacts.getResponseBody());
        assertThat(artifactsBody.path("manifest").isObject()).isTrue();
        assertThat(artifactsBody.path("artifacts").isArray()).isTrue();

        // The download face writes its own security audit fact, synchronously.
        assertThat(auditCount("ORG_EXPORT_DOWNLOADED")).isPositive();
    }

    @Test
    void deletionWorkflowConfirmsThenDisablesEveryIdentitySurface() {
        CookieJar operator = loginOperator();
        UUID targetPrincipal = insertPrincipal("M12A01 Deletion Target");
        String targetUsername = "m12a01-target-" + suffix8();
        UUID targetAccount = seedLocalAccountBoundTo(targetPrincipal, targetUsername);
        String base = "/api/v1/organizations/" + ORGANIZATION_ID;

        JsonNode precheck = bodyOf(
                getWithCookies(base + "/deletion-precheck?accountId=" + targetAccount, operator)
                        .exchange()
                        .expectStatus().isOk());
        assertThat(precheck.path("inFlightTaskExecutions").asLong()).isZero();
        assertThat(precheck.path("activeResponsibilities").asLong()).isZero();
        assertThat(absent(precheck, "openReservations")).isTrue();

        JsonNode created = bodyOf(
                postJson(base + "/account-deletions", operator)
                        .header(ApiHeaders.IDEMPOTENCY_KEY, "m12a01-deletion-" + UUID.randomUUID())
                        .bodyValue(Map.of(
                                "accountId", targetAccount.toString(),
                                "reason", "offboarding the departing operator after the audit finding closed"))
                        .exchange()
                        .expectStatus().isEqualTo(HttpStatus.ACCEPTED.value()));
        assertThat(created.path("status").asText()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(created.get("items")).hasSize(DELETION_STEP_COUNT);

        String jobId = created.path("id").asText();
        postJson(base + "/account-deletions/" + jobId + "/process", operator)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());

        JsonNode confirmed = bodyOf(
                postJson(base + "/account-deletions/" + jobId + "/confirm", operator)
                        .header(ApiHeaders.IF_MATCH, "\"0\"")
                        .bodyValue(Map.of("confirmationToken", targetUsername))
                        .exchange()
                        .expectStatus().isOk());
        assertThat(confirmed.path("status").asText()).isEqualTo("CONFIRMED");

        JsonNode processed = bodyOf(
                postJson(base + "/account-deletions/" + jobId + "/process", operator)
                        .exchange()
                        .expectStatus().isOk());
        assertThat(processed.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(statesOf(processed.get("items"))).containsOnly("DONE");

        // The terminal step disables every login surface in place; the V31 credential-history
        // guards keep the rows (and the local password row) as history.
        assertThat(jdbc.queryForObject(
                "SELECT status FROM crewscope.login_identity WHERE account_id = ?",
                String.class, targetAccount)).isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM crewscope.account_organization_binding WHERE account_id = ?",
                String.class, targetAccount)).isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM crewscope.principal WHERE id = ?",
                String.class, targetPrincipal)).isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM crewscope.user_account WHERE id = ?",
                String.class, targetAccount)).isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.local_credential WHERE account_id = ?",
                Integer.class, targetAccount)).isEqualTo(1);

        // Every settled step wrote its governance audit fact before the response returned.
        assertThat(auditCount("ORG_DELETION_STEP_EXECUTED"))
                .isGreaterThanOrEqualTo(DELETION_STEP_COUNT);
    }

    @Test
    void retentionDefaultsToForeverUntilAWindowIsConfigured() {
        CookieJar operator = loginOperator();
        String settingsUri = "/api/v1/organizations/" + ORGANIZATION_ID + "/settings";

        JsonNode initial = getJson(settingsUri, operator);
        assertThat(absent(initial, "auditEventRetentionDays")).isTrue();
        assertThat(retentionSweeper.runOnce()).isZero();

        putJson(settingsUri, operator)
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m12a01-retention-" + UUID.randomUUID())
                .header(ApiHeaders.IF_MATCH, "\"0\"")
                .bodyValue(Map.of("auditEventRetentionDays", 30))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.ACCEPTED.value());
        assertThat(getJson(settingsUri, operator).path("auditEventRetentionDays").asInt())
                .isEqualTo(30);

        UUID expiredOperational = UUID.randomUUID();
        UUID expiredSecurity = UUID.randomUUID();
        insertAuditEvent(expiredOperational, "TEAM_CREATED", "TEAM",
                Instant.parse("2026-01-01T00:00:00Z"));
        insertAuditEvent(expiredSecurity, "ORG_EXPORT_DOWNLOADED", "SECURITY",
                Instant.parse("2026-01-01T00:00:00Z"));

        // The sweep deletes the expired operational fact, spares the SECURITY one regardless of
        // age, and writes its own evidence in the same transaction.
        assertThat(retentionSweeper.runOnce()).isEqualTo(1);
        assertThat(auditRowPresent(expiredOperational)).isFalse();
        assertThat(auditRowPresent(expiredSecurity)).isTrue();
        assertThat(auditCount("ORG_RETENTION_SWEEP_COMPLETED")).isPositive();
    }

    // ------------------------------------------------------------------ HTTP plumbing

    private CookieJar loginOperator() {
        CookieJar operator = new CookieJar();
        login(operator, OPERATOR_USERNAME, BOOTSTRAP_PASSWORD);
        return operator;
    }

    /** Anonymous session (XSRF token) → real login endpoint with the CSRF header → the rotated
     *  session cookie every guarded call then carries. */
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

    private JsonNode getSession(CookieJar jar) {
        return getJson("/api/v1/auth/session", jar);
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

    private WebTestClient.RequestBodySpec postJson(String uri, CookieJar jar) {
        WebTestClient.RequestBodySpec request = client.post()
                .uri(uri)
                .header(CSRF_HEADER, jar.required(CSRF_COOKIE))
                .contentType(MediaType.APPLICATION_JSON);
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

    private static List<String> textList(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private static List<String> statesOf(JsonNode items) {
        List<String> states = new ArrayList<>();
        items.forEach(item -> states.add(item.path("state").asText()));
        return states;
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

    /** user_account → login_identity(local) → local_credential(bcrypt) → binding to the given
     *  principal, all through SQL: the login path under test stays the real one. */
    private UUID seedLocalAccountBoundTo(UUID principalId, String username) {
        UUID accountId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crewscope.user_account (
                    id, username, username_normalized, email, email_normalized, display_name,
                    status, platform_role, security_version, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', 'USER', 1, 0, NOW(), NOW())
                """,
                accountId, username, username,
                username + "@m12a01.test", username + "@m12a01.test", "M12A01 Governance Actor");
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

    /** A READY team (owner member + default workspace wired, the Q01 patch pattern) the invited
     *  principal does not belong to: the team resolves, then the membership check refuses the
     *  org admin — the orthogonality negative needs a real team, not a 404. */
    private UUID insertReadyTeam(UUID ownerPrincipalId) {
        UUID teamId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status)"
                        + " VALUES (?, ?, ?, 'ACTIVE')",
                teamId, UUID.fromString(ORGANIZATION_ID),
                "M12A01 Invite Target " + suffix8());
        UUID memberId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crewscope.team_member (
                    id, organization_id, team_id, user_principal_id, status, join_method, joined_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', 'BOOTSTRAP', NOW())
                """,
                memberId, UUID.fromString(ORGANIZATION_ID), teamId, ownerPrincipalId);
        UUID workspaceId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status)
                VALUES (?, ?, ?, 'TEAM', 'M12A01 Invite Space', 'ACTIVE')
                """,
                workspaceId, UUID.fromString(ORGANIZATION_ID), teamId);
        jdbc.update(
                "UPDATE crewscope.team SET owner_member_id = ?, default_workspace_id = ?"
                        + " WHERE id = ?",
                memberId, workspaceId, teamId);
        return teamId;
    }

    /** The bootstrap operator's principal id — the stable owner every seeded team hangs off. */
    private UUID operatorPrincipalId() {
        return jdbc.queryForObject(
                """
                SELECT binding.principal_id
                  FROM crewscope.account_organization_binding binding
                  JOIN crewscope.user_account account ON account.id = binding.account_id
                 WHERE account.username = ?
                """,
                UUID.class, OPERATOR_USERNAME);
    }

    /** The four hand-fed team tables that make the principal a TEAM_OWNER — the strongest team
     *  vocabulary — without granting any organization surface. */
    private void insertTeamWithOwnerRole(UUID ownerPrincipalId) {
        UUID teamId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status)"
                        + " VALUES (?, ?, ?, 'ACTIVE')",
                teamId, UUID.fromString(ORGANIZATION_ID),
                "M12A01 Team " + suffix8());
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

    private void insertAuditEvent(
            UUID eventId, String eventType, String category, Instant occurredAt) {
        jdbc.update(
                """
                INSERT INTO crewscope.audit_event (
                    event_id, organization_id, actor_type, event_type, event_category,
                    subject_type, outcome, correlation_id, schema_version, occurred_at, payload)
                VALUES (?, ?, 'USER', ?, ?, 'TEAM', 'SUCCEEDED', ?, '1', ?,
                        CAST('{}' AS JSONB))
                """,
                eventId, UUID.fromString(ORGANIZATION_ID), eventType, category,
                UUID.randomUUID(), occurredAt.atOffset(ZoneOffset.UTC));
    }

    private int auditCount(String eventType) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.audit_event"
                        + " WHERE organization_id = ? AND event_type = ?",
                Integer.class, UUID.fromString(ORGANIZATION_ID), eventType);
    }

    private boolean auditRowPresent(UUID eventId) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.audit_event WHERE event_id = ?",
                        Integer.class, eventId)
                > 0;
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
