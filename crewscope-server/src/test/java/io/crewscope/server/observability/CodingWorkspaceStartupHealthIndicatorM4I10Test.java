package io.crewscope.server.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.runtime.CodingCleanupSummary;
import io.crewscope.application.runtime.CodingRuntimeComponentHealth;
import io.crewscope.application.runtime.CodingRuntimeComponentSummary;
import io.crewscope.application.runtime.CodingRuntimeSnapshot;
import io.crewscope.application.runtime.RuntimeCapacitySummary;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.infrastructure.workspace.repository.CodingWorkspaceRuntimeOperationsAdapter;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;

/** Readiness projection exposes bounded counts without Workspace or container identities. */
class CodingWorkspaceStartupHealthIndicatorM4I10Test {

    @Test
    void reportsDownWhenStartupCapacityWasExhaustedWithoutLeakingIdentities() {
        CodingWorkspaceRuntimeOperationsAdapter operations =
                mock(CodingWorkspaceRuntimeOperationsAdapter.class);
        CodingRuntimeComponentSummary healthy = new CodingRuntimeComponentSummary(
                CodingRuntimeComponentHealth.HEALTHY, 1, 1, 0);
        when(operations.localSnapshot()).thenReturn(new CodingRuntimeSnapshot(
                OrganizationId.generate(),
                new RuntimeEnvironment("test"),
                UtcTimestamp.parse("2026-08-19T04:00:00Z"),
                CodingRuntimeComponentHealth.DEGRADED,
                new RuntimeCapacitySummary(2, 1, 1),
                healthy,
                healthy,
                new CodingCleanupSummary(
                        CodingRuntimeComponentHealth.DEGRADED,
                        true,
                        100,
                        1,
                        20,
                        0,
                        3,
                        100,
                        true,
                        Optional.empty())));

        Health health = new CodingWorkspaceStartupHealthIndicator(operations).health();

        assertEquals("DOWN", health.getStatus().getCode());
        assertEquals(true, health.getDetails().get("capacityLimited"));
        assertFalse(health.getDetails().containsKey("workspaceId"));
        assertFalse(health.getDetails().containsKey("containerName"));
    }
}
