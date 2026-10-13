package io.crewscope.application.quota;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.organization.OrganizationAccessContext;
import io.crewscope.application.organization.OrganizationAuthorization;
import io.crewscope.application.organization.OrganizationRoleGrantRepository;
import io.crewscope.application.quota.OrganizationQuotaUsageQueryRepository.ActualTokenUsage;
import io.crewscope.application.quota.OrganizationQuotaUsageQueryService.QuotaUsageView;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.organization.quota.OrganizationQuotaConfiguration;
import io.crewscope.domain.organization.role.OrganizationRole;
import io.crewscope.domain.organization.role.OrganizationRoleGrant;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Usage view behavior: month resolution and guardrails, empty-state zeros, folded facts. */
class OrganizationQuotaUsageQueryServiceTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-12T08:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final PrincipalId actorId = PrincipalId.generate();
    private final Principal actor = Principal.create(
            actorId,
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            "Admin",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);

    private OrganizationRoleGrantRepository grants;
    private OrganizationQuotaConfigurationRepository configurations;
    private OrganizationQuotaUsageQueryRepository usageFacts;
    private OrganizationQuotaUsageQueryService service;

    @BeforeEach
    void setUp() {
        grants = mock(OrganizationRoleGrantRepository.class);
        configurations = mock(OrganizationQuotaConfigurationRepository.class);
        usageFacts = mock(OrganizationQuotaUsageQueryRepository.class);
        when(usageFacts.countTasksCreatedIn(any(), any(), any())).thenReturn(0L);
        when(usageFacts.findTokenLedger(any(), any())).thenReturn(Optional.empty());
        when(usageFacts.sumActualTokens(any(), any()))
                .thenReturn(new ActualTokenUsage(0, 0, 0, 0));
        when(configurations.findByOrganization(organizationId)).thenReturn(Optional.empty());
        service = new OrganizationQuotaUsageQueryService(
                configurations,
                usageFacts,
                new OrganizationAuthorization(grants),
                () -> NOW,
                ZoneId.of("UTC"));
    }

    @Test
    void aNullMonthResolvesToTheCurrentReportingMonth() {
        asAdmin();

        QuotaUsageView view = service.findUsage(access(), organizationId, null);

        assertEquals("2026-10", view.month());
        assertNull(view.monthlyTaskIntake().limit());
        assertEquals(0, view.monthlyTaskIntake().count());
        assertNull(view.monthlyTokenBudget().limit());
        assertEquals(0, view.monthlyTokenBudget().reservedTokens());
        assertEquals(0, view.monthlyTokenBudget().actualTokens().total());
        assertTrue(!view.materialized());
        var windowStart = ArgumentCaptor.forClass(Instant.class);
        var windowEnd = ArgumentCaptor.forClass(Instant.class);
        verify(usageFacts).countTasksCreatedIn(
                eq(organizationId), windowStart.capture(), windowEnd.capture());
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"), windowStart.getValue());
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), windowEnd.getValue());
    }

    @Test
    void anExplicitPastMonthReadsItsOwnWindow() {
        asAdmin();

        QuotaUsageView view = service.findUsage(access(), organizationId, "2026-09");

        assertEquals("2026-09", view.month());
        verify(usageFacts).countTasksCreatedIn(
                eq(organizationId),
                eq(Instant.parse("2026-09-01T00:00:00Z")),
                eq(Instant.parse("2026-10-01T00:00:00Z")));
        verify(usageFacts).findTokenLedger(organizationId, "2026-09");
        verify(usageFacts).sumActualTokens(organizationId, "2026-09");
    }

    @Test
    void malformedAndFutureMonthsRejectWithTheSameInvalidFieldSignal() {
        asAdmin();

        assertThrows(
                IllegalArgumentException.class,
                () -> service.requireRequestableMonth("2026-13"));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.requireRequestableMonth("2026-1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.requireRequestableMonth("2026-11"));
    }

    @Test
    void aConfiguredMonthFoldsLimitsReservationsAndActualsSideBySide() {
        asAdmin();
        when(configurations.findByOrganization(organizationId)).thenReturn(Optional.of(
                OrganizationQuotaConfiguration.reconstitute(
                        organizationId, 5, 1_000_000L, 2, NOW, actorId, NOW, actorId)));
        when(usageFacts.countTasksCreatedIn(any(), any(), any())).thenReturn(3L);
        when(usageFacts.findTokenLedger(organizationId, "2026-10"))
                .thenReturn(Optional.of(
                        new OrganizationQuotaUsageQueryRepository.TokenLedgerSnapshot(600_000L)));
        when(usageFacts.sumActualTokens(organizationId, "2026-10"))
                .thenReturn(new ActualTokenUsage(100_000L, 50_000L, 25_000L, 175_000L));

        QuotaUsageView view = service.findUsage(access(), organizationId, null);

        assertEquals(5, view.monthlyTaskIntake().limit());
        assertEquals(3, view.monthlyTaskIntake().count());
        assertEquals(1_000_000L, view.monthlyTokenBudget().limit());
        assertEquals(600_000L, view.monthlyTokenBudget().reservedTokens());
        assertEquals(100_000L, view.monthlyTokenBudget().actualTokens().input());
        assertEquals(50_000L, view.monthlyTokenBudget().actualTokens().output());
        assertEquals(25_000L, view.monthlyTokenBudget().actualTokens().cachedInput());
        assertEquals(175_000L, view.monthlyTokenBudget().actualTokens().total());
        assertTrue(view.materialized());
    }

    @Test
    void usageReadsRequireTheSettingsReadCapability() {
        when(grants.findActiveByPrincipal(organizationId, actorId)).thenReturn(List.of());

        assertThrows(
                PolicyDeniedException.class,
                () -> service.findUsage(access(), organizationId, null));
    }

    private OrganizationAccessContext access() {
        return new OrganizationAccessContext(actor);
    }

    private void asAdmin() {
        when(grants.findActiveByPrincipal(organizationId, actorId))
                .thenReturn(List.of(OrganizationRoleGrant.grant(
                        organizationId, actorId, OrganizationRole.ORG_ADMIN, actorId, null, NOW)));
    }
}
