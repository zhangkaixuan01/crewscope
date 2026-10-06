package io.crewscope.application.observability;

import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.TeamBudgetAlertId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamBudgetAlertKind;
import io.crewscope.domain.team.TeamBudgetAlertLevel;
import io.crewscope.domain.team.event.TeamBudgetAlertRecorded;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The soft budget scan (M10-F03): one pass over every active team's current usage
 * month, comparing token totals and per-currency amounts against the configured
 * budgets. The ledger insert and the {@code TEAM_BUDGET_ALERT_RECORDED} append commit
 * in one transaction and only a first insert emits, so a team×month×kind×level alert
 * fires exactly once however often the scan runs. A reminder, never a quota: nothing
 * here blocks execution, and a notification failure downstream never affects the scan.
 */
public final class TeamBudgetAlertService {

    private static final String EVENT_ID_NAMESPACE = "io.crewscope/team-budget-alert/event/";
    private static final String EVENT_TYPE = "TEAM_BUDGET_ALERT_RECORDED";
    private static final String AGGREGATE_TYPE = "TEAM_BUDGET_ALERT";

    private final TeamBudgetQueryRepository queries;
    private final TeamBudgetAlertWriter alerts;
    private final DomainEventStore events;
    private final OutboxRepository outbox;
    private final TransactionExecutor transactions;
    private final AuthoritativeTimeProvider timeProvider;
    private final ZoneId reportingZone;
    private final TeamBudgetAlertSettings settings;

    public TeamBudgetAlertService(
            TeamBudgetQueryRepository queries,
            TeamBudgetAlertWriter alerts,
            DomainEventStore events,
            OutboxRepository outbox,
            TransactionExecutor transactions,
            AuthoritativeTimeProvider timeProvider,
            ZoneId reportingZone,
            TeamBudgetAlertSettings settings) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.alerts = Objects.requireNonNull(alerts, "alerts");
        this.events = Objects.requireNonNull(events, "events");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.reportingZone = Objects.requireNonNull(reportingZone, "reportingZone");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** Scans the current usage month; returns how many first-time alerts were emitted. */
    public int scanOnce() {
        if (!settings.enabled()) {
            return 0;
        }
        // The same month grouping the rollup projection uses, never a second formatter.
        String usageMonth = ModelUsageMonthKey.of(timeProvider.now(), reportingZone).value();
        int emitted = 0;
        for (TeamBudgetQueryRepository.TeamBudgetTarget target : queries.findActiveTeams()) {
            TeamBudgetQueryRepository.TeamMonthlyUsage usage =
                    queries.findMonthlyUsage(target.organizationId(), target.teamId(), usageMonth)
                            .orElseGet(TeamBudgetQueryRepository.TeamMonthlyUsage::empty);
            emitted += scanTokens(target, usageMonth, usage);
            emitted += scanAmounts(target, usageMonth, usage);
        }
        return emitted;
    }

    private int scanTokens(
            TeamBudgetQueryRepository.TeamBudgetTarget target,
            String usageMonth,
            TeamBudgetQueryRepository.TeamMonthlyUsage usage) {
        if (settings.monthlyTokenBudget() <= 0) {
            return 0;
        }
        BigDecimal metric = BigDecimal.valueOf(usage.totalTokens());
        return tryEmit(target, usageMonth, TeamBudgetAlertKind.TOKEN,
                TeamBudgetAlertLevel.WARNING, metric, settings.tokenWarningThreshold(), null)
                + tryEmit(target, usageMonth, TeamBudgetAlertKind.TOKEN,
                        TeamBudgetAlertLevel.EXCEEDED, metric,
                        settings.tokenExceededThreshold(), null);
    }

    private int scanAmounts(
            TeamBudgetQueryRepository.TeamBudgetTarget target,
            String usageMonth,
            TeamBudgetQueryRepository.TeamMonthlyUsage usage) {
        int emitted = 0;
        for (Map.Entry<String, BigDecimal> spent : usage.costsByCurrency().entrySet()) {
            BigDecimal budget = settings.amountBudget(spent.getKey());
            if (budget == null || budget.signum() <= 0) {
                continue;
            }
            emitted += tryEmit(target, usageMonth, TeamBudgetAlertKind.AMOUNT,
                    TeamBudgetAlertLevel.WARNING, spent.getValue(),
                    budget.multiply(settings.warningRatio()), spent.getKey())
                    + tryEmit(target, usageMonth, TeamBudgetAlertKind.AMOUNT,
                            TeamBudgetAlertLevel.EXCEEDED, spent.getValue(),
                            budget.multiply(settings.exceededRatio()), spent.getKey());
        }
        return emitted;
    }

    /** First crossing only: the ledger insert gates the event append in one transaction. */
    private int tryEmit(
            TeamBudgetQueryRepository.TeamBudgetTarget target,
            String usageMonth,
            TeamBudgetAlertKind kind,
            TeamBudgetAlertLevel level,
            BigDecimal metricValue,
            BigDecimal threshold,
            String currencyCode) {
        if (metricValue.compareTo(threshold) < 0) {
            return 0;
        }
        boolean inserted = Boolean.TRUE.equals(transactions.required(() -> {
            if (!alerts.insertIfAbsent(new TeamBudgetAlertWriter.AlertRow(
                    target.organizationId(), target.teamId(), usageMonth, kind, level,
                    metricValue, threshold,
                    Optional.ofNullable(currencyCode)))) {
                return false;
            }
            appendAlertEvent(target, usageMonth, kind, level, metricValue, threshold,
                    currencyCode);
            return true;
        }));
        return inserted ? 1 : 0;
    }

    private void appendAlertEvent(
            TeamBudgetQueryRepository.TeamBudgetTarget target,
            String usageMonth,
            TeamBudgetAlertKind kind,
            TeamBudgetAlertLevel level,
            BigDecimal metricValue,
            BigDecimal threshold,
            String currencyCode) {
        TeamBudgetAlertId alertId = TeamBudgetAlertId.generate();
        UUID eventId = deterministicEventId(target, usageMonth, kind, level);
        UtcTimestamp detectedAt = timeProvider.now();
        TeamBudgetAlertRecorded payload = new TeamBudgetAlertRecorded(
                alertId, target.teamId(), usageMonth, kind, level,
                metricValue, threshold, currencyCode, detectedAt);
        DomainEventEnvelope<DomainEvent> event = new DomainEventEnvelope<>(
                eventId,
                EventType.from(EVENT_TYPE),
                SchemaVersion.V1,
                target.organizationId(),
                Optional.of(target.teamId()),
                Optional.empty(),
                AggregateReference.of(AGGREGATE_TYPE, alertId),
                1L,
                EventActor.anonymousService(),
                alertId.value(),
                Optional.empty(),
                Optional.empty(),
                detectedAt,
                payload);
        events.append(event);
        outbox.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
    }

    /** The same (team, month, kind, level) always rebuilds the same event id. */
    private UUID deterministicEventId(
            TeamBudgetQueryRepository.TeamBudgetTarget target,
            String usageMonth,
            TeamBudgetAlertKind kind,
            TeamBudgetAlertLevel level) {
        return UUID.nameUUIDFromBytes((EVENT_ID_NAMESPACE
                + target.organizationId().value() + "/"
                + target.teamId().value() + "/"
                + usageMonth + "/"
                + kind + "/"
                + level).getBytes(StandardCharsets.UTF_8));
    }
}
