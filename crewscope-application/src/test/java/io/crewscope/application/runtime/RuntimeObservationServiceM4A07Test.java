package io.crewscope.application.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** M4-A07 evidence for safe Coding resource projection and scope closure. */
class RuntimeObservationServiceM4A07Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-20T05:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final RuntimeEnvironment environment = new RuntimeEnvironment("development");
    private final WorkItemAccessPolicy accessPolicy = mock(WorkItemAccessPolicy.class);
    private final RuntimeObservationRepository repository = mock(RuntimeObservationRepository.class);
    private final CodingRuntimeOperationsPort coding = mock(CodingRuntimeOperationsPort.class);
    private final TeamAccessContext context = mock(TeamAccessContext.class);
    private final TransactionExecutor transactions = new TransactionExecutor() {
        @Override
        public <T> T required(Supplier<T> operation) {
            return operation.get();
        }
    };

    @Test
    void projectsWorkspaceCapacityAndComponentHealthWithoutLocalIdentities() {
        when(repository.observe(any())).thenReturn(
                new RuntimeObservationSnapshot(List.of(), List.of(), List.of()));
        CodingRuntimeSnapshot snapshot = snapshot(organizationId, environment);
        when(coding.observe(organizationId, environment)).thenReturn(Optional.of(snapshot));

        RuntimeFleetSummary member = service().summary(
                context, organizationId, teamId, environment);
        RuntimeOperationsView operations = service().operations(
                context, organizationId, teamId, environment);

        CodingWorkspaceFleetSummary safe = member.codingWorkspaces().orElseThrow();
        assertEquals(new RuntimeCapacitySummary(4, 1, 3), safe.capacity());
        assertEquals(CodingRuntimeComponentHealth.DEGRADED, safe.watchers().health());
        assertTrue(operations.codingRuntime().isPresent());
        assertEquals(1, operations.codingRuntime().orElseThrow().cleanup().recoveredWorkspaces());
    }

    @Test
    void rejectsAWorkerSnapshotOutsideTheRequestedOrganization() {
        when(repository.observe(any())).thenReturn(
                new RuntimeObservationSnapshot(List.of(), List.of(), List.of()));
        when(coding.observe(organizationId, environment)).thenReturn(Optional.of(
                snapshot(OrganizationId.generate(), environment)));

        assertThrows(DomainValidationException.class, () -> service().summary(
                context, organizationId, teamId, environment));
    }

    private RuntimeObservationService service() {
        return new RuntimeObservationService(
                accessPolicy,
                repository,
                transactions,
                () -> NOW,
                Duration.ofSeconds(30),
                coding);
    }

    private CodingRuntimeSnapshot snapshot(
            OrganizationId organization, RuntimeEnvironment selectedEnvironment) {
        return new CodingRuntimeSnapshot(
                organization,
                selectedEnvironment,
                NOW,
                CodingRuntimeComponentHealth.DEGRADED,
                new RuntimeCapacitySummary(4, 1, 3),
                new CodingRuntimeComponentSummary(
                        CodingRuntimeComponentHealth.HEALTHY, 1, 1, 0),
                new CodingRuntimeComponentSummary(
                        CodingRuntimeComponentHealth.DEGRADED, 1, 0, 1),
                new CodingCleanupSummary(
                        CodingRuntimeComponentHealth.HEALTHY,
                        true,
                        1,
                        0,
                        0,
                        0,
                        1,
                        0,
                        false,
                        Optional.empty()));
    }
}
