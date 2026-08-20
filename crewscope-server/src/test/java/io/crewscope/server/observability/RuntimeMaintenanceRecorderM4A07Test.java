package io.crewscope.server.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.runtime.CodingCleanupSummary;
import io.crewscope.application.runtime.CodingRuntimeComponentHealth;
import io.crewscope.application.runtime.CodingRuntimeComponentSummary;
import io.crewscope.application.runtime.CodingRuntimeMaintenanceOperation;
import io.crewscope.application.runtime.CodingRuntimeMaintenanceOutcome;
import io.crewscope.application.runtime.CodingRuntimeSnapshot;
import io.crewscope.application.runtime.RuntimeCapacitySummary;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Confirms maintenance metrics use only fixed operation and outcome dimensions. */
class RuntimeMaintenanceRecorderM4A07Test {

    @Test
    void keepsTenantActorAndCorrelationCoordinatesOutOfMetricTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RuntimeMaintenanceRecorder recorder = new RuntimeMaintenanceRecorder(registry);
        UtcTimestamp now = UtcTimestamp.parse("2026-08-20T07:00:00Z");

        for (int index = 0; index < 2; index++) {
            OrganizationId organizationId = OrganizationId.generate();
            TeamAccessContext access = access(organizationId, now);
            recorder.completed(
                    CodingRuntimeMaintenanceOperation.RECONCILE,
                    access,
                    organizationId,
                    UUID.randomUUID(),
                    CommandExecution.completed(
                            outcome(organizationId, now),
                            new CommandReceipt(
                                    UUID.randomUUID(),
                                    UUID.randomUUID(),
                                    0,
                                    UUID.randomUUID())));
        }

        var counters = registry.find(RuntimeMaintenanceRecorder.COMMANDS).counters();
        assertEquals(1, counters.size());
        assertEquals(2.0, counters.iterator().next().count());
        Set<String> keys = counters.iterator().next().getId().getTags().stream()
                .map(tag -> tag.getKey())
                .collect(Collectors.toSet());
        assertEquals(Set.of("operation", "outcome"), keys);
    }

    private static TeamAccessContext access(OrganizationId organizationId, UtcTimestamp now) {
        return new TeamAccessContext(
                Principal.create(
                        PrincipalId.generate(),
                        PrincipalScope.organization(organizationId),
                        PrincipalType.USER,
                        Optional.empty(),
                        "Operator",
                        Optional.empty(),
                        PrincipalVisibility.ORGANIZATION,
                        now),
                true);
    }

    private static CodingRuntimeMaintenanceOutcome outcome(
            OrganizationId organizationId, UtcTimestamp now) {
        CodingRuntimeComponentSummary healthy = new CodingRuntimeComponentSummary(
                CodingRuntimeComponentHealth.HEALTHY, 0, 0, 0);
        CodingRuntimeSnapshot snapshot = new CodingRuntimeSnapshot(
                organizationId,
                new RuntimeEnvironment("development"),
                now,
                CodingRuntimeComponentHealth.HEALTHY,
                new RuntimeCapacitySummary(2, 0, 2),
                healthy,
                healthy,
                new CodingCleanupSummary(
                        CodingRuntimeComponentHealth.HEALTHY,
                        true,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        false,
                        Optional.empty()));
        return new CodingRuntimeMaintenanceOutcome(
                CodingRuntimeMaintenanceOperation.RECONCILE, snapshot);
    }
}
