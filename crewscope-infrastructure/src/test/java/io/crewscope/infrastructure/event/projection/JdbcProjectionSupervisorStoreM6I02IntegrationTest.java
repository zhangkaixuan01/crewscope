package io.crewscope.infrastructure.event.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real PostgreSQL takeover, Fencing, Cursor and cleanup guards for M6-I02. */
@SpringBootTest(
        classes = JdbcProjectionSupervisorStoreM6I02IntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "crewscope.projection.supervisor.enabled=false"
        })
class JdbcProjectionSupervisorStoreM6I02IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final UtcTimestamp NOW = UtcTimestamp.from(
            Instant.parse("2026-08-26T07:00:00Z"));

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID organizationId;
    private JdbcProjectionSupervisorStore store;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        jdbc.update(
                "DELETE FROM crewscope.projection_definition WHERE projection_name = 'test-view'");
        organizationId = UUID.randomUUID();
        UUID principalId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Org', 'ACTIVE')",
                organizationId);
        jdbc.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, visibility, status
                ) VALUES (?, ?, 'USER', 'Admin', 'ORGANIZATION', 'ACTIVE')
                """,
                principalId, organizationId);
        seedRegistry(principalId);
        store = new JdbcProjectionSupervisorStore(jdbc, transactionManager);
    }

    @Test
    void onlyOneInstanceOwnsShadowAndExpiredLeaseGetsHigherFence() {
        List<ProjectionSupervisorClaim> first = store.claim(
                "node-a", NOW, Duration.ofSeconds(30), 10);

        assertEquals(1, first.size());
        assertEquals(1, first.get(0).workerFencingToken());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.projection_worker_claim", Integer.class));
        assertEquals(0, store.claim("node-b", NOW, Duration.ofSeconds(30), 10).size());

        UtcTimestamp expiredAt = UtcTimestamp.from(NOW.value().plusSeconds(31));
        assertEquals(1, store.recoverExpired(expiredAt));
        assertEquals(0, jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM crewscope.projection_worker_claim
                WHERE worker_role = 'SHADOW'
                  AND (owner_id IS NOT NULL OR lease_expires_at IS NOT NULL
                       OR heartbeat_at IS NOT NULL)
                """,
                Integer.class));
        ProjectionSupervisorClaim takeover = store.claim(
                "node-b", expiredAt, Duration.ofSeconds(30), 10).get(0);

        assertEquals(2, takeover.workerFencingToken());
        assertFalse(store.saveProgress(
                first.get(0), Optional.empty(), true, expiredAt, Duration.ofSeconds(30)));
        assertTrue(store.saveProgress(
                takeover, Optional.empty(), true, expiredAt, Duration.ofSeconds(30)));
        assertEquals(0, jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM crewscope.projection_worker_claim
                WHERE worker_role = 'SHADOW' AND status = 'CAUGHT_UP'
                  AND (owner_id IS NOT NULL OR lease_expires_at IS NOT NULL
                       OR heartbeat_at IS NOT NULL)
                """,
                Integer.class));
    }

    @Test
    void persistsKeysetCursorAndRejectsOldGenerationFence() {
        ProjectionSupervisorClaim claim = store.claim(
                "node-a", NOW, Duration.ofSeconds(30), 10).get(0);
        ProjectionHistoryCursor cursor = new ProjectionHistoryCursor(
                "WORK_ITEM", UUID.randomUUID(), 7, NOW, UUID.randomUUID());

        assertTrue(store.saveProgress(
                claim, Optional.of(cursor), false,
                UtcTimestamp.from(NOW.value().plusSeconds(1)), Duration.ofSeconds(30)));
        assertEquals(cursor.eventId(), jdbc.queryForObject(
                "SELECT cursor_event_id FROM crewscope.projection_worker_claim "
                        + "WHERE worker_role = 'SHADOW'",
                UUID.class));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> jdbc.update(
                """
                UPDATE crewscope.projection_generation
                SET status = 'FAILED', fencing_token = fencing_token + 1,
                    version = version + 1, updated_at = ?
                WHERE organization_id = ? AND projection_name = 'test-view' AND generation = 2
                """,
                NOW.value().plusSeconds(2).atOffset(ZoneOffset.UTC), organizationId));
        assertFalse(store.saveProgress(
                claim, Optional.of(cursor), true,
                UtcTimestamp.from(NOW.value().plusSeconds(3)), Duration.ofSeconds(30)));
    }

    @Test
    void cleanupExcludesActiveGenerationAndPreservesGenerationTombstone() {
        OffsetDateTime old = NOW.value().minus(Duration.ofDays(10)).atOffset(ZoneOffset.UTC);
        jdbc.update(
                """
                INSERT INTO crewscope.projection_generation (
                    organization_id, projection_name, generation, definition_version,
                    status, fencing_token, version, created_at, updated_at
                ) VALUES (?, 'test-view', 3, 1, 'RETIRED', 2, 1, ?, ?)
                """,
                organizationId, old, old);

        assertEquals(1, store.cleanupDue(NOW, Duration.ofDays(7), 10));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.projection_cleanup_receipt "
                        + "WHERE organization_id = ? AND generation = 3",
                Integer.class, organizationId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.projection_generation "
                        + "WHERE organization_id = ? AND generation = 3",
                Integer.class, organizationId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.projection_cleanup_receipt "
                        + "WHERE organization_id = ? AND generation = 1",
                Integer.class, organizationId));
    }

    @Test
    void propertiesFailClosedOnUnsafeBounds() {
        ProjectionSupervisorProperties properties = new ProjectionSupervisorProperties();
        properties.setEnabled(true);
        properties.setPageSize(1_001);
        assertThrows(IllegalStateException.class, properties::validate);
    }

    private void seedRegistry(UUID principalId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update(
                    """
                    INSERT INTO crewscope.projection_definition (
                        projection_name, definition_version, projection_schema_version,
                        canonical_encoder, validator
                    ) VALUES ('test-view', 1, 1, 'test.canonical-v1', 'test.expected-v1')
                    """);
            jdbc.update(
                    """
                    INSERT INTO crewscope.projection_generation (
                        organization_id, projection_name, generation, definition_version,
                        status, fencing_token, version, created_at, updated_at
                    ) VALUES (?, 'test-view', 1, 1, 'ACTIVE', 1, 0, ?, ?)
                    """,
                    organizationId, NOW.toOffsetDateTime(), NOW.toOffsetDateTime());
            jdbc.update(
                    """
                    INSERT INTO crewscope.projection_pointer (
                        organization_id, projection_name, active_generation, version, updated_at
                    ) VALUES (?, 'test-view', 1, 0, ?)
                    """,
                    organizationId, NOW.toOffsetDateTime());
            UUID jobId = UUID.randomUUID();
            jdbc.update(
                    """
                    INSERT INTO crewscope.projection_generation (
                        organization_id, projection_name, generation, definition_version,
                        rebuild_job_id, status, fencing_token, version, created_at, updated_at
                    ) VALUES (?, 'test-view', 2, 1, ?, 'BUILDING', 1, 0, ?, ?)
                    """,
                    organizationId, jobId, NOW.toOffsetDateTime(), NOW.toOffsetDateTime());
            jdbc.update(
                    """
                    INSERT INTO crewscope.projection_rebuild_job (
                        id, organization_id, projection_name, definition_version, generation,
                        requested_by_principal_id, status, version, created_at, updated_at
                    ) VALUES (?, ?, 'test-view', 1, 2, ?, 'BUILDING', 0, ?, ?)
                    """,
                    jobId, organizationId, principalId,
                    NOW.toOffsetDateTime(), NOW.toOffsetDateTime());
        });
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {}
}
