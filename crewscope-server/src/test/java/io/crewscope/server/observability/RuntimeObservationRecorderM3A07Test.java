package io.crewscope.server.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.runtime.RuntimeCapacitySummary;
import io.crewscope.application.runtime.RuntimeFleetHealth;
import io.crewscope.application.runtime.RuntimeFleetSummary;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Proves Runtime read metrics keep tenant, actor and correlation IDs out of tags. */
class RuntimeObservationRecorderM3A07Test {

    @Test
    void recordsOnlyLowCardinalityViewAndHealthDimensions() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RuntimeObservationRecorder recorder = new RuntimeObservationRecorder(registry);
        UtcTimestamp now = UtcTimestamp.parse("2026-08-15T12:30:00Z");
        RuntimeFleetSummary summary = new RuntimeFleetSummary(
                new RuntimeEnvironment("development"),
                now,
                RuntimeFleetHealth.HEALTHY,
                1,
                1,
                1,
                0,
                0,
                new RuntimeCapacitySummary(4, 0, 4),
                0,
                Map.of(),
                Optional.empty());

        for (int index = 0; index < 2; index++) {
            OrganizationId organizationId = OrganizationId.generate();
            TeamId teamId = TeamId.generate();
            Principal actor = Principal.create(
                    PrincipalId.generate(),
                    PrincipalScope.team(organizationId, teamId),
                    PrincipalType.USER,
                    Optional.empty(),
                    "Operator",
                    Optional.empty(),
                    PrincipalVisibility.TEAM,
                    now);
            recorder.record(
                    RuntimeObservationRecorder.View.MEMBER,
                    new TeamAccessContext(actor, false),
                    organizationId,
                    teamId,
                    UUID.randomUUID(),
                    summary);
        }

        var counters = registry.find(RuntimeObservationRecorder.REQUESTS).counters();
        assertEquals(1, counters.size());
        assertEquals(2.0, counters.iterator().next().count());
        Set<String> tagKeys = counters.iterator().next().getId().getTags().stream()
                .map(tag -> tag.getKey())
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(
                Set.of("view", "health", "workspace_health", "action_health"),
                tagKeys);
        assertEquals(
                "unavailable",
                counters.iterator().next().getId().getTag("action_health"));
        assertTrue(registry.getMeters().stream().noneMatch(meter ->
                meter.getId().getTags().stream().anyMatch(tag ->
                        tag.getValue().contains("-"))));
    }
}
