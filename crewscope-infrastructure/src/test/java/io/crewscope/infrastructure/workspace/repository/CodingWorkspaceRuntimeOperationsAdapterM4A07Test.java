package io.crewscope.infrastructure.workspace.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.runtime.CodingRuntimeComponentHealth;
import io.crewscope.application.runtime.CodingRuntimeMaintenanceOperation;
import io.crewscope.application.runtime.CodingRuntimeOperationsUnavailableException;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.infrastructure.runtime.RuntimeWorkerRegistrationSpec;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Local capacity, Sandbox, Watcher and maintenance delegation proof for M4-A07. */
class CodingWorkspaceRuntimeOperationsAdapterM4A07Test {

    private static final OrganizationId ORGANIZATION = OrganizationId.generate();
    private static final RuntimeEnvironment ENVIRONMENT = new RuntimeEnvironment("development");
    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-20T06:00:00Z");

    private final CodingWorkspaceRuntimeRegistry registry = new CodingWorkspaceRuntimeRegistry();
    private final CodingWorkspaceStartupReconciler reconciler =
            mock(CodingWorkspaceStartupReconciler.class);
    private final RuntimeWorkerRegistrationSpec registration =
            mock(RuntimeWorkerRegistrationSpec.class);

    @BeforeEach
    void setUp() {
        when(registration.organizationId()).thenReturn(ORGANIZATION);
        when(registration.environment()).thenReturn(ENVIRONMENT);
        when(registration.maxConcurrentExecutions()).thenReturn(4);
        when(reconciler.health()).thenReturn(new CodingWorkspaceStartupHealth(
                true, 1, 0, 2, 0, 1, 3, false, Optional.empty()));
    }

    @Test
    void derivesClosedLocalCapacityAndDegradedWatcherHealthWithoutCoordinates() {
        CodingWorkspaceExecution execution = execution(true, true);
        registry.register(execution);

        var snapshot = adapter().observe(ORGANIZATION, ENVIRONMENT).orElseThrow();

        assertEquals(4, snapshot.workspaceCapacity().maximum());
        assertEquals(1, snapshot.workspaceCapacity().active());
        assertEquals(CodingRuntimeComponentHealth.HEALTHY, snapshot.sandboxes().health());
        assertEquals(CodingRuntimeComponentHealth.DEGRADED, snapshot.watchers().health());
        assertEquals(1, snapshot.watchers().failed());
    }

    @Test
    void delegatesReconcileAndArchiveToTheI10Authority() {
        adapter().maintain(
                ORGANIZATION, ENVIRONMENT, CodingRuntimeMaintenanceOperation.RECONCILE);
        adapter().maintain(
                ORGANIZATION, ENVIRONMENT, CodingRuntimeMaintenanceOperation.ARCHIVE);

        verify(reconciler).reconcileWorkspaceResources();
        verify(reconciler).archiveWorkspaceResources();
    }

    @Test
    void failsClosedForAnEnvironmentNotOwnedByThisWorker() {
        assertThrows(CodingRuntimeOperationsUnavailableException.class, () -> adapter().maintain(
                ORGANIZATION,
                new RuntimeEnvironment("production"),
                CodingRuntimeMaintenanceOperation.RECONCILE));
    }

    private CodingWorkspaceRuntimeOperationsAdapter adapter() {
        return new CodingWorkspaceRuntimeOperationsAdapter(
                registry, reconciler, registration, () -> NOW);
    }

    private CodingWorkspaceExecution execution(boolean sandboxRunning, boolean watcherFailed) {
        CodingWorkspaceExecution execution = mock(CodingWorkspaceExecution.class);
        ExecutionWorkspace workspace = mock(ExecutionWorkspace.class);
        ManagedTaskExecutionSandbox sandbox = mock(ManagedTaskExecutionSandbox.class);
        WorkspaceDiffMonitor monitor = mock(WorkspaceDiffMonitor.class);
        when(workspace.taskExecutionId()).thenReturn(TaskExecutionId.generate());
        when(execution.workspace()).thenReturn(workspace);
        when(execution.sandbox()).thenReturn(sandbox);
        when(sandbox.isRunning()).thenReturn(sandboxRunning);
        when(execution.diffMonitor()).thenReturn(Optional.of(monitor));
        when(monitor.lastFailure()).thenReturn(watcherFailed
                ? Optional.of(mock(WorkspaceDiffException.class))
                : Optional.empty());
        return execution;
    }
}
