package io.crewscope.application.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.crewscope.application.projection.ProjectionAdministration;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.projection.ProjectionDefinitionVersion;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Member disclosure, administrator authorization and low-cardinality tests for M6-E07. */
class OperationsHealthServiceM6E07Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-26T05:00:00Z");

    private OrganizationId organizationId;
    private TeamId teamId;
    private Principal actor;
    private TeamAccessContext context;
    private WorkItemAccessPolicy accessPolicy;
    private ProjectionAdministration administration;
    private OperationsHealthQueryPort queries;
    private OperationsHealthService service;

    @BeforeEach
    void setUp() {
        organizationId = OrganizationId.generate();
        teamId = TeamId.generate();
        actor = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                "Administrator",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        context = new TeamAccessContext(actor, true);
        accessPolicy = mock(WorkItemAccessPolicy.class);
        administration = mock(ProjectionAdministration.class);
        queries = mock(OperationsHealthQueryPort.class);
        service = new OperationsHealthService(
                accessPolicy,
                administration,
                queries,
                new DirectTransactions(),
                fixedTime(),
                thresholds());
    }

    @Test
    void memberSummaryContainsOnlyFiveBoundedComponentsAndMetricLabels() {
        when(queries.observe(organizationId)).thenReturn(snapshot(organizationId));

        OperationsMemberHealthSummary result = service.summary(context, organizationId, teamId);

        assertEquals(OperationsHealthLevel.UNAVAILABLE, result.health());
        assertEquals(5, result.components().size());
        assertEquals(5, result.metrics().size());
        assertEquals(
                OperationsHealthLevel.DEGRADED,
                component(result, OperationsHealthComponent.OUTBOX).health());
        assertEquals(
                OperationsHealthLevel.ATTENTION_REQUIRED,
                component(result, OperationsHealthComponent.DEAD_LETTER).health());
        assertEquals(20, component(result, OperationsHealthComponent.OUTBOX)
                .oldestOutstandingAgeSeconds());
        assertFalse(Arrays.stream(OperationsMemberHealthSummary.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase())
                .anyMatch(name -> name.contains("organization")
                        || name.contains("team")
                        || name.contains("projection")
                        || name.endsWith("id")));
        assertFalse(Arrays.stream(OperationsHealthMetric.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase())
                .anyMatch(name -> name.contains("organization")
                        || name.contains("team")
                        || name.contains("projection")
                        || name.contains("errorcode")
                        || name.endsWith("id")));
        verify(accessPolicy).requireVisibleTeam(context, organizationId, teamId);
    }

    @Test
    void deniedMemberPerformsNoOperationsQuery() {
        doThrow(new IllegalStateException("denied"))
                .when(accessPolicy)
                .requireVisibleTeam(context, organizationId, teamId);

        assertThrows(
                IllegalStateException.class,
                () -> service.summary(context, organizationId, teamId));

        verifyNoInteractions(queries);
    }

    @Test
    void administratorAuthorizationPrecedesExactDiagnostics() {
        OperationsHealthSnapshot snapshot = snapshot(organizationId);
        when(queries.observe(organizationId)).thenReturn(snapshot);

        OperationsAdministratorDiagnostics result = service.diagnostics(organizationId, actor);

        assertEquals(new ProjectionName("team-activity"),
                result.projections().get(0).projectionName());
        assertEquals(1, result.recoveryCandidates().size());
        verify(administration).requireOrganizationAdministrator(organizationId, actor, NOW);
    }

    @Test
    void rejectsCrossScopeAndFutureSnapshots() {
        when(queries.observe(organizationId)).thenReturn(snapshot(OrganizationId.generate()));
        assertThrows(
                IllegalStateException.class,
                () -> service.diagnostics(organizationId, actor));

        OperationsHealthSnapshot valid = snapshot(organizationId);
        when(queries.observe(organizationId)).thenReturn(new OperationsHealthSnapshot(
                organizationId,
                UtcTimestamp.from(NOW.value().plusSeconds(1)),
                valid.components(),
                valid.projections(),
                valid.recoveryCandidates()));
        assertThrows(
                IllegalStateException.class,
                () -> service.diagnostics(organizationId, actor));
    }

    @Test
    void snapshotRequiresExactlyOneOfEveryFixedComponent() {
        OperationsHealthSnapshot valid = snapshot(organizationId);
        assertThrows(
                IllegalArgumentException.class,
                () -> new OperationsHealthSnapshot(
                        organizationId,
                        NOW,
                        valid.components().subList(0, 4),
                        valid.projections(),
                        valid.recoveryCandidates()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new OperationsHealthSnapshot(
                        organizationId,
                        NOW,
                        List.of(
                                valid.components().get(0),
                                valid.components().get(0),
                                valid.components().get(1),
                                valid.components().get(2),
                                valid.components().get(3),
                                valid.components().get(4)),
                        valid.projections(),
                        valid.recoveryCandidates()));
    }

    private OperationsHealthSnapshot snapshot(OrganizationId scope) {
        List<OperationsComponentObservation> observations = List.of(
                observation(OperationsHealthComponent.PROJECTION, 1, 0, 0, 1, 20, false),
                observation(OperationsHealthComponent.OUTBOX, 11, 2, 0, 3, 20, false),
                observation(OperationsHealthComponent.DEAD_LETTER, 1, 0, 1, 1, 1, false),
                observation(OperationsHealthComponent.CURSOR, 0, 3, 0, 0, 0, false),
                observation(OperationsHealthComponent.NOTIFICATION, 0, 0, 0, 0, 0, true));
        ProjectionHealthDiagnostic projection = new ProjectionHealthDiagnostic(
                new ProjectionName("team-activity"),
                ProjectionDefinitionVersion.V1,
                ProjectionGeneration.FIRST,
                0,
                0,
                Optional.empty(),
                Optional.empty(),
                OptionalLong.empty(),
                Optional.empty(),
                OptionalLong.empty(),
                20,
                0,
                0,
                Optional.empty());
        return new OperationsHealthSnapshot(
                scope,
                NOW,
                observations,
                List.of(projection),
                List.of(new OutboxDeadLetterRecoveryTarget(
                        UUID.randomUUID(), UUID.randomUUID(), 2)));
    }

    private OperationsComponentObservation observation(
            OperationsHealthComponent component,
            long backlog,
            long inFlight,
            long failures,
            long affected,
            long ageSeconds,
            boolean unavailable) {
        return new OperationsComponentObservation(
                component,
                backlog,
                inFlight,
                failures,
                affected,
                backlog == 0
                        ? Optional.empty()
                        : Optional.of(UtcTimestamp.from(NOW.value().minusSeconds(ageSeconds))),
                unavailable);
    }

    private static OperationsComponentSummary component(
            OperationsMemberHealthSummary summary, OperationsHealthComponent component) {
        return summary.components().stream()
                .filter(value -> value.component() == component)
                .findFirst()
                .orElseThrow();
    }

    private static OperationsHealthThresholds thresholds() {
        EnumMap<OperationsHealthComponent, OperationsComponentThreshold> values =
                new EnumMap<>(OperationsHealthComponent.class);
        for (OperationsHealthComponent component : OperationsHealthComponent.values()) {
            values.put(component, new OperationsComponentThreshold(
                    Duration.ofSeconds(30), Duration.ofMinutes(2), 10, 100));
        }
        values.put(OperationsHealthComponent.DEAD_LETTER,
                new OperationsComponentThreshold(
                        Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1));
        return new OperationsHealthThresholds(values);
    }

    private static TimeProvider fixedTime() {
        return () -> NOW;
    }

    private static final class DirectTransactions implements TransactionExecutor {
        @Override
        public <T> T required(Supplier<T> operation) {
            return operation.get();
        }
    }
}
