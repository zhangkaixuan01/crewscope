package io.crewscope.server.observability;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.runtime.CodingRuntimeMaintenanceOperation;
import io.crewscope.application.runtime.CodingRuntimeMaintenanceOutcome;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.shared.id.OrganizationId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Emits low-cardinality metrics and path-free structured audit logs for maintenance commands. */
@Component
public class RuntimeMaintenanceRecorder {

    public static final String COMMANDS = "crewscope.runtime.maintenance.commands";
    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeMaintenanceRecorder.class);

    private final MeterRegistry registry;

    public RuntimeMaintenanceRecorder(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public void completed(
            CodingRuntimeMaintenanceOperation operation,
            TeamAccessContext access,
            OrganizationId organizationId,
            UUID correlationId,
            CommandExecution<CodingRuntimeMaintenanceOutcome> execution) {
        String outcome = execution.replayed() ? "replayed" : "completed";
        increment(operation, outcome);
        var event = LOGGER.atInfo()
                .addKeyValue("event", "runtime_maintenance_command")
                .addKeyValue("operation", operation.name())
                .addKeyValue("outcome", outcome.toUpperCase(Locale.ROOT))
                .addKeyValue("organizationId", organizationId)
                .addKeyValue("actorPrincipalId", access.actor().id())
                .addKeyValue("correlationId", correlationId);
        execution.result().ifPresent(value -> event
                .addKeyValue("health", value.snapshot().health().name())
                .addKeyValue("capacityLimited", value.snapshot().cleanup().capacityLimited()));
        event.log("Runtime maintenance command completed");
    }

    public void failed(
            CodingRuntimeMaintenanceOperation operation,
            TeamAccessContext access,
            OrganizationId organizationId,
            UUID correlationId) {
        increment(operation, "failed");
        LOGGER.atWarn()
                .addKeyValue("event", "runtime_maintenance_command")
                .addKeyValue("operation", operation.name())
                .addKeyValue("outcome", "FAILED")
                .addKeyValue("organizationId", organizationId)
                .addKeyValue("actorPrincipalId", access.actor().id())
                .addKeyValue("correlationId", correlationId)
                .log("Runtime maintenance command failed");
    }

    private void increment(CodingRuntimeMaintenanceOperation operation, String outcome) {
        Counter.builder(COMMANDS)
                .description("Coding Runtime maintenance commands")
                .tags(
                        "operation", operation.name().toLowerCase(Locale.ROOT),
                        "outcome", outcome)
                .register(registry)
                .increment();
    }
}
