package io.crewscope.server.observability;

import io.crewscope.application.action.ActionReconciliationHealth;
import io.crewscope.application.action.ActionReconciliationObserver;
import io.crewscope.application.action.ActionReconciliationOutcome;
import io.crewscope.application.action.ActionReconciliationTrace;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Emits correlated model/Review/Action spans and metrics with bounded metric tags. */
public final class ActionReconciliationMetricsObserver
        implements ActionReconciliationObserver {

    public static final String ATTEMPTS = "crewscope.action.reconciliation.attempts";
    public static final String QUEUE = "crewscope.action.reconciliation.queue";

    private static final Logger LOGGER = LoggerFactory.getLogger(
            ActionReconciliationMetricsObserver.class);

    private final MeterRegistry registry;
    private final Tracer tracer;
    private final AtomicLong running = new AtomicLong();
    private final AtomicLong unknown = new AtomicLong();
    private final AtomicLong reconciling = new AtomicLong();
    private final AtomicLong manualReview = new AtomicLong();

    public ActionReconciliationMetricsObserver(MeterRegistry registry, Tracer tracer) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.tracer = Objects.requireNonNull(tracer, "tracer");
        gauge("running", running);
        gauge("unknown", unknown);
        gauge("reconciling", reconciling);
        gauge("manual_review", manualReview);
    }

    @Override
    public void record(
            ActionReconciliationTrace trace,
            ActionReconciliationOutcome outcome,
            Duration duration) {
        ActionReconciliationTrace value = Objects.requireNonNull(trace, "trace");
        ActionReconciliationOutcome result = Objects.requireNonNull(outcome, "outcome");
        Duration elapsed = Objects.requireNonNull(duration, "duration");
        String kind = value.actionKind().name().toLowerCase(Locale.ROOT);
        String mode = value.claimMode().name().toLowerCase(Locale.ROOT);
        String resultTag = result.name().toLowerCase(Locale.ROOT);
        Timer.builder(ATTEMPTS)
                .tags("kind", kind, "mode", mode, "outcome", resultTag)
                .register(registry)
                .record(elapsed);

        Span span = tracer.nextSpan()
                .name("crewscope.action.reconcile")
                .tag("crewscope.action.kind", kind)
                .tag("crewscope.action.outcome", resultTag)
                .tag("crewscope.task_execution_id", value.taskExecutionId().toString())
                .tag("crewscope.review_decision_id", value.reviewDecisionId().toString())
                .tag("crewscope.action_id", value.actionId().toString())
                .start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            LOGGER.atInfo()
                    .addKeyValue("event", "action_reconciliation")
                    .addKeyValue("organizationId", value.organizationId())
                    .addKeyValue("teamId", value.teamId())
                    .addKeyValue("taskExecutionId", value.taskExecutionId())
                    .addKeyValue("reviewDecisionId", value.reviewDecisionId())
                    .addKeyValue("actionId", value.actionId())
                    .addKeyValue("actionKind", value.actionKind())
                    .addKeyValue("claimMode", value.claimMode())
                    .addKeyValue("outcome", result)
                    .addKeyValue("durationMs", elapsed.toMillis())
                    .log("Action reconciliation observed");
        } finally {
            span.end();
        }
    }

    @Override
    public void queueHealth(ActionReconciliationHealth health) {
        ActionReconciliationHealth value = Objects.requireNonNull(health, "health");
        running.set(value.running());
        unknown.set(value.unknown());
        reconciling.set(value.reconciling());
        manualReview.set(value.manualReview());
    }

    private void gauge(String state, AtomicLong value) {
        Gauge.builder(QUEUE, value, AtomicLong::get)
                .tag("state", state)
                .description("Current durable external Action queue size")
                .register(registry);
    }
}
