package io.crewscope.domain.organization.quota;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import org.junit.jupiter.api.Test;

/** Quota configuration guardrails: dimension validation, optimistic versioning, null clearing. */
class OrganizationQuotaConfigurationTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-12T08:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final PrincipalId actorId = PrincipalId.generate();

    @Test
    void initialKeepsBothDimensionsUnconfiguredWithNulls() {
        OrganizationQuotaConfiguration configuration =
                OrganizationQuotaConfiguration.initial(organizationId, null, null, actorId, NOW);

        assertTrue(configuration.monthlyTaskIntakeLimit().isEmpty());
        assertTrue(configuration.monthlyTokenBudget().isEmpty());
        assertEquals(0, configuration.version());
    }

    @Test
    void initialAcceptsTheLowerBoundLimitOfOne() {
        OrganizationQuotaConfiguration configuration =
                OrganizationQuotaConfiguration.initial(organizationId, 1, 1L, actorId, NOW);

        assertEquals(1, configuration.monthlyTaskIntakeLimit().orElseThrow());
        assertEquals(1L, configuration.monthlyTokenBudget().orElseThrow());
    }

    @Test
    void initialRejectsZeroLimitsAsConfigurationErrors() {
        assertThrows(
                DomainValidationException.class,
                () -> OrganizationQuotaConfiguration.initial(
                        organizationId, 0, null, actorId, NOW));
        assertThrows(
                DomainValidationException.class,
                () -> OrganizationQuotaConfiguration.initial(
                        organizationId, null, 0L, actorId, NOW));
    }

    @Test
    void reconstituteRejectsANegativeVersion() {
        assertThrows(
                DomainValidationException.class,
                () -> OrganizationQuotaConfiguration.reconstitute(
                        organizationId, 5, 1000L, -1, NOW, actorId, NOW, actorId));
    }

    @Test
    void changeLimitsAdvancesTheVersionAndClearsDimensionsWithNulls() {
        OrganizationQuotaConfiguration stored = OrganizationQuotaConfiguration.reconstitute(
                organizationId, 5, 1000L, 3, NOW, actorId, NOW, actorId);

        OrganizationQuotaConfiguration changed = stored.changeLimits(
                3, null, 5000L, actorId, NOW);

        assertEquals(4, changed.version());
        assertTrue(changed.monthlyTaskIntakeLimit().isEmpty());
        assertEquals(5000L, changed.monthlyTokenBudget().orElseThrow());
        // Creation facts survive a change; only the update side moves.
        assertEquals(stored.createdAt(), changed.createdAt());
        assertEquals(stored.createdBy(), changed.createdBy());
    }

    @Test
    void changeLimitsFailsAStaleExpectedVersion() {
        OrganizationQuotaConfiguration stored = OrganizationQuotaConfiguration.reconstitute(
                organizationId, 5, 1000L, 3, NOW, actorId, NOW, actorId);

        OptimisticLockConflictException conflict = assertThrows(
                OptimisticLockConflictException.class,
                () -> stored.changeLimits(2, 10, 2000L, actorId, NOW));
        assertEquals(
                "ORGANIZATION_QUOTA_CONFIGURATION",
                conflict.error().details().get("aggregateType"));
        assertEquals("3", conflict.error().details().get("actualVersion"));
    }
}
