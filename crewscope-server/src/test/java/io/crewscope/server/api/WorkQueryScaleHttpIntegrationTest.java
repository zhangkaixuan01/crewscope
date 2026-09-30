package io.crewscope.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.crewscope.infrastructure.persistence.workitem.A06QueryScaleFixtures;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.filter.annotation.TypeExcludeFilters;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The end-to-end scale gate M9b-A06 handed over to Q01 (A06 summary :42): A06 proved the SQL
 * layer holds p95 {@code <= 200ms}; the 4vCPU/8GiB budget of {@code <= 500ms} covers the real
 * HTTP surface — network, serialization, the session→binding→principal authorization chain —
 * and belongs to this gate.
 *
 * <p>The seed itself is the A06 test-jar fixtures, reimported unchanged so both gates walk the
 * same deterministic value domain (repeated sort keys, null due dates, the probe row deep in
 * every ordering). Following A06 :61, the FULL tier deliberately does NOT seed the inbox or
 * review projection governance chains — the WorkDesk walk here asserts the sections those
 * chains do not feed stay empty and walks the assignment-driven ones instead.
 *
 * <p>Tiers: SMALL (200 items per project) runs in the regular suite; FULL (10 000) is opt-in
 * via {@code -Dq01.scale=full}, mirroring A06's {@code -Da06.scale=full}. The measured numbers
 * of both tiers land in the Q01 matrix document.
 */
@Tag("integration")
@ActiveProfiles("team-beta")
@TypeExcludeFilters(WorkQueryScaleHttpIntegrationTest.ExcludeTestClasspathConfigurations.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkQueryScaleHttpIntegrationTest extends AbstractPostgresRedisContainerIntegrationTest {

    private static final String SESSION_COOKIE = "CREWSCOPE_SESSION";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final String PASSWORD = "q01-scale-password-9f2a";

    /** The wire page size every walk asks for; equals WORK_ITEM_MAX_LIMIT and the desk section max. */
    private static final int PAGE = 100;
    private static final int WARMUP = 5;
    private static final int FIRST_PAGE_SAMPLES = 20;
    /** The M9b main plan's end-to-end budget on the 4vCPU/8GiB reference environment. */
    private static final long HTTP_BUDGET_MILLIS = 500;

    @TempDir
    static Path artifactRoot;

    /** The coding sandbox (bare repositories, worktrees, locks, git HOME) — the deployment
     *  contract requires pre-created roots, so the directories exist before the context reads
     *  them; the gate never touches them, only their resolution must succeed. */
    static final Path CODING_ROOT = createDirectory("crewscope-q01-coding");

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

    /**
     * Fixed throwaway keys for the fail-closed deployments team-beta enables (credential envelope,
     * activity cursors, login defense, task tokens, invitations — quickstart generates them into
     * .env; the test pins its own so no real key ever reaches a build log). Only the walk under
     * test sees them, and nothing asserted depends on their values.
     */
    private static final String TEST_CREDENTIAL_KEYS = "v1=7fxmDni7ah4GVkFpXlFp/WJ9Xvlk4lVlEuMO5YMmYPw=";
    private static final String TEST_ACTIVITY_KEY = "qy+UtChs7KTKHsPDCKXhhm41lKIf8/3GaSKmClfeqwM=";
    private static final String TEST_LOGIN_DEFENSE_KEY = "gL9T+TUAof/s+bhqjftsy17/a4NSctPXA+/6pIZzA1o=";
    private static final String TEST_TASK_TOKEN_KEY = "u1WSa5SkDSf8N10uJ2sJE72U/u+ZDGrjaffROM17X5U=";
    private static final String TEST_INVITATION_KEY = "asKlHnh1ld0Le3fJyzEj1VDm2poi7ZMyuzisUyIpg6k=";

    @DynamicPropertySource
    static void testDeploymentProperties(DynamicPropertyRegistry registry) {
        // team-beta defaults the artifact tree to /var/crewscope/artifacts, which the test
        // sandbox must not touch; the container database and redis come from the base class.
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
        registry.add("crewscope.coding.git.command-home",
                () -> prepared(CODING_ROOT, "git-home"));
        // The AgentScope state backend keeps its own connection coordinate (quickstart wires it
        // to the compose redis); default localhost would reach whatever redis runs on the host.
        registry.add("crewscope.runtime.redis.url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getFirstMappedPort());
        // team-beta provisions the bootstrap operator at startup; the shared dev default
        // password does not survive provisioning — supply a throwaway strong one.
        registry.add("crewscope.security.bootstrap.password",
                () -> "q01-scale-bootstrap-7c3e9a1b4d6f8250a17e");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private WebTestClient client;
    private A06QueryScaleFixtures.Scope scope;
    private UUID ownerPrincipalId;
    private final CookieJar jar = new CookieJar();
    private String username;

    @BeforeEach
    void seedScaleWorldAndLogin() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
        boolean fullTier = "full".equals(System.getProperty("q01.scale"));
        scope = new A06QueryScaleFixtures(jdbc).seed(
                fullTier ? A06QueryScaleFixtures.Profile.FULL : A06QueryScaleFixtures.Profile.SMALL);
        // A06 walks the repository layer, so its teams never need the owner/workspace
        // cross-references; the HTTP authorization chain refuses a team whose owner_member_id or
        // default_workspace_id is still null (team.initializationStatus must be READY). Patch the
        // cross-references in rather than forking the shared fixtures.
        jdbc.update("""
                UPDATE crewscope.team team
                SET owner_member_id = member.id,
                    default_workspace_id = (
                        SELECT workspace.id FROM crewscope.workspace workspace
                        WHERE workspace.team_id = team.id LIMIT 1)
                FROM crewscope.team_member member
                WHERE member.organization_id = team.organization_id AND member.team_id = team.id
                """);
        // The fixtures keep their owner principal internal; the probe team's single member row
        // carries it, and reading it out keeps A06's asset untouched. Binding the login account
        // to that principal inherits both the team membership and every seeded assignment.
        ownerPrincipalId = jdbc.queryForObject(
                "SELECT user_principal_id FROM crewscope.team_member "
                        + "WHERE organization_id = ? AND team_id = ?",
                UUID.class, scope.organizationId(), scope.teamId());
        seedLocalAccountBoundTo(ownerPrincipalId);
        login();
    }

    @Test
    void everyOrderingWalksTheWholeSetOverRealHttp() {
        List<String> byUpdated = walkWorkItems("updatedAt");
        List<String> byPriority = walkWorkItems("priority");
        List<String> byDue = walkWorkItems("dueAt");

        // Three views of one authorized set: full, no duplicates, nothing lost.
        assertThat(byUpdated).hasSize(scope.itemCount());
        assertThat(byPriority).hasSize(scope.itemCount());
        assertThat(byDue).hasSize(scope.itemCount());
        assertThat(Set.copyOf(byUpdated)).isEqualTo(Set.copyOf(byPriority));
        assertThat(Set.copyOf(byUpdated)).isEqualTo(Set.copyOf(byDue));
        assertThat(byUpdated).contains(scope.probeTitle());

        // The first screen every list mount opens with holds the HTTP budget after warm-up.
        for (int index = 0; index < WARMUP; index++) {
            getJson(workItemsUri("updatedAt", null));
        }
        List<Long> samples = new ArrayList<>();
        for (int index = 0; index < FIRST_PAGE_SAMPLES; index++) {
            long startedAt = System.nanoTime();
            getJson(workItemsUri("updatedAt", null));
            samples.add((System.nanoTime() - startedAt) / 1_000_000);
        }
        assertLatency("work-items first page", samples);
    }

    @Test
    void workDeskSectionsWalkTheAssignedSetWithinTheBudget() {
        JsonNode summary = getJson(workDeskUri());
        Map<String, JsonNode> sections = new LinkedHashMap<>();
        for (JsonNode section : summary.get("sections")) {
            sections.put(section.get("key").asText(), section);
        }
        assertThat(sections.keySet()).containsExactly(
                "HUMAN_GATE", "REVIEW", "BLOCKED", "WORK_ITEM", "TASK_EXECUTION", "INBOX");

        // A06 :61 alignment: neither tier seeds the inbox/review projection governance chains,
        // so the sections they feed must answer empty rather than inherit unrelated rows.
        assertThat(sections.get("HUMAN_GATE").get("total").asInt()).isZero();
        assertThat(sections.get("REVIEW").get("total").asInt()).isZero();

        // The assignment-driven section is the walk target: the fixture marks every third item
        // (index % 3 == 0) as an ACTIVE REVIEWER assignment for the owner principal.
        JsonNode workItem = sections.get("WORK_ITEM");
        int expectedTotal = (scope.itemCount() + 2) / 3;
        assertThat(workItem.get("total").asInt()).isEqualTo(expectedTotal);
        assertThat(workItem.get("items")).hasSize(Math.min(20, expectedTotal));

        // Section continuation: the first screen's cursor walks the rest of the section with no
        // row twice and none lost, and every page holds the HTTP budget.
        Set<String> seen = new HashSet<>();
        for (JsonNode item : workItem.get("items")) {
            assertThat(seen.add(item.get("objectId").asText())).isTrue();
        }
        List<Long> samples = new ArrayList<>();
        String after = workItem.get("nextCursor").isNull() ? null : workItem.get("nextCursor").asText();
        while (after != null) {
            String uri = workDeskSectionUri(after);
            long startedAt = System.nanoTime();
            JsonNode page = getJson(uri);
            samples.add((System.nanoTime() - startedAt) / 1_000_000);
            for (JsonNode item : page.get("items")) {
                assertThat(seen.add(item.get("objectId").asText())).isTrue();
            }
            after = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
        }
        assertThat(seen).hasSize(expectedTotal);
        if (!samples.isEmpty()) {
            assertLatency("work-desk section pages", samples);
        }
    }

    /**
     * The FULL tier (10 000 items, ~100 pages, ~3 334 assigned rows): the walk itself is the
     * latency sample — a plan that degrades as the project grows shows up in the tail pages,
     * not only on the first screen.
     */
    @Test
    @EnabledIfSystemProperty(named = "q01.scale", matches = "full")
    void fullTierWalksTenThousandRowsWithinTheBudget() {
        // The budget is an after-warm-up budget (A06's wording: paged reads hold it once the
        // buffer cache is warm). A freshly seeded 10 000-row project legitimately pays ~2s per
        // page while PostgreSQL pulls the index into shared_buffers; the untimed warm-up walk
        // below mirrors A06's WARMUP loop and then every measured page must hold the budget.
        walkWorkItems("updatedAt");
        // The desk section counts assignment rows while the walk deduplicates item ids — pin
        // both numbers from the seeded facts before asserting the HTTP surface against them.
        Integer assignmentRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.responsibility_assignment a "
                        + "JOIN crewscope.work_item wi ON wi.id = a.work_item_id "
                        + "WHERE a.organization_id = ? AND a.team_id = ? AND a.project_id = ? "
                        + "AND a.status = 'ACTIVE' AND a.actor_principal_id = ?",
                Integer.class, scope.organizationId(), scope.teamId(), scope.projectId(),
                ownerPrincipalId);
        Integer distinctRows = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT a.work_item_id) FROM crewscope.responsibility_assignment a "
                        + "WHERE a.organization_id = ? AND a.team_id = ? AND a.project_id = ? "
                        + "AND a.status = 'ACTIVE' AND a.actor_principal_id = ?",
                Integer.class, scope.organizationId(), scope.teamId(), scope.projectId(),
                ownerPrincipalId);
        System.out.printf("[q01-scale] seeded ACTIVE assignments=%s distinct items=%s%n",
                assignmentRows, distinctRows);
        List<Long> pageSamples = new ArrayList<>();
        List<String> ids = walkWorkItems("updatedAt", pageSamples);
        assertThat(ids).hasSize(scope.itemCount());
        assertThat(ids).contains(scope.probeTitle());
        assertLatency("full-tier work-items walk (" + scope.itemCount() + " rows)", pageSamples);

        List<Long> sectionSamples = new ArrayList<>();
        JsonNode summary = getJson(workDeskUri());
        JsonNode workItem = section(summary, "WORK_ITEM");
        int expectedTotal = (scope.itemCount() + 2) / 3;
        assertThat(workItem.get("total").asInt()).isEqualTo(expectedTotal);
        Set<String> seen = new HashSet<>();
        for (JsonNode item : workItem.get("items")) {
            assertThat(seen.add(item.get("objectId").asText())).isTrue();
        }
        String after = workItem.get("nextCursor").isNull() ? null : workItem.get("nextCursor").asText();
        while (after != null) {
            long startedAt = System.nanoTime();
            JsonNode page = getJson(workDeskSectionUri(after));
            sectionSamples.add((System.nanoTime() - startedAt) / 1_000_000);
            for (JsonNode item : page.get("items")) {
                assertThat(seen.add(item.get("objectId").asText())).isTrue();
            }
            after = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
        }
        assertThat(seen).hasSize(expectedTotal);
        assertLatency("full-tier work-desk section walk (" + expectedTotal + " rows)", sectionSamples);
    }

    /**
     * The real application scans all of {@code io.crewscope} — which, on a test classpath, also
     * reaches other suites' disposable-database {@code @Configuration}s (A01TestServer, every
     * nested {@code @SpringBootConfiguration} of the *IntegrationTest classes, the A06 test-jar's
     * TestApplication). Every such host class name ends in {@code Test} or {@code TestServer} and
     * no production class does, so the scan keeps exactly the production beans. The full stack
     * below is then the real one: no slicing, no mocked beans.
     */
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

    // ------------------------------------------------------------------ HTTP plumbing

    /** Walks the whole work-item collection under one ordering; every page is timed when a
     *  sample collector is given. Returns the item titles (the probe row is hunted by title). */
    private List<String> walkWorkItems(String sort) {
        return walkWorkItems(sort, null);
    }

    private List<String> walkWorkItems(String sort, List<Long> samples) {
        List<String> titles = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        String after = null;
        for (int page = 0; page < 2_000; page++) {
            String uri = workItemsUri(sort, after);
            long startedAt = System.nanoTime();
            JsonNode body = getJson(uri);
            long elapsed = (System.nanoTime() - startedAt) / 1_000_000;
            if (samples != null) {
                samples.add(elapsed);
                if (elapsed > HTTP_BUDGET_MILLIS) {
                    System.out.printf(
                            "[q01-scale] slow page #%d after=%s took %dms%n",
                            page, after == null ? "-" : after.substring(0, Math.min(12, after.length())), elapsed);
                }
            }
            for (JsonNode item : body.get("items")) {
                assertThat(seenIds.add(item.get("id").asText()))
                        .as("a row appeared on two pages under %s", sort)
                        .isTrue();
                titles.add(item.get("title").asText());
            }
            JsonNode next = body.get("nextCursor");
            if (next == null || next.isNull()) {
                return titles;
            }
            after = next.asText();
        }
        throw new AssertionError("the walk never ended: the ordering or the cursor is drifting");
    }

    private JsonNode getJson(String uri) {
        WebTestClient.RequestHeadersSpec<?> request = client.get().uri(uri);
        jar.apply(request);
        org.springframework.test.web.reactive.server.EntityExchangeResult<String> result =
                request.exchange()
                        .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                        .expectBody(String.class)
                        .returnResult();
        if (!result.getStatus().isSameCodeAs(org.springframework.http.HttpStatus.OK)) {
            throw new AssertionError("GET " + uri + " -> " + result.getStatus()
                    + " body=" + result.getResponseBody());
        }
        try {
            return objectMapper.readTree(result.getResponseBody());
        } catch (Exception malformed) {
            throw new AssertionError("malformed JSON from " + uri, malformed);
        }
    }

    private String workItemsUri(String sort, String after) {
        StringBuilder uri = new StringBuilder("/api/v1/organizations/")
                .append(scope.organizationId())
                .append("/teams/").append(scope.teamId())
                .append("/work-projects/").append(scope.projectId())
                .append("/work-items?limit=").append(PAGE)
                .append("&sort=").append(sort);
        if (after != null) {
            uri.append("&after=").append(after);
        }
        return uri.toString();
    }

    /** The desk first screen pins the probe project and keeps the default 20-row section page so
     *  the WORK_ITEM section actually truncates and hands out a continuation cursor. */
    private String workDeskUri() {
        return "/api/v1/organizations/" + scope.organizationId()
                + "/teams/" + scope.teamId()
                + "/work-desk?projectId=" + scope.projectId();
    }

    private String workDeskSectionUri(String after) {
        return "/api/v1/organizations/" + scope.organizationId()
                + "/teams/" + scope.teamId()
                + "/work-desk/sections/WORK_ITEM?projectId=" + scope.projectId()
                + "&limit=" + PAGE
                + "&after=" + after;
    }

    private JsonNode section(JsonNode summary, String key) {
        for (JsonNode section : summary.get("sections")) {
            if (key.equals(section.get("key").asText())) {
                return section;
            }
        }
        throw new AssertionError("section " + key + " missing from the desk summary");
    }

    private void assertLatency(String label, List<Long> samples) {
        List<Long> sorted = samples.stream().sorted().toList();
        long p50 = sorted.get((int) Math.ceil(0.50 * sorted.size()) - 1);
        long p95 = sorted.get((int) Math.ceil(0.95 * sorted.size()) - 1);
        long max = sorted.get(sorted.size() - 1);
        // The measured numbers are part of the Q01 matrix evidence; print for the record.
        System.out.printf("[q01-scale] %-40s n=%d p50=%dms p95=%dms max=%dms%n",
                label, sorted.size(), p50, p95, max);
        assertThat(p95).as("%s: p95 must hold the 500ms end-to-end budget", label)
                .isLessThanOrEqualTo(HTTP_BUDGET_MILLIS);
    }

    // ------------------------------------------------------------------ seed and login

    /** user_account → login_identity(local) → local_credential(bcrypt) → binding to the owner
     *  principal, all through SQL: the login path under test stays the real one. */
    private void seedLocalAccountBoundTo(UUID principalId) {
        username = "q01-scale-" + UUID.randomUUID().toString().substring(0, 8);
        UUID accountId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crewscope.user_account (
                    id, username, username_normalized, email, email_normalized, display_name,
                    status, platform_role, security_version, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', 'USER', 1, 0, NOW(), NOW())
                """,
                accountId, username, username,
                username + "@scale.q01", username + "@scale.q01", "Q01 Scale Viewer");
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
                UUID.randomUUID(), accountId, scope.organizationId(), principalId);
    }

    /** Anonymous session (XSRF token) → real login endpoint with the CSRF header → the rotated
     *  session cookie every guarded read then carries. */
    private void login() {
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
                .bodyValue(Map.of("identifier", username, "password", PASSWORD));
        jar.apply(loginRequest);
        loginRequest
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("authenticated").isEqualTo(true)
                .consumeWith(jar::capture);
    }

    /** WebTestClient keeps no cookie jar; the login dance and every guarded read share this one. */
    private static final class CookieJar {
        private final Map<String, String> values = new LinkedHashMap<>();

        void apply(WebTestClient.RequestHeadersSpec<?> request) {
            values.forEach(request::cookie);
        }

        void capture(org.springframework.test.web.reactive.server.EntityExchangeResult<?> result) {
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
}
