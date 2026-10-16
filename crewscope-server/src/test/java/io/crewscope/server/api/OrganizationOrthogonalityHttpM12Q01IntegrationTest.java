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
 * The M12-Q01 cross-cutting orthogonality gate over real HTTP: one matrix test per direction of
 * the organization/team authority split, family-wide rather than endpoint-picked.
 *
 * <ul>
 *   <li><b>Team authority never opens any organization surface</b> — the strongest team vocabulary
 *       (TEAM_OWNER, no organization grant) is refused by every organization endpoint family:
 *       settings, role grants, data exports, SLI, quota. A01/A02/I03 each pinned one or two of
 *       these; this family rescan is the hardening regression that survives an endpoint being
 *       re-scoped later.
 *   <li><b>The auditor reads audit facts and nothing else</b> — ORG_AUDITOR holds
 *       AUDIT_READ_ORG alone: the audit-fact surfaces (SLI, the role-grant list) answer 200
 *       while every settings-flavoured read and every governance write is refused, and the write
 *       denial is a SECURITY fact (an ORG_GOVERNANCE_DENIED audit row appears).
 *   <li><b>Organization authority never opens a team surface</b> — an ORG_ADMIN with no team
 *       identity gets an empty team directory (no team existence is enumerable), is refused by
 *       every team read and write endpoint for a real team, and the session projection carries
 *       organization keys only (the A01 negative, rescanned).
 * </ul>
 *
 * <p><b>Known boundary, pinned as-is:</b> team point reads resolve the team before the membership
 * check, so a non-member sees 403 for an existing team and 404 for a random UUID. The differential
 * only widens what the directory already refuses to enumerate, and changing the order would be a
 * behaviour change outside this work package; the boundary is recorded in the M12-Q01 matrix.
 *
 * <p>Shared world, no truncation: every scenario seeds uniquely-named accounts under the bootstrap
 * organization, so test order never matters.
 */
@Tag("integration")
@ActiveProfiles("team-beta")
@TypeExcludeFilters(OrganizationOrthogonalityHttpM12Q01IntegrationTest.ExcludeTestClasspathConfigurations.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrganizationOrthogonalityHttpM12Q01IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    /** team-beta provisions exactly this organization and its bootstrap operator. */
    private static final String ORGANIZATION_ID = "0198a475-0831-7000-8000-000000000001";
    private static final String OPERATOR_USERNAME = "crewscope-monitor";
    private static final String BOOTSTRAP_PASSWORD = "m12q01-ortho-bootstrap-8e1f4a7c52b9";
    private static final String PASSWORD = "m12q01-ortho-password-64d2";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    @TempDir
    static Path artifactRoot;

    /** The coding sandbox roots the deployment contract requires to resolve (never touched). */
    static final Path CODING_ROOT = createDirectory("crewscope-m12q01-coding");

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

    private WebTestClient client;
    private String orgUri;

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
        orgUri = "/api/v1/organizations/" + ORGANIZATION_ID;
    }

    @Test
    void teamRolesNeverOpenAnyOrganizationSurface() {
        UUID teamOwnerPrincipal = insertPrincipal("M12Q01 Team Owner");
        String teamOwnerUsername = "m12q01-teamowner-" + suffix8();
        seedLocalAccountBoundTo(teamOwnerPrincipal, teamOwnerUsername);
        insertTeamWithOwnerRole(teamOwnerPrincipal);
        CookieJar teamOwner = new CookieJar();
        login(teamOwner, teamOwnerUsername, PASSWORD);

        // The family rescan: settings, role grants, exports, SLI, quota — reads and writes.
        getWithCookies(orgUri + "/settings", teamOwner)
                .exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN.value());
        putJson(orgUri + "/settings", teamOwner)
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m12q01-settings-" + UUID.randomUUID())
                .header(ApiHeaders.IF_MATCH, "\"0\"")
                .bodyValue(Map.of("auditEventRetentionDays", 90))
                .exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN.value());
        getWithCookies(orgUri + "/role-grants", teamOwner)
                .exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN.value());
        postJson(orgUri + "/role-grants", teamOwner)
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m12q01-grant-" + UUID.randomUUID())
                .bodyValue(Map.of(
                        "principalId", teamOwnerPrincipal.toString(),
                        "role", "ORG_AUDITOR",
                        "reason", "orthogonality negative"))
                .exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN.value());
        postJson(orgUri + "/data-exports", teamOwner)
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m12q01-export-" + UUID.randomUUID())
                .bodyValue(Map.of("includeVectors", false))
                .exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN.value());
        getWithCookies(orgUri + "/sli", teamOwner)
                .exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN.value());
        getWithCookies(orgUri + "/quota", teamOwner)
                .exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN.value());
        putJson(orgUri + "/quota", teamOwner)
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m12q01-quota-" + UUID.randomUUID())
                .header(ApiHeaders.IF_MATCH, "\"0\"")
                .bodyValue(Map.of("monthlyTaskIntakeLimit", 5))
                .exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void orgAuditorReadsAuditFactsAlone() {
        UUID auditorPrincipal = insertPrincipal("M12Q01 Auditor");
        String auditorUsername = "m12q01-auditor-" + suffix8();
        seedLocalAccountBoundTo(auditorPrincipal, auditorUsername);
        insertOrganizationRoleGrant(auditorPrincipal, "ORG_AUDITOR");
        CookieJar auditor = new CookieJar();
        login(auditor, auditorUsername, PASSWORD);

        // AUDIT_READ_ORG opens exactly the audit-fact surfaces (I03's SLI anchor, rescanned,
        // plus the role-grant list — the role-management audit face)...
        getWithCookies(orgUri + "/sli", auditor)
                .exchange().expectStatus().isOk();
        getWithCookies(orgUri + "/role-grants", auditor)
                .exchange().expectStatus().isOk();

        // ...and refuses every settings-flavoured read: no SETTINGS_READ, no ROLE_ASSIGN.
        getWithCookies(orgUri + "/settings", auditor)
                .exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN.value());
        getWithCookies(orgUri + "/quota", auditor)
                .exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN.value());

        // Every governance write is refused, and the denial is a SECURITY fact.
        int denialsBefore = auditCount("ORG_GOVERNANCE_DENIED");
        putJson(orgUri + "/settings", auditor)
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m12q01-settings-" + UUID.randomUUID())
                .header(ApiHeaders.IF_MATCH, "\"0\"")
                .bodyValue(Map.of("auditEventRetentionDays", 90))
                .exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN.value());
        postJson(orgUri + "/role-grants", auditor)
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m12q01-grant-" + UUID.randomUUID())
                .bodyValue(Map.of(
                        "principalId", auditorPrincipal.toString(),
                        "role", "ORG_ADMIN",
                        "reason", "auditor escalation negative"))
                .exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN.value());
        postJson(orgUri + "/data-exports", auditor)
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m12q01-export-" + UUID.randomUUID())
                .bodyValue(Map.of("includeVectors", false))
                .exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(auditCount("ORG_GOVERNANCE_DENIED")).isGreaterThan(denialsBefore);
    }

    @Test
    void orgAuthorityGrantsNoTeamSurface() {
        UUID adminPrincipal = insertPrincipal("M12Q01 Org Admin");
        String adminUsername = "m12q01-admin-" + suffix8();
        seedLocalAccountBoundTo(adminPrincipal, adminUsername);
        insertOrganizationRoleGrant(adminPrincipal, "ORG_ADMIN");
        CookieJar admin = new CookieJar();
        login(admin, adminUsername, PASSWORD);

        // The session projects organization keys only (the A01 negative, rescanned).
        JsonNode session = getJson("/api/v1/auth/session", admin);
        assertThat(session.path("organization").path("role").asText()).isEqualTo("ORG_ADMIN");

        // The directory answers 200 with an empty page: no team existence is enumerable.
        JsonNode teams = getJson(orgUri + "/teams", admin);
        assertThat(teams.isArray()).isTrue();
        assertThat(teams.size()).isZero();

        // A real, READY team every seeded member belongs to but the admin does not.
        UUID teamId = insertReadyTeam(operatorPrincipalId());

        getWithCookies(orgUri + "/teams/" + teamId, admin)
                .exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN.value());
        getWithCookies(orgUri + "/teams/" + teamId + "/members", admin)
                .exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN.value());
        getWithCookies(orgUri + "/teams/" + teamId + "/invitations", admin)
                .exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN.value());
        postJson(orgUri + "/teams/" + teamId + "/invitations", admin)
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m12q01-invite-" + UUID.randomUUID())
                .bodyValue(Map.of(
                        "targetEmail", "stranger@example.com",
                        "targetRole", "TEAM_ADMIN",
                        "expiresInMinutes", 60))
                .exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    // ------------------------------------------------------------------ HTTP plumbing

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

    private JsonNode getJson(String uri, CookieJar jar) {
        EntityExchangeResult<String> result = getWithCookies(uri, jar)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody(String.class)
                .returnResult();
        return readTree(result.getResponseBody());
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

    /** user_account → login_identity(local) → local_credential(bcrypt) → binding to the given
     *  principal, all through SQL: the login path under test stays the real one. */
    private void seedLocalAccountBoundTo(UUID principalId, String username) {
        UUID accountId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crewscope.user_account (
                    id, username, username_normalized, email, email_normalized, display_name,
                    status, platform_role, security_version, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', 'USER', 1, 0, NOW(), NOW())
                """,
                accountId, username, username,
                username + "@m12q01.test", username + "@m12q01.test", "M12Q01 Orthogonality Actor");
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

    /** The organization role grant the capability checks re-query per request (I03's form). */
    private void insertOrganizationRoleGrant(UUID principalId, String role) {
        jdbc.update(
                """
                INSERT INTO crewscope.organization_role_grant (
                    id, organization_id, principal_id, role, status,
                    granted_by_principal_id, version,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, 'ACTIVE', ?, 0, NOW(), ?, NOW(), ?)
                """,
                UUID.randomUUID(), UUID.fromString(ORGANIZATION_ID), principalId, role,
                principalId, principalId, principalId);
    }

    /** A READY team (owner member + default workspace wired) the seeded principals do not belong
     *  to: the team resolves, then the membership check refuses — the negatives need a real team,
     *  not a 404. */
    private UUID insertReadyTeam(UUID ownerPrincipalId) {
        UUID teamId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status)"
                        + " VALUES (?, ?, ?, 'ACTIVE')",
                teamId, UUID.fromString(ORGANIZATION_ID),
                "M12Q01 Ortho Team " + suffix8());
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
                VALUES (?, ?, ?, 'TEAM', 'M12Q01 Ortho Space', 'ACTIVE')
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
                "M12Q01 Team " + suffix8());
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
