package io.crewscope.server.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.infrastructure.workspace.repository.CodingWorkspaceStartupHealth;
import io.crewscope.infrastructure.workspace.repository.CodingWorkspaceStartupReconciler;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;

/** Readiness projection exposes bounded counts without Workspace or container identities. */
class CodingWorkspaceStartupHealthIndicatorM4I10Test {

    @Test
    void reportsDownWhenStartupCapacityWasExhaustedWithoutLeakingIdentities() {
        CodingWorkspaceStartupReconciler reconciler = mock(CodingWorkspaceStartupReconciler.class);
        when(reconciler.health()).thenReturn(new CodingWorkspaceStartupHealth(
                true, 100, 1, 20, 0, 3, 100, true, Optional.empty()));

        Health health = new CodingWorkspaceStartupHealthIndicator(reconciler).health();

        assertEquals("DOWN", health.getStatus().getCode());
        assertEquals(true, health.getDetails().get("capacityLimited"));
        assertFalse(health.getDetails().containsKey("workspaceId"));
        assertFalse(health.getDetails().containsKey("containerName"));
    }
}
