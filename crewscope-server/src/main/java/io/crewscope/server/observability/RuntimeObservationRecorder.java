package io.crewscope.server.observability;

import io.crewscope.application.runtime.RuntimeFleetSummary;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Component;

/** Emits a correlated safe audit log and low-cardinality metric for Runtime observation reads. */
@Component
public class RuntimeObservationRecorder {

    public static final String REQUESTS = "crewscope.runtime.observation.requests";

    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeObservationRecorder.class);

    private final MeterRegistry registry;

    public RuntimeObservationRecorder(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public void record(
            View view,
            TeamAccessContext access,
            OrganizationId organizationId,
            TeamId teamId,
            UUID correlationId,
            RuntimeFleetSummary summary) {
        View requiredView = Objects.requireNonNull(view, "view");
        RuntimeFleetSummary requiredSummary = Objects.requireNonNull(summary, "summary");
        Counter.builder(REQUESTS)
                .description("Authorized Runtime observation reads")
                .tags(
                        "view", requiredView.name().toLowerCase(Locale.ROOT),
                        "health", requiredSummary.health().name().toLowerCase(Locale.ROOT))
                .register(registry)
                .increment();

        LoggingEventBuilder event = LOGGER.atInfo()
                .addKeyValue("event", "runtime_observation_read")
                .addKeyValue("view", requiredView.name())
                .addKeyValue("organizationId", Objects.requireNonNull(organizationId, "organizationId"))
                .addKeyValue("teamId", Objects.requireNonNull(teamId, "teamId"))
                .addKeyValue("actorPrincipalId", Objects.requireNonNull(access, "access").actor().id())
                .addKeyValue("correlationId", Objects.requireNonNull(correlationId, "correlationId"))
                .addKeyValue("health", requiredSummary.health().name())
                .addKeyValue("waitingRuntimeExecutions", requiredSummary.waitingRuntimeExecutions());
        event.log("Runtime observation read authorized");
    }

    public enum View {
        MEMBER,
        OPERATIONS
    }
}
