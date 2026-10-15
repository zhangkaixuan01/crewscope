package io.crewscope.server.observability;

import io.crewscope.application.observability.sli.SliTimerStatistics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

/**
 * Deployment-scoped Timer readings for the M12-I03 SLI surface, merged across series
 * via histogram buckets. The API latency population excludes actuator routes and the
 * three file-download route templates (their durations measure transfers, not API
 * work — the exclusion lives here, query-side, so the emission path stays untouched).
 */
@Component
public final class MeterRegistrySliTimerStatistics implements SliTimerStatistics {

    private static final Set<String> EXCLUDED_DOWNLOAD_ROUTES = Set.of(
            "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}"
                    + "/attempts/{executionId}/coding/artifacts/patch",
            "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}"
                    + "/attempts/{executionId}/coding/commands/{commandEvidenceId}/log",
            "/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}"
                    + "/attempts/{executionId}/coding/test-evidence/{testEvidenceId}/report");

    private final MeterRegistry registry;

    public MeterRegistrySliTimerStatistics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Optional<Distribution> apiLatencyP95() {
        return distribution(ApiObservabilityMetrics.REQUESTS, this::countsTowardsApiLatency);
    }

    @Override
    public Optional<Double> apiServerErrorRatio() {
        long total = 0;
        long serverErrors = 0;
        for (Timer timer : registry.find(ApiObservabilityMetrics.REQUESTS).timers()) {
            if (!countsTowardsApiLatency(timer)) {
                continue;
            }
            long count = timer.count();
            total += count;
            if ("server_error".equals(timer.getId().getTag("outcome"))) {
                serverErrors += count;
            }
        }
        return total == 0 ? Optional.empty() : Optional.of((double) serverErrors / total);
    }

    @Override
    public Optional<Distribution> firstTokenModelSideP95() {
        return distribution(AgentCallObservabilityMetrics.FIRST_TOKEN, timer -> true);
    }

    @Override
    public Optional<Distribution> firstTokenAppOverheadP95() {
        return distribution(AgentCallObservabilityMetrics.APP_OVERHEAD, timer -> true);
    }

    @Override
    public Optional<Distribution> outboxDeliveryP95() {
        return distribution(TeamBetaOperationalTelemetry.OUTBOX_DURATION, timer -> true);
    }

    @Override
    public Optional<Distribution> projectionReplayP95() {
        return distribution(TeamBetaOperationalTelemetry.PROJECTION_DURATION, timer -> true);
    }

    @Override
    public Optional<Distribution> knowledgeRetrievalP95() {
        return distribution(TeamBetaOperationalTelemetry.KNOWLEDGE_RETRIEVAL_DURATION, timer -> true);
    }

    private Optional<Distribution> distribution(String name, Predicate<Timer> filter) {
        List<Timer> timers = registry.find(name).timers().stream().filter(filter).toList();
        long sampleCount = timers.stream().mapToLong(Timer::count).sum();
        return HistogramPercentileMerge.percentile95Millis(
                        timers.stream().map(Timer::takeSnapshot).toList())
                .map(percentile -> new Distribution(percentile, sampleCount));
    }

    private boolean countsTowardsApiLatency(Timer timer) {
        String route = timer.getId().getTag("route");
        if (route == null) {
            return true;
        }
        return !route.startsWith("/actuator") && !EXCLUDED_DOWNLOAD_ROUTES.contains(route);
    }
}
