package io.crewscope.infrastructure.persistence.organization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.organization.OrganizationRoleGrantRepository;
import io.crewscope.application.organization.OrganizationSettingRepository;
import io.crewscope.domain.organization.role.OrganizationRole;
import io.crewscope.domain.organization.role.OrganizationRoleGrant;
import io.crewscope.domain.organization.role.OrganizationRoleGrantConflictException;
import io.crewscope.domain.organization.setting.OrganizationSetting;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import io.crewscope.infrastructure.testsupport.SharedPersistenceTestApplication;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL contract for the V68 adapters: per-request active-grant requery, the partial unique
 * index as the duplicate-active boundary, optimistic versioning, and the first-row settings
 * materialization.
 */
@SpringBootTest(
        classes = SharedPersistenceTestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.open-in-view=false"
        })
class OrganizationPersistenceM12A01IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final UtcTimestamp NOW =
            UtcTimestamp.from(Instant.parse("2026-10-12T12:00:00Z"));
    private static final UtcTimestamp LATER =
            UtcTimestamp.from(Instant.parse("2026-10-12T12:01:00Z"));

    @Autowired private OrganizationRoleGrantRepository grants;
    @Autowired private OrganizationSettingRepository settings;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private OrganizationId organizationId;
    private PrincipalId ownerId;
    private PrincipalId auditorId;

    @BeforeEach
    void resetBusinessData() {
        jdbc.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        organizationId = OrganizationId.generate();
        ownerId = PrincipalId.generate();
        auditorId = PrincipalId.generate();
        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Org', 'ACTIVE')",
                organizationId.value());
        jdbc.update(
                "INSERT INTO crewscope.principal (id, organization_id, principal_type,"
                        + " display_name, status) VALUES (?, ?, 'USER', 'Owner', 'ACTIVE')",
                ownerId.value(), organizationId.value());
        jdbc.update(
                "INSERT INTO crewscope.principal (id, organization_id, principal_type,"
                        + " display_name, status) VALUES (?, ?, 'USER', 'Auditor', 'ACTIVE')",
                auditorId.value(), organizationId.value());
    }

    @Test
    void createsGrantsAndRequeriesActiveOnesPerRequest() {
        grants.create(OrganizationRoleGrant.grant(
                organizationId, ownerId, OrganizationRole.ORG_OWNER, ownerId, null, NOW));
        OrganizationRoleGrant storedAuditor = grants.create(OrganizationRoleGrant.grant(
                organizationId, auditorId, OrganizationRole.ORG_AUDITOR, ownerId, null, NOW));
        OrganizationRoleGrant revoked = storedAuditor.revoke(0, ownerId, "rotation", LATER);
        grants.update(revoked);

        assertEquals(1, grants.findActiveByPrincipal(organizationId, ownerId).size());
        assertTrue(grants.findActiveByPrincipal(organizationId, auditorId).isEmpty());
        assertEquals(1, grants.countActiveOwners(organizationId));
        assertTrue(grants.hasActiveOwner(organizationId));
        // The organization listing keeps revoked history behind the active face.
        assertEquals(2, grants.findByOrganization(organizationId).size());
        assertEquals(
                OrganizationRoleGrant.GrantStatus.ACTIVE,
                grants.findByOrganization(organizationId).get(0).status());
        assertEquals(
                OrganizationRoleGrant.GrantStatus.REVOKED,
                grants.findByOrganization(organizationId).get(1).status());
        assertTrue(
                grants.findByIdForUpdate(organizationId, revoked.id()).orElseThrow().reason()
                        .isPresent());
    }

    @Test
    void aSecondActiveGrantForTheSameRoleSurfacesAsTheDomainConflict() {
        grants.create(OrganizationRoleGrant.grant(
                organizationId, auditorId, OrganizationRole.ORG_AUDITOR, ownerId, null, NOW));

        assertThrows(
                OrganizationRoleGrantConflictException.class,
                () -> grants.create(OrganizationRoleGrant.grant(
                        organizationId, auditorId, OrganizationRole.ORG_AUDITOR, ownerId,
                        "duplicate", NOW)));
        // A different role for the same principal is a legal second grant.
        grants.create(OrganizationRoleGrant.grant(
                organizationId, auditorId, OrganizationRole.ORG_ADMIN, ownerId, null, NOW));
    }

    @Test
    void updateFailsStaleVersionsUnderOptimisticLocking() {
        OrganizationRoleGrant stored = grants.create(OrganizationRoleGrant.grant(
                organizationId, ownerId, OrganizationRole.ORG_OWNER, ownerId, null, NOW));
        OrganizationRoleGrant revoked =
                stored.revoke(0, ownerId, "handover", LATER);
        grants.update(revoked);

        // The already-applied successor cannot be persisted twice.
        assertThrows(OptimisticLockConflictException.class, () -> grants.update(revoked));
    }

    @Test
    void settingsMaterializeOnFirstWriteAndUpdateUnderOptimisticVersioning() {
        assertTrue(settings.findByOrganization(organizationId).isEmpty());

        settings.insert(OrganizationSetting.initial(organizationId, 90, ownerId, NOW));
        OrganizationSetting stored =
                settings.findByOrganization(organizationId).orElseThrow();
        assertEquals(90, stored.auditEventRetentionDays().orElseThrow());
        assertEquals(0, stored.version());

        OrganizationSetting changed =
                stored.changeRetention(0, null, ownerId, LATER);
        settings.update(changed);
        assertTrue(settings.findByOrganization(organizationId).orElseThrow()
                .auditEventRetentionDays().isEmpty());

        assertThrows(OptimisticLockConflictException.class, () -> settings.update(changed));
    }

    /**
     * Reproduction shape of the concurrent double-revocation window: two revocations of
     * DIFFERENT owner grants hold unrelated row locks, so only the per-organization advisory
     * lock serializes them. The second transaction must observe the first one's committed
     * revocation (one remaining owner), never a pre-commit snapshot of two.
     */
    @Test
    void concurrentOwnerRevocationsSerializeThroughTheAdvisoryLock() throws Exception {
        PrincipalId second = PrincipalId.generate();
        jdbc.update(
                "INSERT INTO crewscope.principal (id, organization_id, principal_type,"
                        + " display_name, status) VALUES (?, ?, 'USER', 'Second Owner', 'ACTIVE')",
                second.value(), organizationId.value());
        OrganizationRoleGrant firstGrant = grants.create(OrganizationRoleGrant.grant(
                organizationId, ownerId, OrganizationRole.ORG_OWNER, ownerId, null, NOW));
        OrganizationRoleGrant secondGrant = grants.create(OrganizationRoleGrant.grant(
                organizationId, second, OrganizationRole.ORG_OWNER, ownerId, null, NOW));

        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        AtomicLong secondCount = new AtomicLong(-1);

        Thread first = new Thread(() -> transactions.executeWithoutResult(status -> {
            grants.lockAuthorizationChanges(organizationId);
            firstLocked.countDown();
            try {
                releaseFirst.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            grants.update(firstGrant.revoke(0, ownerId, "concurrent handover A", LATER));
        }));
        Thread secondThread = new Thread(() -> transactions.executeWithoutResult(status -> {
            // Blocks on the advisory lock until the first transaction commits.
            grants.lockAuthorizationChanges(organizationId);
            secondCount.set(grants.countActiveOwners(organizationId));
            status.setRollbackOnly();
            secondDone.countDown();
        }));

        first.start();
        assertTrue(firstLocked.await(10, TimeUnit.SECONDS), "first revocation never locked");
        secondThread.start();
        // Still blocked on the lock while the first transaction holds it open.
        assertFalse(secondDone.await(500, TimeUnit.MILLISECONDS),
                "second revocation read past a held authorization lock");
        releaseFirst.countDown();
        first.join(10_000);
        secondThread.join(10_000);
        assertFalse(first.isAlive());
        assertFalse(secondThread.isAlive());

        assertEquals(1, secondCount.get(),
                "the serialized second count must see the committed first revocation");
        assertEquals(1, grants.countActiveOwners(organizationId));
        assertEquals(
                OrganizationRoleGrant.GrantStatus.REVOKED,
                grants.findByIdForUpdate(organizationId, firstGrant.id()).orElseThrow().status());
        assertEquals(
                OrganizationRoleGrant.GrantStatus.ACTIVE,
                grants.findByIdForUpdate(organizationId, secondGrant.id()).orElseThrow().status());
    }
}
