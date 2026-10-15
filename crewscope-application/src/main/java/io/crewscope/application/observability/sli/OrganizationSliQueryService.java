package io.crewscope.application.observability.sli;

import io.crewscope.application.organization.OrganizationAccessContext;
import io.crewscope.application.organization.OrganizationAuthorization;
import io.crewscope.domain.organization.role.OrganizationCapability;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.TimeProvider;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-side SLI assembly (M12-I03): the six ADR-037 §5 indicators for one organization.
 * Table-backed rates run on real windows (28-day rolling plus the current reporting
 * day); Timer-backed latencies honestly report the process lifetime and mark their
 * 28-day entries degraded — only an external Prometheus can fold those across restarts.
 */
public final class OrganizationSliQueryService {

    /** ADR-037 §5 targets: API p95 800ms / 99.9% success, app overhead 1500ms, queueing 30s. */
    static final double API_SERVER_ERROR_ALLOWED_RATIO = 0.001;
    static final long API_P95_OBJECTIVE_MILLIS = 800;
    static final long FIRST_TOKEN_APP_OVERHEAD_OBJECTIVE_MILLIS = 1500;
    static final long QUEUEING_OBJECTIVE_SECONDS = 30;

    /** ADR-037 §5 targets: execution >= 95%, notification delivery >= 99%, retrieval 2s. */
    static final double EXECUTION_FAILURE_ALLOWED_RATIO = 0.05;
    static final double NOTIFICATION_FAILURE_ALLOWED_RATIO = 0.01;
    static final long KNOWLEDGE_RETRIEVAL_OBJECTIVE_MILLIS = 2000;

    private static final Duration ROLLING_WINDOW = Duration.ofDays(28);
    private static final String TIMER_WINDOW_NOTE =
            "Window is this process's lifetime; a 28-day percentile needs external Prometheus.";

    private final SliStatisticsRepository statistics;
    private final SliTimerStatistics timerStatistics;
    private final OrganizationAuthorization authorization;
    private final TimeProvider timeProvider;
    private final ZoneId reportingZone;

    public OrganizationSliQueryService(
            SliStatisticsRepository statistics,
            SliTimerStatistics timerStatistics,
            OrganizationAuthorization authorization,
            TimeProvider timeProvider,
            ZoneId reportingZone) {
        this.statistics = Objects.requireNonNull(statistics, "statistics");
        this.timerStatistics = Objects.requireNonNull(timerStatistics, "timerStatistics");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.reportingZone = Objects.requireNonNull(reportingZone, "reportingZone");
    }

    /** Assembles all six indicators; AUDIT_READ_ORG is required (ORG_AUDITOR may read this). */
    public OrganizationSliView findSli(OrganizationAccessContext access, OrganizationId organizationId) {
        authorization.requireCapability(
                access, organizationId, OrganizationCapability.AUDIT_READ_ORG);
        Instant now = timeProvider.now().value();
        Instant rollingStart = now.minus(ROLLING_WINDOW);
        Instant currentDayStart = now.atZone(reportingZone)
                .toLocalDate()
                .atStartOfDay(reportingZone)
                .toInstant();

        SliExecutionStatistics executionRolling =
                statistics.executionStatistics(organizationId, rollingStart, now);
        SliExecutionStatistics executionToday =
                statistics.executionStatistics(organizationId, currentDayStart, now);
        SliNotificationStatistics notificationRolling =
                statistics.notificationStatistics(organizationId, rollingStart, now);
        SliNotificationStatistics notificationToday =
                statistics.notificationStatistics(organizationId, currentDayStart, now);
        SliQueueingSnapshot queueing = statistics.queueingSnapshot(organizationId);

        List<OrganizationSliView.MetricView> metrics = new ArrayList<>();
        metrics.add(apiLatency());
        metrics.add(firstToken());
        metrics.add(queueingLatency(queueing));
        metrics.add(executionSuccessRate(executionRolling, executionToday));
        metrics.add(notificationDeliveryRate(notificationRolling, notificationToday));
        metrics.add(knowledgeRetrievalLatency());
        return new OrganizationSliView(
                organizationId.value().toString(), now, reportingZone.getId(), List.copyOf(metrics));
    }

    private OrganizationSliView.MetricView apiLatency() {
        Optional<SliTimerStatistics.Distribution> p95 = timerStatistics.apiLatencyP95();
        Optional<Double> errorRatio = timerStatistics.apiServerErrorRatio();
        List<OrganizationSliView.WindowView> windows = new ArrayList<>();
        windows.add(new OrganizationSliView.WindowView(
                "process_lifetime", "deployment", null, "milliseconds",
                p95.map(d -> d.percentile95Millis()).orElse(null),
                p95.map(SliTimerStatistics.Distribution::sampleCount).orElse(null),
                p95.isPresent() ? "ok" : "no_data",
                errorRatio.map(ratio -> errorBudget("server_error_ratio", API_SERVER_ERROR_ALLOWED_RATIO, ratio))
                        .orElse(null),
                "Excludes /actuator/* and file-download route templates. " + TIMER_WINDOW_NOTE));
        windows.add(timerRolling28d(
                "milliseconds", null,
                "Fold crewscope_api_requests_bucket via histogram_quantile over 28 days."));
        return new OrganizationSliView.MetricView(
                "api.latency.p95",
                String.format(Locale.ROOT, "p95 < %d ms with %.1f%% success budget (ADR-037 §5)",
                        API_P95_OBJECTIVE_MILLIS, 100 - API_SERVER_ERROR_ALLOWED_RATIO * 100),
                "crewscope.api.requests Timer",
                windows);
    }

    private OrganizationSliView.MetricView firstToken() {
        List<OrganizationSliView.WindowView> windows = new ArrayList<>();
        Optional<SliTimerStatistics.Distribution> modelSide = timerStatistics.firstTokenModelSideP95();
        windows.add(timerProcessLifetime(
                "model_side", modelSide,
                "Model-call initiation to the first response event; distribution only, no target."));
        windows.add(timerRolling28d(
                "milliseconds", "model_side",
                "Fold crewscope_agent_model_first_token_seconds_bucket via histogram_quantile over 28 days."));
        Optional<SliTimerStatistics.Distribution> appOverhead = timerStatistics.firstTokenAppOverheadP95();
        windows.add(timerProcessLifetime(
                "app_overhead", appOverhead,
                "Invocation acceptance to the first model-call initiation, recorded once per invocation."));
        windows.add(timerRolling28d(
                "milliseconds", "app_overhead",
                "Fold crewscope_agent_model_app_overhead_seconds_bucket via histogram_quantile over 28 days."));
        return new OrganizationSliView.MetricView(
                "agent.first.token",
                String.format(Locale.ROOT, "application overhead p95 < %d ms; model side reported as distribution only (ADR-037 §5)",
                        FIRST_TOKEN_APP_OVERHEAD_OBJECTIVE_MILLIS),
                "crewscope.agent.model.first.token / crewscope.agent.model.app.overhead Timer",
                windows);
    }

    private OrganizationSliView.MetricView queueingLatency(SliQueueingSnapshot queueing) {
        List<OrganizationSliView.WindowView> windows = new ArrayList<>();
        windows.add(timerProcessLifetime(
                "outbox_delivery", timerStatistics.outboxDeliveryP95(),
                "Outbox claim-to-delivery duration. " + TIMER_WINDOW_NOTE));
        windows.add(timerProcessLifetime(
                "projection_replay", timerStatistics.projectionReplayP95(),
                "Projection replay duration. " + TIMER_WINDOW_NOTE));
        windows.add(new OrganizationSliView.WindowView(
                "snapshot", "organization", null, "seconds",
                (double) queueing.worstDelaySeconds(), null, "ok", null,
                String.format(Locale.ROOT,
                        "Outbox backlog %d entries (oldest %d s); largest projection checkpoint lag %d s."
                                + " Planned rebuilds inflate projection lag and are not filtered (ADR-037 §5 exclusion).",
                        queueing.outboxBacklog(), queueing.outboxOldestAgeSeconds(),
                        queueing.projectionMaxLagSeconds())));
        return new OrganizationSliView.MetricView(
                "queueing.latency",
                String.format(Locale.ROOT, "p95 < %d s excluding planned rebuilds (ADR-037 §5)",
                        QUEUEING_OBJECTIVE_SECONDS),
                "crewscope.m6.outbox.duration / crewscope.m6.projection.duration Timer + outbox and projection tables",
                windows);
    }

    private OrganizationSliView.MetricView executionSuccessRate(
            SliExecutionStatistics rolling, SliExecutionStatistics today) {
        List<OrganizationSliView.WindowView> windows = new ArrayList<>();
        windows.add(tableRateWindow(
                "rolling_28d", rolling,
                "Excludes user cancellations and policy denials (POLICY_VIOLATION, AUTHORIZATION)."));
        windows.add(tableRateWindow(
                "current_day", today,
                "Reporting-day window in " + reportingZone.getId()
                        + "; same exclusions as the rolling window."));
        return new OrganizationSliView.MetricView(
                "execution.success.rate",
                ">= 95% over a 28-day rolling window excluding user cancels and policy denials (ADR-037 §5)",
                "task_execution and agent_run SQL counts (organization_id + created_at scan)",
                windows);
    }

    private OrganizationSliView.MetricView notificationDeliveryRate(
            SliNotificationStatistics rolling, SliNotificationStatistics today) {
        List<OrganizationSliView.WindowView> windows = new ArrayList<>();
        windows.add(notificationRateWindow("rolling_28d", rolling));
        windows.add(notificationRateWindow("current_day", today));
        return new OrganizationSliView.MetricView(
                "notification.delivery.rate",
                ">= 99% excluding recipient-side unavailability and revoked authorization (ADR-037 §5)",
                "notification_delivery SQL counts",
                windows);
    }

    private OrganizationSliView.MetricView knowledgeRetrievalLatency() {
        List<OrganizationSliView.WindowView> windows = new ArrayList<>();
        Optional<SliTimerStatistics.Distribution> p95 = timerStatistics.knowledgeRetrievalP95();
        windows.add(timerProcessLifetime(
                null, p95,
                "Unified retrieval: embedding chain plus both vector searches; degraded outcomes (feature"
                        + " off, provider unavailable) are timed and tagged so 'disabled' stays distinct from 'slow'."
                        + " " + TIMER_WINDOW_NOTE));
        windows.add(timerRolling28d(
                "milliseconds", null,
                "Fold crewscope_m10_knowledge_retrieval_duration_seconds_bucket via histogram_quantile over 28 days."));
        return new OrganizationSliView.MetricView(
                "knowledge.retrieval.latency.p95",
                String.format(Locale.ROOT, "p95 < %d ms excluding index rebuild windows (ADR-037 §5)",
                        KNOWLEDGE_RETRIEVAL_OBJECTIVE_MILLIS),
                "crewscope.m10.knowledge.retrieval.duration Timer",
                windows);
    }

    private OrganizationSliView.WindowView timerProcessLifetime(
            String component, Optional<SliTimerStatistics.Distribution> distribution, String note) {
        return new OrganizationSliView.WindowView(
                "process_lifetime", "deployment", component, "milliseconds",
                distribution.map(d -> d.percentile95Millis()).orElse(null),
                distribution.map(SliTimerStatistics.Distribution::sampleCount).orElse(null),
                distribution.isPresent() ? "ok" : "no_data",
                null,
                note);
    }

    private OrganizationSliView.WindowView timerRolling28d(String unit, String component, String note) {
        return new OrganizationSliView.WindowView(
                "rolling_28d", "deployment", component, unit, null, null, "degraded", null,
                "In-process registry cannot answer this; " + note);
    }

    private OrganizationSliView.WindowView tableRateWindow(
            String window, SliExecutionStatistics stats, String exclusionNote) {
        long denominator = stats.executionDenominator();
        boolean noData = denominator <= 0;
        Double rate = noData ? null : (double) stats.executionsCompleted() / denominator;
        Double observedFailure = noData ? null : (double) stats.executionsFailed() / denominator;
        return new OrganizationSliView.WindowView(
                window, "organization", null, "ratio", rate,
                noData ? null : denominator,
                noData ? "no_data" : "ok",
                noData ? null : errorBudget("failure_ratio", EXECUTION_FAILURE_ALLOWED_RATIO, observedFailure),
                exclusionNote + String.format(Locale.ROOT,
                        " Agent-run view (no policy-denial classification exists there): %d terminal, %d completed,"
                                + " %d failed, %d cancelled, %d interrupted.",
                        stats.agentRunsTerminal(), stats.agentRunsCompleted(), stats.agentRunsFailed(),
                        stats.agentRunsCancelled(), stats.agentRunsInterrupted()));
    }

    private OrganizationSliView.WindowView notificationRateWindow(
            String window, SliNotificationStatistics stats) {
        long denominator = stats.deliveryDenominator();
        boolean noData = denominator <= 0;
        Double rate = noData ? null : (double) stats.delivered() / denominator;
        Double observedFailure = noData ? null : (double) stats.failedFinal() / denominator;
        return new OrganizationSliView.WindowView(
                window, "organization", null, "ratio", rate,
                noData ? null : denominator,
                noData ? "no_data" : "ok",
                noData ? null : errorBudget("failure_ratio", NOTIFICATION_FAILURE_ALLOWED_RATIO, observedFailure),
                String.format(Locale.ROOT,
                        "Excludes recipient-side failures (RECIPIENT_UNAVAILABLE, AUTHORIZATION_REVOKED: %d)"
                                + " and invalidated or cancelled deliveries (%d).",
                        stats.recipientSideExcluded(), stats.invalidatedOrCancelled()));
    }

    private OrganizationSliView.ErrorBudgetView errorBudget(
            String basis, double allowed, double observedFailureRatio) {
        double consumed = Math.min(1.0, observedFailureRatio / allowed);
        return new OrganizationSliView.ErrorBudgetView(
                basis, allowed, observedFailureRatio, consumed, consumed >= 1.0 ? "exhausted" : "ok");
    }
}
