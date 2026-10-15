package io.crewscope.application.observability.sli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.organization.OrganizationAccessContext;
import io.crewscope.application.organization.OrganizationAuthorization;
import io.crewscope.application.organization.OrganizationRoleGrantRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.organization.role.OrganizationRole;
import io.crewscope.domain.organization.role.OrganizationRoleGrant;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Window arithmetic, exclusion denominators, availability honesty and error-budget
 * burn-down of the six-indicator view (M12-I03), asserted against the ADR-037 §5
 * targets — including the honest split: table-backed rates get real windows, Timer
 * readings get one process-lifetime window plus a permanently degraded 28-day entry.
 */
class OrganizationSliQueryServiceTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-12T08:00:00Z");
    private static final Instant NOW_INSTANT = NOW.value();
    private static final ZoneId REPORTING_ZONE = ZoneId.of("Asia/Shanghai");
    /** 2026-10-12 16:00 in the reporting zone — the reporting day began at 00:00 there. */
    private static final Instant CURRENT_DAY_START =
            Instant.parse("2026-10-11T16:00:00Z");
    private static final Instant ROLLING_START = NOW_INSTANT.minusSeconds(28L * 24 * 3600);

    private final OrganizationId organizationId = OrganizationId.generate();
    private final PrincipalId principalId = PrincipalId.generate();
    private final OrganizationRoleGrantRepository grants =
            mock(OrganizationRoleGrantRepository.class);
    private final Principal actor = Principal.create(
            principalId,
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            "Actor",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);

    @Test
    void sixIndicatorsAssembleWithWindowsAvailabilityAndBudgets() {
        RecordingStatistics statistics = new RecordingStatistics(
                new SliExecutionStatistics(100, 90, 5, 3, 2, 12, 10, 1, 1, 0),
                new SliExecutionStatistics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                new SliNotificationStatistics(200, 1, 2, 3),
                new SliQueueingSnapshot(5, 7, 3));
        FixedTimers timers = new FixedTimers(
                new SliTimerStatistics.Distribution(650.0, 42),
                0.0005,
                new SliTimerStatistics.Distribution(2100.0, 10),
                new SliTimerStatistics.Distribution(900.0, 10),
                new SliTimerStatistics.Distribution(1500.0, 33),
                new SliTimerStatistics.Distribution(400.0, 21),
                new SliTimerStatistics.Distribution(1800.0, 9));
        ownerGrant();
        OrganizationSliView view = service(statistics, timers)
                .findSli(new OrganizationAccessContext(actor), organizationId);

        assertEquals(organizationId.value().toString(), view.organizationId());
        assertEquals(NOW_INSTANT, view.generatedAt());
        assertEquals("Asia/Shanghai", view.reportingZone());
        // Both table-backed queries ran per window with the exact window arithmetic.
        assertEquals(
                List.of(ROLLING_START, CURRENT_DAY_START),
                statistics.executionWindows.stream().map(window -> window[0]).toList());

        List<String> ids = view.metrics().stream().map(OrganizationSliView.MetricView::id).toList();
        assertEquals(List.of(
                "api.latency.p95",
                "agent.first.token",
                "queueing.latency",
                "execution.success.rate",
                "notification.delivery.rate",
                "knowledge.retrieval.latency.p95"), ids);

        OrganizationSliView.MetricView api = view.metrics().get(0);
        OrganizationSliView.WindowView apiLifetime = api.windows().get(0);
        assertEquals("process_lifetime", apiLifetime.window());
        assertEquals(650.0, apiLifetime.value());
        assertEquals(42L, apiLifetime.sampleCount());
        assertEquals("ok", apiLifetime.availability());
        // 5xx ratio 0.0005 against the 0.001 allowance: half the budget spent, still ok.
        OrganizationSliView.ErrorBudgetView apiBudget = apiLifetime.errorBudget();
        assertEquals(0.5, apiBudget.consumedRatio(), 1e-9);
        assertEquals("ok", apiBudget.status());
        OrganizationSliView.WindowView apiRolling = api.windows().get(1);
        assertEquals("rolling_28d", apiRolling.window());
        assertEquals("degraded", apiRolling.availability());
        assertNull(apiRolling.value());

        OrganizationSliView.MetricView firstToken = view.metrics().get(1);
        assertEquals(4, firstToken.windows().size());
        assertEquals("model_side", firstToken.windows().get(0).component());
        assertEquals(2100.0, firstToken.windows().get(0).value());
        assertEquals("app_overhead", firstToken.windows().get(2).component());
        assertEquals(900.0, firstToken.windows().get(2).value());

        OrganizationSliView.MetricView queueing = view.metrics().get(2);
        OrganizationSliView.WindowView snapshot = queueing.windows().get(2);
        assertEquals("snapshot", snapshot.window());
        assertEquals("organization", snapshot.scope());
        assertEquals(7.0, snapshot.value());
        assertTrue(snapshot.note().contains("backlog 5 entries"));

        OrganizationSliView.MetricView execution = view.metrics().get(3);
        OrganizationSliView.WindowView rollingRate = execution.windows().get(0);
        // Denominator 100 - 3 cancels - 2 policy denials = 95; 90 completed, 5 failed.
        assertEquals(90.0 / 95, rollingRate.value(), 1e-9);
        assertEquals(95L, rollingRate.sampleCount());
        OrganizationSliView.ErrorBudgetView executionBudget = rollingRate.errorBudget();
        assertEquals(0.05, executionBudget.allowedFailureRatio(), 1e-12);
        assertEquals(5.0 / 95, executionBudget.observedFailureRatio(), 1e-9);
        // 5/95 exceeds the 5% allowance: the budget is capped at 1.0 and exhausted.
        assertEquals(1.0, executionBudget.consumedRatio(), 1e-9);
        assertEquals("exhausted", executionBudget.status());
        OrganizationSliView.WindowView todayRate = execution.windows().get(1);
        assertEquals("current_day", todayRate.window());
        assertEquals("no_data", todayRate.availability());
        assertNull(todayRate.value());
        assertNull(todayRate.errorBudget());
        assertTrue(rollingRate.note().contains("12 terminal, 10 completed"));

        OrganizationSliView.MetricView notification = view.metrics().get(4);
        OrganizationSliView.WindowView notificationRolling = notification.windows().get(0);
        assertEquals(200.0 / 201, notificationRolling.value(), 1e-9);
        assertEquals("ok", notificationRolling.errorBudget().status());
        assertTrue(notificationRolling.note().contains("RECIPIENT_UNAVAILABLE"));

        OrganizationSliView.MetricView retrieval = view.metrics().get(5);
        assertEquals("process_lifetime", retrieval.windows().get(0).window());
        assertEquals(1800.0, retrieval.windows().get(0).value());
        assertEquals("degraded", retrieval.windows().get(1).availability());
    }

    @Test
    void timerReadingsWithoutSamplesReportNoDataAndCarryNoBudget() {
        ownerGrant();
        OrganizationSliView view = service(
                new RecordingStatistics(
                        new SliExecutionStatistics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                        new SliExecutionStatistics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                        new SliNotificationStatistics(0, 0, 0, 0),
                        new SliQueueingSnapshot(0, 0, 0)),
                new FixedTimers(null, null, null, null, null, null, null))
                .findSli(new OrganizationAccessContext(actor), organizationId);

        for (String id : List.of(
                "api.latency.p95", "agent.first.token", "queueing.latency",
                "knowledge.retrieval.latency.p95")) {
            OrganizationSliView.MetricView metric = view.metrics().stream()
                    .filter(candidate -> candidate.id().equals(id)).findFirst().orElseThrow();
            for (OrganizationSliView.WindowView window : metric.windows()) {
                if ("rolling_28d".equals(window.window())) {
                    assertEquals("degraded", window.availability());
                } else if (!"snapshot".equals(window.window())) {
                    // The snapshot stays "ok" — zero backlog is a fact, not missing data.
                    assertEquals("no_data", window.availability(), id + ":" + window.window());
                    assertNull(window.value());
                    assertNull(window.errorBudget());
                }
            }
        }
    }

    @Test
    void principalsWithoutAnOrganizationGrantAreDenied() {
        when(grants.findActiveByPrincipal(organizationId, principalId)).thenReturn(List.of());

        PolicyDeniedException denied = assertThrows(
                PolicyDeniedException.class,
                () -> service(new RecordingStatistics(
                                new SliExecutionStatistics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                                new SliExecutionStatistics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                                new SliNotificationStatistics(0, 0, 0, 0),
                                new SliQueueingSnapshot(0, 0, 0)),
                        new FixedTimers(null, null, null, null, null, null, null))
                        .findSli(new OrganizationAccessContext(actor), organizationId));
        assertEquals("read organization audit", denied.error().details().get("action"));
    }

    private OrganizationSliQueryService service(
            SliStatisticsRepository statistics, SliTimerStatistics timers) {
        return new OrganizationSliQueryService(
                statistics, timers, new OrganizationAuthorization(grants),
                () -> NOW, REPORTING_ZONE);
    }

    private void ownerGrant() {
        when(grants.findActiveByPrincipal(organizationId, principalId))
                .thenReturn(List.of(OrganizationRoleGrant.grant(
                        organizationId, principalId, OrganizationRole.ORG_OWNER,
                        principalId, null, NOW)));
    }

    /** One fixed answer per window call, recording the exact window bounds it served. */
    private static final class RecordingStatistics implements SliStatisticsRepository {
        private final SliExecutionStatistics rollingExecution;
        private final SliExecutionStatistics todayExecution;
        private final SliNotificationStatistics notifications;
        private final SliQueueingSnapshot queueing;
        private final List<Instant[]> executionWindows = new ArrayList<>();

        RecordingStatistics(
                SliExecutionStatistics rollingExecution,
                SliExecutionStatistics todayExecution,
                SliNotificationStatistics notifications,
                SliQueueingSnapshot queueing) {
            this.rollingExecution = rollingExecution;
            this.todayExecution = todayExecution;
            this.notifications = notifications;
            this.queueing = queueing;
        }

        @Override
        public SliExecutionStatistics executionStatistics(
                OrganizationId organization, Instant windowStart, Instant windowEnd) {
            executionWindows.add(new Instant[] {windowStart, windowEnd});
            return executionWindows.size() == 1 ? rollingExecution : todayExecution;
        }

        @Override
        public SliNotificationStatistics notificationStatistics(
                OrganizationId organization, Instant windowStart, Instant windowEnd) {
            return notifications;
        }

        @Override
        public SliQueueingSnapshot queueingSnapshot(OrganizationId organization) {
            return queueing;
        }
    }

    /** Fixed answers; null means "no samples" (Optional.empty at the boundary). */
    private static final class FixedTimers implements SliTimerStatistics {
        private final SliTimerStatistics.Distribution apiLatency;
        private final Double apiServerErrorRatio;
        private final SliTimerStatistics.Distribution firstTokenModelSide;
        private final SliTimerStatistics.Distribution firstTokenAppOverhead;
        private final SliTimerStatistics.Distribution outboxDelivery;
        private final SliTimerStatistics.Distribution projectionReplay;
        private final SliTimerStatistics.Distribution knowledgeRetrieval;

        FixedTimers(
                SliTimerStatistics.Distribution apiLatency,
                Double apiServerErrorRatio,
                SliTimerStatistics.Distribution firstTokenModelSide,
                SliTimerStatistics.Distribution firstTokenAppOverhead,
                SliTimerStatistics.Distribution outboxDelivery,
                SliTimerStatistics.Distribution projectionReplay,
                SliTimerStatistics.Distribution knowledgeRetrieval) {
            this.apiLatency = apiLatency;
            this.apiServerErrorRatio = apiServerErrorRatio;
            this.firstTokenModelSide = firstTokenModelSide;
            this.firstTokenAppOverhead = firstTokenAppOverhead;
            this.outboxDelivery = outboxDelivery;
            this.projectionReplay = projectionReplay;
            this.knowledgeRetrieval = knowledgeRetrieval;
        }

        @Override
        public Optional<Distribution> apiLatencyP95() {
            return Optional.ofNullable(apiLatency);
        }

        @Override
        public Optional<Double> apiServerErrorRatio() {
            return Optional.ofNullable(apiServerErrorRatio);
        }

        @Override
        public Optional<Distribution> firstTokenModelSideP95() {
            return Optional.ofNullable(firstTokenModelSide);
        }

        @Override
        public Optional<Distribution> firstTokenAppOverheadP95() {
            return Optional.ofNullable(firstTokenAppOverhead);
        }

        @Override
        public Optional<Distribution> outboxDeliveryP95() {
            return Optional.ofNullable(outboxDelivery);
        }

        @Override
        public Optional<Distribution> projectionReplayP95() {
            return Optional.ofNullable(projectionReplay);
        }

        @Override
        public Optional<Distribution> knowledgeRetrievalP95() {
            return Optional.ofNullable(knowledgeRetrieval);
        }
    }
}
