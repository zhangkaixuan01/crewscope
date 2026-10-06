package io.crewscope.application.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamBudgetAlertKind;
import io.crewscope.domain.team.TeamBudgetAlertLevel;
import io.crewscope.domain.team.event.TeamBudgetAlertRecorded;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Locks the soft budget scan: thresholds, first-crossing-only emission, month zoning. */
class TeamBudgetAlertServiceTest {

    private static final OrganizationId ORG = OrganizationId.generate();
    private static final TeamId TEAM = TeamId.generate();
    private static final TeamBudgetQueryRepository.TeamBudgetTarget TARGET =
            new TeamBudgetQueryRepository.TeamBudgetTarget(ORG, TEAM, "platform");

    private final TeamBudgetQueryRepository queries = mock(TeamBudgetQueryRepository.class);
    private final TeamBudgetAlertWriter alerts = mock(TeamBudgetAlertWriter.class);
    private final DomainEventStore events = mock(DomainEventStore.class);
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final TransactionExecutor transactions = mock(TransactionExecutor.class);
    private final AuthoritativeTimeProvider timeProvider = mock(AuthoritativeTimeProvider.class);

    @BeforeEach
    void setUp() {
        // 2026-09-30 17:00 UTC is already 2026-10-01 01:00 in Asia/Shanghai.
        when(timeProvider.now()).thenReturn(UtcTimestamp.parse("2026-09-30T17:00:00Z"));
        when(transactions.required(any())).thenAnswer(
                call -> ((java.util.function.Supplier<?>) call.getArgument(0)).get());
    }

    private TeamBudgetAlertService service(TeamBudgetAlertSettings settings) {
        return new TeamBudgetAlertService(
                queries, alerts, events, outbox, transactions,
                timeProvider, ZoneId.of("Asia/Shanghai"), settings);
    }

    @Test
    void doesNotScanWhenDisabled() {
        TeamBudgetAlertService scan = service(
                new TeamBudgetAlertSettings(false, 1_000L, new BigDecimal("0.8"), Map.of()));

        assertEquals(0, scan.scanOnce());

        verifyNoInteractions(queries, alerts, events, outbox);
    }

    @Test
    void groupsTheUsageMonthInTheReportingZoneNotUtc() {
        when(queries.findActiveTeams()).thenReturn(List.of(TARGET));
        when(queries.findMonthlyUsage(ORG, TEAM, "2026-10")).thenReturn(Optional.empty());
        TeamBudgetAlertService scan = service(
                new TeamBudgetAlertSettings(true, 1_000L, new BigDecimal("0.8"), Map.of()));

        scan.scanOnce();

        // UTC still says September; the reporting zone already rolled to October.
        verify(queries).findMonthlyUsage(ORG, TEAM, "2026-10");
    }

    @Test
    void emitsWarningAndExceededWhenTokenUsageCrossesTheBudget() {
        when(queries.findActiveTeams()).thenReturn(List.of(TARGET));
        when(queries.findMonthlyUsage(ORG, TEAM, "2026-10")).thenReturn(Optional.of(
                new TeamBudgetQueryRepository.TeamMonthlyUsage(
                        1_200L, Map.of("USD", new BigDecimal("25")))));
        when(alerts.insertIfAbsent(any())).thenReturn(true);
        TeamBudgetAlertService scan = service(new TeamBudgetAlertSettings(
                true, 1_000L, new BigDecimal("0.8"), Map.of("USD", new BigDecimal("20"))));

        assertEquals(4, scan.scanOnce());

        ArgumentCaptor<DomainEventEnvelope<? extends io.crewscope.domain.shared.DomainEvent>> captured =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(events, org.mockito.Mockito.times(4)).append(captured.capture());
        List<TeamBudgetAlertLevel> levels = captured.getAllValues().stream()
                .map(event -> (TeamBudgetAlertRecorded) event.payload())
                .map(TeamBudgetAlertRecorded::level)
                .toList();
        assertEquals(List.of(
                TeamBudgetAlertLevel.WARNING, TeamBudgetAlertLevel.EXCEEDED,
                TeamBudgetAlertLevel.WARNING, TeamBudgetAlertLevel.EXCEEDED), levels);
        verify(outbox, org.mockito.Mockito.times(4)).enqueue(any(PendingOutboxEvent.class));
    }

    @Test
    void staysQuietBelowTheWarningLineAndWithoutATokenBudget() {
        when(queries.findActiveTeams()).thenReturn(List.of(TARGET));
        when(queries.findMonthlyUsage(ORG, TEAM, "2026-10")).thenReturn(Optional.of(
                new TeamBudgetQueryRepository.TeamMonthlyUsage(799L, Map.of())));
        // 0.8 of 1000 rounds up to 800: 799 tokens has not crossed yet.
        TeamBudgetAlertService scan = service(
                new TeamBudgetAlertSettings(true, 1_000L, new BigDecimal("0.8"), Map.of()));

        assertEquals(0, scan.scanOnce());

        verify(alerts, never()).insertIfAbsent(any());
        verify(events, never()).append(any());
        verify(outbox, never()).enqueue(any());
    }

    @Test
    void alertsEachConfiguredCurrencyOnceAndSkipsUnbudgetedOnes() {
        when(queries.findActiveTeams()).thenReturn(List.of(TARGET));
        when(queries.findMonthlyUsage(ORG, TEAM, "2026-10")).thenReturn(Optional.of(
                new TeamBudgetQueryRepository.TeamMonthlyUsage(0L, Map.of(
                        "USD", new BigDecimal("25"), "CNY", new BigDecimal("3")))));
        when(alerts.insertIfAbsent(any())).thenReturn(true);
        // Only USD carries a budget; CNY is spent but unbudgeted.
        TeamBudgetAlertService scan = service(new TeamBudgetAlertSettings(
                true, 0L, new BigDecimal("0.8"), Map.of("USD", new BigDecimal("20"))));

        assertEquals(2, scan.scanOnce());

        verify(alerts, org.mockito.Mockito.times(2)).insertIfAbsent(any());
        verify(alerts).insertIfAbsent(new TeamBudgetAlertWriter.AlertRow(
                ORG, TEAM, "2026-10", TeamBudgetAlertKind.AMOUNT,
                TeamBudgetAlertLevel.WARNING, new BigDecimal("25"),
                new BigDecimal("16.0"), Optional.of("USD")));
    }

    @Test
    void aSecondScanOfTheSameCrossingEmitsNothing() {
        when(queries.findActiveTeams()).thenReturn(List.of(TARGET));
        when(queries.findMonthlyUsage(ORG, TEAM, "2026-10")).thenReturn(Optional.of(
                new TeamBudgetQueryRepository.TeamMonthlyUsage(1_500L, Map.of())));
        when(alerts.insertIfAbsent(any())).thenReturn(true, true, false, false);
        TeamBudgetAlertService scan = service(
                new TeamBudgetAlertSettings(true, 1_000L, new BigDecimal("0.8"), Map.of()));

        assertEquals(2, scan.scanOnce());
        assertEquals(0, scan.scanOnce());

        verify(events, org.mockito.Mockito.times(2)).append(any());
        verify(outbox, org.mockito.Mockito.times(2)).enqueue(any());
    }

    @Test
    void anEmittingCrossingCarriesADeterministicEventIdAndNoAmountCurrency() {
        when(queries.findActiveTeams()).thenReturn(List.of(TARGET));
        when(queries.findMonthlyUsage(ORG, TEAM, "2026-10")).thenReturn(Optional.of(
                new TeamBudgetQueryRepository.TeamMonthlyUsage(1_000L, Map.of())));
        when(alerts.insertIfAbsent(any())).thenReturn(true);
        service(new TeamBudgetAlertSettings(true, 1_000L, new BigDecimal("0.8"), Map.of()))
                .scanOnce();

        ArgumentCaptor<DomainEventEnvelope<? extends io.crewscope.domain.shared.DomainEvent>> captured =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        // Exactly at the budget both lines are crossed: 1000 >= 800 and 1000 >= 1000.
        verify(events, org.mockito.Mockito.times(2)).append(captured.capture());
        DomainEventEnvelope<? extends io.crewscope.domain.shared.DomainEvent> envelope =
                captured.getAllValues().stream()
                        .filter(event -> ((TeamBudgetAlertRecorded) event.payload()).level()
                                == TeamBudgetAlertLevel.EXCEEDED)
                        .findFirst().orElseThrow();
        TeamBudgetAlertRecorded payload = (TeamBudgetAlertRecorded) envelope.payload();
        assertEquals(TeamBudgetAlertKind.TOKEN, payload.kind());
        assertNull(payload.currencyCode());
        assertEquals(payload.alertId().value(), envelope.correlationId());
    }

    @Test
    void aTeamWithoutFactsThisMonthIsSkippedEntirely() {
        when(queries.findActiveTeams()).thenReturn(List.of(TARGET));
        when(queries.findMonthlyUsage(any(), any(), anyString())).thenReturn(Optional.empty());
        TeamBudgetAlertService scan = service(new TeamBudgetAlertSettings(
                true, 1_000L, new BigDecimal("0.8"), Map.of("USD", new BigDecimal("20"))));

        assertEquals(0, scan.scanOnce());

        verify(alerts, never()).insertIfAbsent(any());
    }
}
