package io.crewscope.infrastructure.persistence.organization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.organization.OrganizationMemberDirectoryCursor;
import io.crewscope.application.organization.OrganizationMemberDirectoryEntry;
import io.crewscope.application.organization.OrganizationMemberDirectoryPage;
import io.crewscope.application.organization.OrganizationMemberDirectoryQuery;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
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
 * Proves the M13b-A01 organization member directory against migrated PostgreSQL: the candidate
 * set is exactly this organization's ACTIVE account bindings — a DISABLED binding and another
 * organization's member never appear — each row joins the account identity and status with the
 * ACTIVE organization roles, the prefix matches display name or username literally, and both
 * paging modes walk one byte-stable ordering.
 */
@SpringBootTest(
        classes = OrganizationMemberDirectoryIntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.default_schema=crewscope",
            "spring.jpa.open-in-view=false"
        })
class OrganizationMemberDirectoryIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    @Autowired
    private JdbcOrganizationMemberDirectoryRepositoryAdapter repository;

    @Autowired
    private JdbcTemplate jdbc;

    private OrganizationId organizationId;
    private OrganizationId foreignOrganizationId;
    private UUID grantorPrincipal;

    @BeforeEach
    void seedScope() {
        // user_account and the grant tables hold rows tied to this fixture set directly.
        jdbc.execute(
                "TRUNCATE TABLE crewscope.organization, crewscope.user_account CASCADE");
        organizationId = OrganizationId.generate();
        foreignOrganizationId = OrganizationId.generate();
        seedOrganization(organizationId, "A01 Member Organization");
        seedOrganization(foreignOrganizationId, "A01 Foreign Organization");
        grantorPrincipal = seedPrincipal(organizationId, "A01 Grantor");
    }

    /**
     * Only this organization's ACTIVE bindings appear; the row carries the account identity and
     * status with the ACTIVE roles only, and the display name sorts on the byte-stable key.
     */
    @Test
    void listsActiveBindingsWithAccountIdentityActiveRolesAndAccountStatus() {
        UUID alicePrincipal = seedPrincipal(organizationId, "Alice");
        UUID aliceAccount = seedAccount("alice", "Alice", "ACTIVE");
        seedBinding(aliceAccount, organizationId, alicePrincipal, "ACTIVE");
        seedGrant(organizationId, alicePrincipal, "ORG_AUDITOR", "ACTIVE");
        seedGrant(organizationId, alicePrincipal, "ORG_ADMIN", "REVOKED");

        UUID bobPrincipal = seedPrincipal(organizationId, "Bob");
        UUID bobAccount = seedAccount("bob", "Bob", "LOCKED");
        seedBinding(bobAccount, organizationId, bobPrincipal, "ACTIVE");

        // A DISABLED binding of this organization stays out of the roster.
        UUID gonePrincipal = seedPrincipal(organizationId, "Archived Member");
        UUID goneAccount = seedAccount("archived", "Archived Member", "ACTIVE");
        seedBinding(goneAccount, organizationId, gonePrincipal, "DISABLED");

        // Another organization's member is invisible here.
        UUID foreignPrincipal = seedPrincipal(foreignOrganizationId, "Foreign Member");
        UUID foreignAccount = seedAccount("foreign", "Foreign Member", "ACTIVE");
        seedBinding(foreignAccount, foreignOrganizationId, foreignPrincipal, "ACTIVE");

        List<OrganizationMemberDirectoryEntry> items = search(prefixless(50)).items();

        assertEquals(List.of("Alice", "Bob"), items.stream()
                        .map(OrganizationMemberDirectoryEntry::displayName).toList(),
                "only this organization's ACTIVE bindings are listed");
        OrganizationMemberDirectoryEntry alice = items.get(0);
        assertEquals(aliceAccount, alice.accountId().value());
        assertEquals(alicePrincipal, alice.principalId().value());
        assertEquals("alice", alice.username());
        assertEquals(io.crewscope.domain.identity.AccountStatus.ACTIVE, alice.status());
        assertEquals(List.of("ORG_AUDITOR"), alice.orgRoles(),
                "a revoked grant never appears as an active role");
        assertEquals(io.crewscope.domain.identity.AccountStatus.LOCKED, items.get(1).status(),
                "the account status is the governance face of the row");
    }

    /** The prefix matches the display name or the username, case-insensitively and literally. */
    @Test
    void prefixMatchesDisplayNameOrUsernameWithoutWildcards() {
        seedMember("Wang Wei", "ww-account");
        seedMember("Zoe", "zed-account");
        seedMember("w_x", "literal-account");

        assertEquals(Set.of("Wang Wei"), names("ww"),
                "the username prefix narrows the roster");
        assertEquals(Set.of("Wang Wei"), names("wa"),
                "the display-name prefix narrows the roster");
        assertEquals(Set.of("w_x"), names("w_"),
                "an underscore in the prefix is a literal, not a single-character wildcard");
    }

    /**
     * Thirty members page through completely — offset after offset, then cursor after cursor —
     * with no duplicate, no gap and one shared ordering, and a truncating offset page carries
     * both continuations while the final page carries none.
     */
    @Test
    void pagesTheWholeSetInBothModesWithOneOrdering() {
        for (int index = 0; index < 30; index++) {
            seedMember("Member %03d".formatted(index), "member%03d".formatted(index));
        }
        Comparator<OrganizationMemberDirectoryEntry> ordering = Comparator
                .comparing((OrganizationMemberDirectoryEntry entry) ->
                        entry.displayName().toLowerCase(Locale.ROOT))
                .thenComparing(entry -> entry.principalId().toString());

        List<OrganizationMemberDirectoryEntry> byOffset = new ArrayList<>();
        int offset = 0;
        OptionalInt next;
        do {
            OrganizationMemberDirectoryPage page = search(new OrganizationMemberDirectoryQuery(
                    organizationId, Optional.empty(), Optional.empty(), offset, 10));
            byOffset.addAll(page.items());
            next = page.nextOffset();
            offset = next.orElse(0);
            assertEquals(next.isPresent(), page.nextCursor().isPresent(),
                    "a truncating offset page always carries the keyset tail too");
        } while (next.isPresent());

        List<OrganizationMemberDirectoryEntry> byCursor = new ArrayList<>();
        Optional<OrganizationMemberDirectoryCursor> cursor = Optional.empty();
        do {
            OrganizationMemberDirectoryPage page = search(new OrganizationMemberDirectoryQuery(
                    organizationId, Optional.empty(), cursor, 0, 10));
            byCursor.addAll(page.items());
            cursor = page.nextCursor();
        } while (cursor.isPresent());

        assertEquals(30, byOffset.size(), "every member is reachable");
        assertEquals(byOffset, byCursor, "both paging modes walk one ordering");
        assertEquals(-1, Integer.signum(
                ordering.compare(byOffset.get(0), byOffset.get(byOffset.size() - 1))),
                "the byte key with its id tie-breaker is total");
        assertEquals(30, byOffset.stream()
                .map(OrganizationMemberDirectoryEntry::principalId).distinct().count());
    }

    private OrganizationMemberDirectoryPage search(OrganizationMemberDirectoryQuery query) {
        return repository.search(query);
    }

    private OrganizationMemberDirectoryQuery prefixless(int limit) {
        return new OrganizationMemberDirectoryQuery(
                organizationId, Optional.empty(), Optional.empty(), 0, limit);
    }

    private Set<String> names(String prefix) {
        return search(new OrganizationMemberDirectoryQuery(
                        organizationId, Optional.of(prefix), Optional.empty(), 0, 50))
                .items().stream()
                .map(OrganizationMemberDirectoryEntry::displayName)
                .collect(Collectors.toSet());
    }

    private void seedOrganization(OrganizationId id, String name) {
        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, ?, 'ACTIVE')",
                id.value(), name);
    }

    private UUID seedPrincipal(OrganizationId organization, String displayName) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO crewscope.principal (id, organization_id, principal_type,"
                        + " display_name, status) VALUES (?, ?, 'USER', ?, 'ACTIVE')",
                id, organization.value(), displayName);
        return id;
    }

    private UUID seedAccount(String username, String displayName, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO crewscope.user_account (
                    id, username, username_normalized, email, email_normalized,
                    display_name, status)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                id, username, username.toLowerCase(Locale.ROOT),
                username + "@example.com", username + "@example.com", displayName, status);
        return id;
    }

    private void seedBinding(
            UUID account, OrganizationId organization, UUID principal, String status) {
        jdbc.update(
                """
                INSERT INTO crewscope.account_organization_binding (
                    id, account_id, organization_id, principal_id, status)
                VALUES (?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), account, organization.value(), principal, status);
    }

    private void seedGrant(
            OrganizationId organization, UUID principal, String role, String status) {
        boolean revoked = "REVOKED".equals(status);
        jdbc.update(
                """
                INSERT INTO crewscope.organization_role_grant (
                    id, organization_id, principal_id, role, status, granted_by_principal_id,
                    revoked_at, revoked_by_principal_id,
                    created_by_principal_id, updated_by_principal_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), organization.value(), principal, role, status,
                grantorPrincipal,
                revoked ? java.sql.Timestamp.from(java.time.Instant.now()) : null,
                revoked ? grantorPrincipal : null,
                grantorPrincipal, grantorPrincipal);
    }

    /** @return the account id, for assertions tying the roster row back to its account. */
    private UUID seedMember(String displayName, String username) {
        UUID principal = seedPrincipal(organizationId, displayName);
        UUID account = seedAccount(username, displayName, "ACTIVE");
        seedBinding(account, organizationId, principal, "ACTIVE");
        return account;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(JdbcOrganizationMemberDirectoryRepositoryAdapter.class)
    static class TestApplication {}
}
