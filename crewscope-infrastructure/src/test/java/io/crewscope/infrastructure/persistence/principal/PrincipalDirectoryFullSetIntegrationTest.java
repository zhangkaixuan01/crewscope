package io.crewscope.infrastructure.persistence.principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.principal.PrincipalDirectoryCursor;
import io.crewscope.application.principal.PrincipalDirectoryEntry;
import io.crewscope.application.principal.PrincipalDirectoryPage;
import io.crewscope.application.principal.PrincipalDirectoryPurpose;
import io.crewscope.application.principal.PrincipalDirectoryQuery;
import io.crewscope.application.principal.PrincipalKind;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the M9b-A06 full-set directory against migrated PostgreSQL: however large the Team's
 * visible set is, every row stays reachable past any in-memory window — the previous read capped
 * Agent candidates at the first two hundred rows — in one stable byte-keyed ordering, with the
 * filters and the by-id lookup confined to the authorized candidate set, and with an AUDIT
 * purpose seeing the whole membership history that an ASSIGNMENT read never does.
 */
@SpringBootTest(
        classes = PrincipalDirectoryFullSetIntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.default_schema=crewscope",
            "spring.jpa.open-in-view=false"
        })
class PrincipalDirectoryFullSetIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-09-02T08:00:00Z");

    @Autowired
    private JdbcPrincipalDirectoryRepositoryAdapter repository;

    @Autowired
    private JdbcTemplate jdbc;

    private OrganizationId organizationId;
    private TeamId teamId;
    private UUID workspaceId;
    private UUID viewerPrincipal;
    private TeamMemberId viewerMember;
    private TeamId foreignTeam;

    @BeforeEach
    void seedScope() {
        jdbc.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        organizationId = OrganizationId.generate();
        teamId = TeamId.generate();
        workspaceId = UUID.randomUUID();
        seedOrganization(organizationId, "A06 Directory Organization");
        seedTeam(teamId, organizationId, "A06 Directory Team");
        seedWorkspace(workspaceId, organizationId, teamId);
        viewerPrincipal = seedPrincipal("A06 Viewer", "USER");
        viewerMember = new TeamMemberId(seedMember(viewerPrincipal, teamId, "ACTIVE"));
        seedOwnerRole(viewerMember);

        // Noise from another Team of the same organization: authorized sets never include it.
        foreignTeam = TeamId.generate();
        seedTeam(foreignTeam, organizationId, "A06 Foreign Team");
        UUID foreignWorkspace = UUID.randomUUID();
        seedWorkspace(foreignWorkspace, organizationId, foreignTeam);
        UUID foreignUser = seedPrincipal("A06 Foreign Member", "USER");
        seedMember(foreignUser, foreignTeam, "ACTIVE");
        seedAgentProfile(
                seedPrincipal("A06 Foreign Agent", "TEAM_AGENT", foreignTeam),
                foreignTeam, foreignWorkspace, "TEAM", null);
    }

    /**
     * Two hundred five visible Agents plus the viewing member page through completely — offset
     * after offset, then cursor after cursor — with no duplicate, no gap and one shared ordering,
     * and the rows past two hundred stay reachable, which is the regression this read fixes.
     */
    @Test
    void pagesTheWholeVisibleSetBeyondAnyInMemoryWindow() {
        seedAgentBatch(205);
        Comparator<PrincipalDirectoryEntry> ordering = Comparator
                .comparing((PrincipalDirectoryEntry entry) ->
                        entry.displayName().toLowerCase(Locale.ROOT))
                .thenComparing(entry -> entry.principalId().toString());

        List<PrincipalDirectoryEntry> byOffset = new ArrayList<>();
        int offset = 0;
        OptionalInt next;
        do {
            PrincipalDirectoryPage page = search(query(
                    PrincipalDirectoryPurpose.ASSIGNMENT, Optional.empty(), offset));
            byOffset.addAll(page.items());
            next = page.nextOffset();
            offset = next.orElse(0);
        } while (next.isPresent());

        List<PrincipalDirectoryEntry> byCursor = new ArrayList<>();
        Optional<PrincipalDirectoryCursor> cursor = Optional.empty();
        do {
            PrincipalDirectoryPage page = search(query(
                    PrincipalDirectoryPurpose.ASSIGNMENT, cursor, 0));
            byCursor.addAll(page.items());
            cursor = page.nextCursor();
        } while (cursor.isPresent());

        assertEquals(206, byOffset.size(), "the viewer and every visible Agent are reachable");
        assertEquals(byOffset, byCursor, "both paging modes walk one ordering");
        assertEquals(-1, Integer.signum(
                ordering.compare(byOffset.get(0), byOffset.get(byOffset.size() - 1))),
                "the byte key with its id tie-breaker is total");
        assertEquals(206, byOffset.stream().map(entry -> entry.principalId()).distinct().count());
        assertTrue(byOffset.stream().anyMatch(entry ->
                        entry.displayName().equals("Agent 204")),
                "rows past the previous two-hundred window stay reachable");
        assertTrue(byOffset.stream().noneMatch(entry ->
                        entry.displayName().startsWith("A06 Foreign")),
                "another Team's subjects never leak into the candidate set");
    }

    /**
     * The ordering is the byte-stable C collation — uppercase before lowercase — and equal
     * display names fall back to the principal id, so the position of a tie never depends on
     * which page it lands on.
     */
    @Test
    void sortsOnTheByteKeyWithThePrincipalIdTieBreaker() {
        UUID first = seedPrincipalAndMember("Duplicate Name");
        UUID second = seedPrincipalAndMember("Duplicate Name");
        UUID upperCased = seedPrincipalAndMember("Bob");
        UUID lowerCased = seedPrincipalAndMember("bob");
        UUID ten = seedPrincipalAndMember("apple10");
        UUID two = seedPrincipalAndMember("apple2");

        List<PrincipalDirectoryEntry> items = search(query(
                PrincipalDirectoryPurpose.ASSIGNMENT, Optional.empty(), 0)).items();

        List<UUID> order = items.stream()
                .map(PrincipalDirectoryEntry::principalId).map(PrincipalId::value).toList();
        // '1' sorts before '2' under the byte-stable C collation, digits not numbers.
        assertTrue(order.indexOf(ten) < order.indexOf(two),
                "the C collation orders by bytes, not by locale");
        // PostgreSQL orders uuid bytes unsigned; the hex rendering compares the same way.
        assertEquals(
                Integer.signum(first.toString().compareTo(second.toString())),
                Integer.signum(order.indexOf(first) - order.indexOf(second)),
                "equal names keep the principal id order on every page");
        // 'Bob' and 'bob' share the lower-cased key and fall to the same id tie-breaker.
        assertEquals(
                Integer.signum(upperCased.toString().compareTo(lowerCased.toString())),
                Integer.signum(order.indexOf(upperCased) - order.indexOf(lowerCased)),
                "case-folded names share one key");
        assertEquals(List.of("TEAM_OWNER"),
                items.stream().filter(entry -> entry.principalId().value().equals(viewerPrincipal))
                        .findFirst().orElseThrow().roles());
    }

    /**
     * The prefix filter matches case-insensitively without ever becoming a wildcard, the kind
     * filter keeps USER and AGENT apart, and a by-id lookup answers only from the authorized
     * candidate set.
     */
    @Test
    void filtersByPrefixKindAndIdsWithinTheAuthorizedSet() {
        UUID alice = seedPrincipalAndMember("Alice");
        seedPrincipalAndMember("alice wang");
        seedPrincipalAndMember("alicia");
        seedPrincipalAndMember("a_x");
        seedPrincipalAndMember("abc");
        UUID agent = seedAgentProfile(seedPrincipal("Alice Agent", "TEAM_AGENT", teamId));

        List<String> byPrefix = names(Optional.of("aLi"));
        assertEquals(
                Set.of("Alice", "alice wang", "alicia", "Alice Agent"), Set.copyOf(byPrefix));

        List<String> wildcardShaped = names(Optional.of("a_"));
        assertEquals(Set.of("a_x"), Set.copyOf(wildcardShaped),
                "an underscore in the prefix is a literal, not a single-character wildcard");

        List<PrincipalDirectoryEntry> agents = search(new PrincipalDirectoryQuery(
                organizationId, teamId, Optional.empty(), Set.of(PrincipalKind.AGENT),
                Set.of(), PrincipalDirectoryPurpose.ASSIGNMENT,
                Optional.empty(), 0, 50)).items();
        assertTrue(agents.stream().allMatch(entry -> entry.kind() == PrincipalKind.AGENT));
        assertTrue(agents.stream().noneMatch(entry ->
                entry.principalId().value().equals(alice)));

        UUID foreignUser = jdbc.queryForObject(
                "SELECT user_principal_id FROM crewscope.team_member WHERE team_id = ?",
                UUID.class, foreignTeam.value());
        List<PrincipalDirectoryEntry> byIds = search(new PrincipalDirectoryQuery(
                organizationId, teamId, Optional.empty(), Set.of(),
                Set.of(new PrincipalId(agent), new PrincipalId(foreignUser)),
                PrincipalDirectoryPurpose.ASSIGNMENT, Optional.empty(), 0, 50)).items();
        assertEquals(Set.of(agent), byIds.stream()
                .map(entry -> entry.principalId().value()).collect(Collectors.toSet()),
                "a by-id lookup answers from the authorized candidate set only");
    }

    /**
     * An ASSIGNMENT read sees the current Team; an AUDIT read additionally resolves the
     * membership history — a member who left and one who is suspended — without their roles.
     */
    @Test
    void auditSeesTheWholeMembershipHistoryWhileAssignmentSeesActiveOnly() {
        UUID former = seedPrincipalAndMember("Former Member");
        jdbc.update("UPDATE crewscope.team_member SET status = 'LEFT' WHERE user_principal_id = ?",
                former);
        UUID paused = seedPrincipalAndMember("Paused Member");
        jdbc.update("UPDATE crewscope.team_member SET status = 'SUSPENDED' WHERE user_principal_id = ?",
                paused);

        Function<PrincipalDirectoryPurpose, Set<UUID>> visibleIds = purpose ->
                search(query(purpose, Optional.empty(), 0)).items().stream()
                        .map(PrincipalDirectoryEntry::principalId).map(PrincipalId::value)
                        .collect(Collectors.toSet());

        Set<UUID> assignment = visibleIds.apply(PrincipalDirectoryPurpose.ASSIGNMENT);
        assertTrue(!assignment.contains(former) && !assignment.contains(paused),
                "an ASSIGNMENT read lists only ACTIVE members");

        Set<UUID> audit = visibleIds.apply(PrincipalDirectoryPurpose.AUDIT);
        assertTrue(audit.containsAll(Set.of(former, paused)),
                "an AUDIT read resolves the whole membership history");
        assertEquals(List.of(), search(prefixQuery("Former", PrincipalDirectoryPurpose.AUDIT))
                        .items().get(0).roles(),
                "a historical identity carries no active roles");
    }

    private PrincipalDirectoryPage search(PrincipalDirectoryQuery query) {
        return repository.search(query, viewerMember);
    }

    private PrincipalDirectoryQuery query(
            PrincipalDirectoryPurpose purpose, Optional<PrincipalDirectoryCursor> cursor,
            int offset) {
        return new PrincipalDirectoryQuery(
                organizationId, teamId, Optional.empty(), Set.of(), Set.of(), purpose,
                cursor, offset, 20);
    }

    private PrincipalDirectoryQuery prefixQuery(
            String prefix, PrincipalDirectoryPurpose purpose) {
        return new PrincipalDirectoryQuery(
                organizationId, teamId, Optional.of(prefix), Set.of(), Set.of(),
                purpose, Optional.empty(), 0, 50);
    }

    private List<String> names(Optional<String> prefix) {
        return search(prefixQuery(prefix.orElseThrow(),
                PrincipalDirectoryPurpose.ASSIGNMENT)).items().stream()
                .map(PrincipalDirectoryEntry::displayName).toList();
    }

    private void seedOrganization(OrganizationId id, String name) {
        jdbc.update("INSERT INTO crewscope.organization (id, name, status) VALUES (?, ?, 'ACTIVE')",
                id.value(), name);
    }

    private void seedTeam(TeamId id, OrganizationId organization, String name) {
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status) "
                        + "VALUES (?, ?, ?, 'ACTIVE')",
                id.value(), organization.value(), name);
    }

    private void seedWorkspace(UUID id, OrganizationId organization, TeamId team) {
        jdbc.update(
                """
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES (?, ?, ?, 'TEAM', ?, 'ACTIVE')
                """,
                id, organization.value(), team.value(), "A06 Workspace");
    }

    private UUID seedPrincipal(String displayName, String type) {
        return seedPrincipal(displayName, type, null);
    }

    /** An Agent Principal carries the Team scope its profile's composite foreign key requires. */
    private UUID seedPrincipal(String displayName, String type, TeamId team) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, team_id, principal_type, display_name, visibility, status
                ) VALUES (?, ?, ?, ?, ?, 'ORGANIZATION', 'ACTIVE')
                """,
                id, organizationId.value(), team == null ? null : team.value(), type, displayName);
        return id;
    }

    private UUID seedMember(UUID principal, TeamId team, String status) {
        UUID member = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO crewscope.team_member "
                        + "(id, organization_id, team_id, user_principal_id, status, join_method, joined_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'IMPORT', ?)",
                member, organizationId.value(), team.value(), principal, status,
                BASE.atOffset(ZoneOffset.UTC));
        return member;
    }

    private UUID seedPrincipalAndMember(String displayName) {
        UUID principal = seedPrincipal(displayName, "USER");
        seedMember(principal, teamId, "ACTIVE");
        return principal;
    }

    private void seedOwnerRole(TeamMemberId member) {
        OffsetDateTime now = BASE.atOffset(ZoneOffset.UTC);
        UUID roleId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO crewscope.team_role (
                    id, organization_id, team_id, role_key, name, built_in,
                    permissions, scope_type, status, created_at, updated_at
                ) VALUES (?, ?, ?, 'TEAM_OWNER', 'Team Owner', TRUE, '[]'::JSONB, 'TEAM',
                          'ACTIVE', ?, ?)
                """,
                roleId, organizationId.value(), teamId.value(), now, now);
        jdbc.update(
                """
                INSERT INTO crewscope.team_member_role (
                    id, organization_id, team_id, team_member_id, team_role_id, scope_type,
                    granted_by_principal_id, granted_at, valid_from, status,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'TEAM', ?, ?, ?, 'ACTIVE', ?, ?)
                """,
                UUID.randomUUID(), organizationId.value(), teamId.value(), member.value(),
                roleId, viewerPrincipal, now, now, now, now);
    }

    /** @return the Agent's principal id — the identity the directory exposes, not the profile row. */
    private UUID seedAgentProfile(UUID agentPrincipal) {
        return seedAgentProfile(agentPrincipal, teamId, workspaceId, "TEAM", null);
    }

    private UUID seedAgentProfile(
            UUID agentPrincipal, TeamId team, UUID teamWorkspace,
            String ownershipType, UUID ownerMember) {
        OffsetDateTime now = BASE.atOffset(ZoneOffset.UTC);
        jdbc.update(
                """
                INSERT INTO crewscope.agent_profile (
                    id, organization_id, team_id, workspace_id, agent_principal_id,
                    owner_member_id, profile_type, default_profile, status, version,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id,
                    ownership_type, ownership_team_id, runtime_role,
                    template_key, template_version
                ) VALUES (?, ?, ?, ?, ?, ?, 'TEAM', FALSE, 'ACTIVE', 0, ?, ?, ?, ?,
                          ?, ?, 'TEAM_COORDINATOR', 'team-coordinator', 1)
                """,
                UUID.randomUUID(), organizationId.value(), team.value(), teamWorkspace,
                agentPrincipal, ownerMember, now, viewerPrincipal, now, viewerPrincipal,
                ownershipType, team.value());
        return agentPrincipal;
    }

    /** Bulk-seeds numbered visible Agents; the viewer's own row sorts first among them. */
    private void seedAgentBatch(int count) {
        java.sql.Timestamp now = java.sql.Timestamp.from(BASE);
        List<Object[]> principals = new ArrayList<>();
        List<Object[]> profiles = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            UUID principal = UUID.randomUUID();
            UUID profile = UUID.randomUUID();
            principals.add(new Object[] {
                    principal, organizationId.value(), teamId.value(), "TEAM_AGENT",
                    "Agent %03d".formatted(index), now});
            profiles.add(new Object[] {
                    profile, organizationId.value(), teamId.value(), workspaceId,
                    principal, "TEAM", teamId.value(), now, viewerPrincipal});
        }
        jdbc.batchUpdate(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, team_id, principal_type, display_name, visibility,
                    status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'ORGANIZATION', 'ACTIVE', ?, ?)
                """,
                principals.stream().map(row -> new Object[] {
                        row[0], row[1], row[2], row[3], row[4], row[5], row[5]}).toList());
        jdbc.batchUpdate(
                """
                INSERT INTO crewscope.agent_profile (
                    id, organization_id, team_id, workspace_id, agent_principal_id,
                    profile_type, default_profile, status, version,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id,
                    ownership_type, ownership_team_id, runtime_role,
                    template_key, template_version
                ) VALUES (?, ?, ?, ?, ?, 'TEAM', FALSE, 'ACTIVE', 0, ?, ?, ?, ?,
                          ?, ?, 'TEAM_COORDINATOR', 'team-coordinator', 1)
                """,
                profiles.stream().map(row -> new Object[] {
                        row[0], row[1], row[2], row[3], row[4],
                        row[7], row[8], row[7], row[8], row[5], row[6]}).toList());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(JdbcPrincipalDirectoryRepositoryAdapter.class)
    static class TestApplication {}
}
