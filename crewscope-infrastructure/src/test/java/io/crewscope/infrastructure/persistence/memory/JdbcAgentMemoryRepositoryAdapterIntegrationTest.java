package io.crewscope.infrastructure.persistence.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.agent.AgentMemoryEntry;
import io.crewscope.domain.agent.AgentMemoryKey;
import io.crewscope.domain.agent.AgentMemoryOwner;
import io.crewscope.domain.agent.AgentMemoryOwnerKey;
import io.crewscope.domain.agent.AgentMemoryPolicy;
import io.crewscope.domain.agent.AgentMemoryPolicyReference;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M10-I02a PostgreSQL proof behind the V58 schema: the generation/TTL/space visibility
 * contract, the FOR UPDATE anti-resurrection check, the cross-space clear receipt and the
 * sweep paths — the exact SQLSTATE-level behavior the application fake replays.
 */
@SpringBootTest(
        classes = JdbcAgentMemoryRepositoryAdapterIntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "crewscope.outbox.enabled=false"
        })
class JdbcAgentMemoryRepositoryAdapterIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-04T09:00:00Z");
    private static final UUID POLICY_ID = AgentMemoryPolicy.DEFAULT_POLICY_ID;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JdbcAgentMemoryRepositoryAdapter memory;

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final AgentProfileId profileId = AgentProfileId.generate();
    private final PrincipalId owner = PrincipalId.generate();
    private AgentMemoryOwnerKey key;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(JdbcAgentMemoryRepositoryAdapter.class)
    static class TestApplication {}

    @BeforeEach
    void seedTenant() {
        jdbc.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        UUID workspaceId = UUID.randomUUID();
        UUID agentPrincipalId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Memory Org', 'ACTIVE')",
                organizationId.value());
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status) VALUES (?, ?, 'Memory Team', 'ACTIVE')",
                teamId.value(), organizationId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status)
                VALUES (?, ?, ?, 'TEAM', 'Memory Workspace', 'ACTIVE')
                """,
                workspaceId, organizationId.value(), teamId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, team_id, principal_type, display_name, status)
                VALUES (?, ?, ?, 'TEAM_AGENT', 'Memory Agent', 'ACTIVE')
                """,
                agentPrincipalId, organizationId.value(), teamId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, status)
                VALUES (?, ?, 'USER', 'Memory owner', 'ACTIVE')
                """,
                owner.value(), organizationId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.agent_profile (
                    id, organization_id, team_id, workspace_id, agent_principal_id,
                    profile_type, status, created_by_principal_id, updated_by_principal_id)
                VALUES (?, ?, ?, ?, ?, 'TEAM', 'ACTIVE', ?, ?)
                """,
                profileId.value(), organizationId.value(), teamId.value(),
                workspaceId, agentPrincipalId, owner.value(), owner.value());
        key = new AgentMemoryOwnerKey(organizationId, teamId, profileId, owner);
    }

    @Test
    void ensureOwnerIsIdempotentAndStartsAtGenerationZero() {
        AgentMemoryOwner first = memory.ensureOwner(key, owner, NOW);
        AgentMemoryOwner second = memory.ensureOwner(key, owner, NOW);

        assertEquals(0, first.clearanceGeneration());
        assertEquals(first, second);
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.agent_memory_owner", Long.class);
        assertEquals(1L, rows);
        assertEquals(Optional.empty(), memory.findOwner(
                new AgentMemoryOwnerKey(
                        OrganizationId.generate(), teamId, profileId, owner)));
    }

    @Test
    void findVisibleFiltersGenerationTtlAndPolicySpaceInKeyOrder() {
        seedRow("code-style", "tabs", 0, POLICY_ID, 1, plusDays(90));
        seedRow("reply-language", "简体中文", 0, POLICY_ID, 1, plusDays(90));
        seedRow("reply-language", "another space", 0, POLICY_ID, 2, plusDays(90));
        // The space key is generation-free: a stale-generation survivor of a raced clear can
        // only hold a different key, and it must stay invisible to the current generation.
        seedRow("stale-generation", "cleared earlier", 1, POLICY_ID, 1, plusDays(90));
        seedRow("expired", "too old", 0, POLICY_ID, 1, minusDays(1));

        List<AgentMemoryEntry> visible = memory.findVisible(
                key, AgentMemoryPolicy.defaults().reference(), NOW);

        assertEquals(
                List.of("code-style", "reply-language"),
                visible.stream().map(entry -> entry.memoryKey().value()).toList());
        assertEquals("简体中文", visible.get(1).value());
    }

    @Test
    void upsertInsertsThenOverwritesInPlaceWithoutASecondSlot() {
        AgentMemoryEntry inserted = memory.upsert(entry("code-style", "spaces", 0));
        AgentMemoryEntry overwritten =
                memory.upsert(entry("code-style", "tabs", 0));

        assertEquals(0, inserted.version());
        assertEquals(1, overwritten.version());
        assertEquals("tabs", overwritten.value());
        List<AgentMemoryEntry> visible = memory.findVisible(
                key, AgentMemoryPolicy.defaults().reference(), NOW);
        assertEquals(1, visible.size());
        assertEquals("tabs", visible.get(0).value());
        assertEquals(1, visible.get(0).version());
    }

    @Test
    void upsertRejectsAStaleClearanceGenerationUnderTheOwnerLock() {
        memory.ensureOwner(key, owner, NOW);
        AgentMemoryClearanceReceipt cleared = clear();

        // A write still carrying the pre-clear generation must not resurrect anything.
        AgentMemoryEntry stale =
                entry("code-style", "revival attempt", cleared.previousGeneration());
        OptimisticLockConflictException rejected =
                assertThrows(OptimisticLockConflictException.class, () -> memory.upsert(stale));

        assertEquals("OPTIMISTIC_LOCK_CONFLICT", rejected.error().code().name());
        assertTrue(memory.findVisible(key, AgentMemoryPolicy.defaults().reference(), NOW).isEmpty(),
                "the cleared space stays empty");
        Long ownerRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.agent_memory_entry", Long.class);
        assertEquals(0L, ownerRows);

        // The next write under the current generation succeeds; the space is usable again.
        AgentMemoryEntry fresh = memory.upsert(entry("code-style", "fresh", cleared.nextGeneration()));
        assertEquals("fresh", memory.findVisible(
                key, AgentMemoryPolicy.defaults().reference(), NOW).get(0).value());
        assertEquals(fresh.clearanceGeneration(), cleared.nextGeneration());
    }

    @Test
    void clearCountsAcrossPolicySpacesAndRepeatsHarmlessly() {
        memory.upsert(entry("reply-language", "中文", 0));
        memory.upsert(spaceEntry("reply-language", "Deutsch", POLICY_ID, 2));
        memory.upsert(spaceEntry("old-space", "legacy", UUID.randomUUID(), 1));

        AgentMemoryClearanceReceipt first = clear();
        assertEquals(3, first.deleted);
        assertEquals(1, first.nextGeneration());

        AgentMemoryClearanceReceipt repeated = clear();
        assertEquals(0, repeated.deleted);
        assertEquals(2, repeated.nextGeneration());
        assertTrue(memory.findVisible(key, AgentMemoryPolicy.defaults().reference(), NOW).isEmpty());
    }

    @Test
    void renewSlidesOnlyUnexpiredCurrentGenerationEntries() {
        memory.upsert(entry("reply-language", "中文", 0));
        UtcTimestamp extended = UtcTimestamp.from(NOW.value().plus(Duration.ofDays(120)));

        assertTrue(memory.renew(
                key, AgentMemoryPolicy.defaults().reference(),
                new AgentMemoryKey("reply-language"), extended, owner, NOW));
        AgentMemoryEntry renewed = memory.findVisible(
                key, AgentMemoryPolicy.defaults().reference(), NOW).get(0);
        assertEquals(extended, renewed.expiresAt());
        assertEquals(1, renewed.version());

        seedRow("expired", "too old", 0, POLICY_ID, 1, minusDays(1));
        assertFalse(memory.renew(
                key, AgentMemoryPolicy.defaults().reference(),
                new AgentMemoryKey("expired"), extended, owner, NOW));
        assertFalse(memory.renew(
                key, AgentMemoryPolicy.defaults().reference(),
                new AgentMemoryKey("missing"), extended, owner, NOW));
    }

    @Test
    void sweepDeletesExpiredAndStaleRowsButKeepsTheLivingSpace() {
        memory.upsert(entry("living", "current value", 0));
        seedRow("expired", "too old", 0, POLICY_ID, 1, minusDays(1));
        seedRow("stale", "cleared earlier", 0, POLICY_ID, 1, plusDays(90));
        jdbc.update(
                "UPDATE crewscope.agent_memory_entry SET clearance_generation = 5 WHERE memory_key = 'stale'");

        long swept = memory.deleteSweepable(NOW, 500);

        assertEquals(2, swept);
        List<String> survivors = memory.findVisible(
                key, AgentMemoryPolicy.defaults().reference(), NOW).stream()
                .map(entry -> entry.memoryKey().value()).toList();
        assertEquals(List.of("living"), survivors);
    }

    // ------------------------------------------------------------------ helpers

    private static UtcTimestamp plusDays(long days) {
        return UtcTimestamp.from(NOW.value().plus(Duration.ofDays(days)));
    }

    private static UtcTimestamp minusDays(long days) {
        return UtcTimestamp.from(NOW.value().minus(Duration.ofDays(days)));
    }

    private AgentMemoryEntry entry(String memoryKey, String value, long generation) {
        return AgentMemoryEntry.write(
                key,
                AgentMemoryPolicy.defaults().reference(),
                new AgentMemoryKey(memoryKey),
                value,
                generation,
                UtcTimestamp.from(NOW.value().plus(Duration.ofDays(90))),
                owner,
                NOW);
    }

    private AgentMemoryEntry spaceEntry(String memoryKey, String value, UUID policyId, long policyVersion) {
        return AgentMemoryEntry.write(
                key,
                new AgentMemoryPolicyReference(policyId, policyVersion),
                new AgentMemoryKey(memoryKey),
                value,
                0,
                UtcTimestamp.from(NOW.value().plus(Duration.ofDays(90))),
                owner,
                NOW);
    }

    private void seedRow(
            String memoryKey, String value, long generation,
            UUID policyId, long policyVersion, UtcTimestamp expiresAt) {
        memory.ensureOwner(key, owner, NOW);
        jdbc.update(
                """
                INSERT INTO crewscope.agent_memory_entry (
                    id, organization_id, team_id, agent_profile_id, owner_principal_id,
                    policy_id, policy_version, memory_key, value, clearance_generation,
                    version, expires_at, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), key.organizationId().value(), key.teamId().value(),
                key.agentProfileId().value(), key.ownerPrincipalId().value(),
                policyId, policyVersion, memoryKey, value, generation,
                expiresAt.toOffsetDateTime(), NOW.toOffsetDateTime(), owner.value(),
                NOW.toOffsetDateTime(), owner.value());
    }

    /** clear() receipt pair: the generation before, after, and how many rows went. */
    private record AgentMemoryClearanceReceipt(
            long previousGeneration, long nextGeneration, int deleted) {}

    private AgentMemoryClearanceReceipt clear() {
        long before = memory.findOwner(key).map(AgentMemoryOwner::clearanceGeneration).orElse(0L);
        var receipt = memory.clear(key, owner, NOW);
        return new AgentMemoryClearanceReceipt(before, receipt.clearanceGeneration(),
                (int) receipt.clearedCount());
    }
}
