package io.crewscope.application.quota;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.crewscope.application.quota.OrganizationQuotaAdmissionService.OrganizationQuotaAdmissionRequest;
import io.crewscope.application.quota.OrganizationTaskIntakeQuotaCounter.Decision;
import io.crewscope.domain.organization.quota.OrganizationQuotaConfiguration;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Admission decision matrix (ADR-037 §2): unconfigured organizations must take the zero-cost
 * path, denials carry the task-creation action text, and storage failures propagate so the
 * transaction fails closed instead of becoming a false 403.
 */
class OrganizationQuotaAdmissionServiceTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-12T08:00:00Z");
    private static final long MAX_TOKENS = 200_000L;

    private final OrganizationId organizationId = OrganizationId.generate();
    private final PrincipalId actorId = PrincipalId.generate();

    private OrganizationQuotaConfigurationRepository configurations;
    private OrganizationTaskIntakeQuotaCounter intakeCounter;
    private OrganizationTokenBudgetLedger tokenLedger;
    private OrganizationQuotaAdmissionService service;

    @BeforeEach
    void setUp() {
        configurations = mock(OrganizationQuotaConfigurationRepository.class);
        intakeCounter = mock(OrganizationTaskIntakeQuotaCounter.class);
        tokenLedger = mock(OrganizationTokenBudgetLedger.class);
        service = new OrganizationQuotaAdmissionService(
                configurations, intakeCounter, tokenLedger, ZoneId.of("UTC"));
    }

    @Test
    void anAbsentRowTakesTheZeroCostPathWithoutTouchingEitherPort() {
        when(configurations.findByOrganization(organizationId)).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> service.requireAdmission(request(MAX_TOKENS)));

        verifyNoInteractions(intakeCounter);
        verifyNoInteractions(tokenLedger);
    }

    @Test
    void aRowWithBothDimensionsNullIsStillTheUnconfiguredDefault() {
        stored(null, null);

        assertDoesNotThrow(() -> service.requireAdmission(request(MAX_TOKENS)));

        verifyNoInteractions(intakeCounter);
        verifyNoInteractions(tokenLedger);
    }

    @Test
    void anAvailableIntakeAdmitsWithoutTouchingTheLedger() {
        stored(5, null);
        when(intakeCounter.check(any())).thenReturn(Decision.AVAILABLE);

        assertDoesNotThrow(() -> service.requireAdmission(request(MAX_TOKENS)));

        verify(intakeCounter).check(any());
        verifyNoInteractions(tokenLedger);
    }

    @Test
    void anExhaustedIntakeDeniesWithTheTaskCreationAction() {
        stored(5, null);
        when(intakeCounter.check(any())).thenReturn(Decision.INTAKE_LIMIT_REACHED);

        PolicyDeniedException denied = assertThrows(
                PolicyDeniedException.class, () -> service.requireAdmission(request(MAX_TOKENS)));

        assertEquals(
                OrganizationQuotaAdmissionService.INTAKE_LIMIT_ACTION,
                denied.error().details().get("action"));
        verifyNoInteractions(tokenLedger);
    }

    @Test
    void aReservedTokenBudgetAdmitsWithoutCountingIntake() {
        stored(null, 1_000_000L);
        when(tokenLedger.reserve(any()))
                .thenReturn(OrganizationTokenBudgetLedger.Decision.RESERVED);

        assertDoesNotThrow(() -> service.requireAdmission(request(MAX_TOKENS)));

        verify(tokenLedger).reserve(any(OrganizationTokenBudgetLedger.ReservationRequest.class));
        verifyNoInteractions(intakeCounter);
    }

    @Test
    void anExhaustedTokenBudgetDeniesWithTheReservationAction() {
        stored(null, 1_000_000L);
        when(tokenLedger.reserve(any()))
                .thenReturn(OrganizationTokenBudgetLedger.Decision.BUDGET_EXCEEDED);

        PolicyDeniedException denied = assertThrows(
                PolicyDeniedException.class, () -> service.requireAdmission(request(MAX_TOKENS)));

        assertEquals(
                OrganizationQuotaAdmissionService.TOKEN_BUDGET_ACTION,
                denied.error().details().get("action"));
        verifyNoInteractions(intakeCounter);
    }

    @Test
    void bothDimensionsArbitrateIntakeFirstThenTheLedger() {
        stored(5, 1_000_000L);
        when(intakeCounter.check(any())).thenReturn(Decision.AVAILABLE);
        when(tokenLedger.reserve(any()))
                .thenReturn(OrganizationTokenBudgetLedger.Decision.RESERVED);

        assertDoesNotThrow(() -> service.requireAdmission(request(MAX_TOKENS)));

        verify(intakeCounter).check(any(OrganizationTaskIntakeQuotaCounter.QuotaQuery.class));
        var reservationCaptor = org.mockito.ArgumentCaptor.forClass(
                OrganizationTokenBudgetLedger.ReservationRequest.class);
        verify(tokenLedger).reserve(reservationCaptor.capture());
        OrganizationTokenBudgetLedger.ReservationRequest captured = reservationCaptor.getValue();
        assertEquals("2026-10", captured.usageMonth());
        assertEquals(MAX_TOKENS, captured.reservationTokens());
        assertEquals(1_000_000L, captured.budgetLimit());
        assertEquals(actorId, captured.actor());
    }

    @Test
    void aZeroTokenBudgetTaskSkipsTheLedgerEntirely() {
        stored(null, 1_000_000L);

        assertDoesNotThrow(() -> service.requireAdmission(request(0L)));

        verifyNoInteractions(tokenLedger);
        verifyNoInteractions(intakeCounter);
    }

    @Test
    void counterFailuresPropagateSoAdmissionFailsClosed() {
        stored(5, null);
        when(intakeCounter.check(any())).thenThrow(new IllegalStateException("storage down"));

        assertThrows(IllegalStateException.class, () -> service.requireAdmission(request(MAX_TOKENS)));
    }

    @Test
    void ledgerContextFailuresPropagateSoAdmissionFailsClosed() {
        stored(null, 1_000_000L);
        when(tokenLedger.reserve(any())).thenThrow(
                new QuotaLedgerContextException("lost CAS"));

        assertThrows(
                QuotaLedgerContextException.class, () -> service.requireAdmission(request(MAX_TOKENS)));
    }

    @Test
    void theRequestRecordRejectsANegativeTokenBound() {
        var invalid = assertThrows(
                IllegalArgumentException.class, () -> request(-1L));

        assertTrue(invalid.getMessage().contains("negative"));
    }

    private void stored(Integer intakeLimit, Long tokenBudget) {
        when(configurations.findByOrganization(organizationId)).thenReturn(Optional.of(
                OrganizationQuotaConfiguration.reconstitute(
                        organizationId, intakeLimit, tokenBudget, 0, NOW, actorId, NOW, actorId)));
    }

    private OrganizationQuotaAdmissionRequest request(long taskMaxTokens) {
        return new OrganizationQuotaAdmissionRequest(
                organizationId, actorId, taskMaxTokens, NOW);
    }
}
