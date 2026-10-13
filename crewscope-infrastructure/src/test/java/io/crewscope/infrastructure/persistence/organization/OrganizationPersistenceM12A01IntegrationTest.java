package io.crewscope.infrastructure.persistence.organization;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.crewscope.infrastructure.transaction.SpringTransactionExecutor;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PostgreSQL contract for the V68 adapters: per-request active-grant requery, the partial unique
 * index as the duplicate-active boundary, optimistic versioning, and the first-row settings
 * materialization.
 */
@SpringBootTest(
        classes = OrganizationPersistenceM12A01IntegrationTest.TestApplication.class,
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

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
        JdbcOrganizationRoleGrantRepositoryAdapter.class,
        JdbcOrganizationSettingRepositoryAdapter.class,
        SpringTransactionExecutor.class
    })
    static class TestApplication {}
}
