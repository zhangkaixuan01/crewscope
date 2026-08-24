package io.crewscope.application.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.action.ActionReconciliationHealth;
import io.crewscope.application.action.TeamActionReconciliationHealthRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** M5-A08 evidence for Team-isolated, low-cardinality Action delivery fleet health. */
class RuntimeObservationServiceM5A08Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-24T10:00:00Z");

    @Test
    void observesActionHealthByExactTeamEvenWhenRuntimeFleetIsEmpty() {
        OrganizationId organizationId = OrganizationId.generate();
        TeamId teamId = TeamId.generate();
        WorkItemAccessPolicy access = mock(WorkItemAccessPolicy.class);
        RuntimeObservationRepository runtimes = mock(RuntimeObservationRepository.class);
        TeamActionReconciliationHealthRepository actions =
                mock(TeamActionReconciliationHealthRepository.class);
        when(runtimes.observe(any())).thenReturn(
                new RuntimeObservationSnapshot(List.of(), List.of(), List.of()));
        when(actions.reconciliationHealth(organizationId, teamId)).thenReturn(
                new ActionReconciliationHealth(
                        1,
                        2,
                        1,
                        1,
                        Optional.of(UtcTimestamp.parse("2026-08-24T08:00:00Z"))));
        RuntimeObservationService service = new RuntimeObservationService(
                access,
                runtimes,
                directTransactions(),
                () -> NOW,
                Duration.ofSeconds(30),
                null,
                actions,
                Duration.ofHours(1));

        RuntimeFleetSummary result = service.summary(
                mock(TeamAccessContext.class),
                organizationId,
                teamId,
                new RuntimeEnvironment("development"));

        ActionDeliveryFleetSummary delivery = result.actionDelivery().orElseThrow();
        assertEquals("ATTENTION_REQUIRED", delivery.health());
        assertEquals(2, delivery.unknown());
        assertEquals(7200, delivery.oldestUnresolvedAgeSeconds());
        assertTrue(delivery.stale());
        verify(actions).reconciliationHealth(organizationId, teamId);
    }

    private static TransactionExecutor directTransactions() {
        return new TransactionExecutor() {
            @Override
            public <T> T required(Supplier<T> operation) {
                return operation.get();
            }
        };
    }
}
