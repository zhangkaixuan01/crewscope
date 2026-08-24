package io.crewscope.server.observability;

import io.crewscope.application.task.TaskDeliverySummary;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Correlated audit, trace and low-cardinality metric for authorized delivery summary reads. */
@Component
public final class TaskDeliveryObservationRecorder {

    public static final String REQUESTS = "crewscope.task.delivery.summary.requests";
    private static final Logger LOGGER = LoggerFactory.getLogger(
            TaskDeliveryObservationRecorder.class);

    private final MeterRegistry registry;
    private final Tracer tracer;

    public TaskDeliveryObservationRecorder(MeterRegistry registry, Tracer tracer) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.tracer = Objects.requireNonNull(tracer, "tracer");
    }

    public void record(
            View view,
            TeamAccessContext access,
            OrganizationId organizationId,
            TeamId teamId,
            UUID correlationId,
            int itemCount,
            TaskDeliverySummary representative) {
        View requiredView = Objects.requireNonNull(view, "view");
        String reviewState = representative == null || representative.review() == null
                ? "unavailable"
                : representative.review().status().toLowerCase(Locale.ROOT);
        String deliveryState = representative == null || representative.action() == null
                ? "unavailable"
                : representative.action().validity().toLowerCase(Locale.ROOT);
        Counter.builder(REQUESTS)
                .tags(
                        "view", requiredView.name().toLowerCase(Locale.ROOT),
                        "review", reviewState,
                        "delivery", deliveryState)
                .register(registry)
                .increment();

        Span span = tracer.nextSpan().name("crewscope.task.delivery.summary")
                .tag("crewscope.view", requiredView.name())
                .tag("crewscope.item_count", Integer.toString(itemCount))
                .start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            LOGGER.atInfo()
                    .addKeyValue("event", "task_delivery_summary_read")
                    .addKeyValue("view", requiredView.name())
                    .addKeyValue("organizationId", organizationId)
                    .addKeyValue("teamId", teamId)
                    .addKeyValue("actorPrincipalId", access.actor().id())
                    .addKeyValue("correlationId", correlationId)
                    .addKeyValue("itemCount", itemCount)
                    .log("Task delivery summary read authorized");
        } finally {
            span.end();
        }
    }

    public enum View {
        TASK,
        CONVERSATION
    }
}
