package io.crewscope.application.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.audit.AuditEventCategory;
import io.crewscope.domain.audit.AuditOutcome;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Query/export self-audit and failure semantics for M6-A03. */
class AuditQueryApplicationServiceM6A03Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-27T03:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final Principal actor = Principal.create(
            PrincipalId.generate(),
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            "Audit administrator",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);
    private final TeamAccessContext access = new TeamAccessContext(actor, false);
    private final UUID correlationId = UUID.randomUUID();

    private AuditQueryPort queries;
    private AuditAuthorization authorization;
    private AuditAccessRecorder recorder;
    private AuditQueryApplicationService service;

    @BeforeEach
    void setUp() {
        queries = mock(AuditQueryPort.class);
        authorization = mock(AuditAuthorization.class);
        recorder = mock(AuditAccessRecorder.class);
        service = new AuditQueryApplicationService(queries, authorization, recorder, () -> NOW);
    }

    @Test
    void recordsSuccessfulQueryWithTransportCorrelationAndReturnedRowCount() {
        AuditQuery request = query();
        AuditPage expected = new AuditPage(request, List.of(), false);
        when(queries.find(request)).thenReturn(expected);

        assertSame(expected, service.query(access, correlationId, request));

        AuditAccessRecord record = capturedRecord();
        assertEquals(AuditAccessRecord.Operation.QUERY, record.operation());
        assertEquals(AuditOutcome.SUCCEEDED, record.outcome());
        assertEquals(correlationId, record.correlationId());
        assertEquals(organizationId, record.organizationId());
        assertEquals(teamId, record.teamId());
        assertEquals(actor, record.actor());
        assertEquals(0, record.rowCount());
    }

    @Test
    void recordsSuccessfulBoundedExport() {
        AuditExportRequest request = exportRequest();
        AuditExportBatch expected = new AuditExportBatch(request, NOW, List.of());
        when(queries.export(request)).thenReturn(expected);

        assertSame(expected, service.export(access, correlationId, request));

        AuditAccessRecord record = capturedRecord();
        assertEquals(AuditAccessRecord.Operation.EXPORT, record.operation());
        assertEquals(AuditOutcome.SUCCEEDED, record.outcome());
        assertEquals(0, record.rowCount());
    }

    @Test
    void recordsDeniedPredecodeAuthorizationWithoutCallingTheQueryAdapter() {
        PolicyDeniedException denied = new PolicyDeniedException("read Audit events");
        doThrow(denied)
                .when(authorization)
                .requireRead(access, organizationId, teamId, NOW);

        assertSame(
                denied,
                assertThrows(
                        PolicyDeniedException.class,
                        () -> service.requireRead(
                                access, correlationId, organizationId, teamId)));

        AuditAccessRecord record = capturedRecord();
        assertEquals(AuditOutcome.DENIED, record.outcome());
        verify(queries, org.mockito.Mockito.never()).find(any());
    }

    @Test
    void recordsAdapterFailureAndPreservesItWhenFailureAuditAlsoFails() {
        AuditQuery request = query();
        IllegalStateException primary = new IllegalStateException("private database detail");
        IllegalStateException auditFailure = new IllegalStateException("audit sink unavailable");
        when(queries.find(request)).thenThrow(primary);
        doThrow(auditFailure).when(recorder).record(any());

        assertSame(
                primary,
                assertThrows(
                        IllegalStateException.class,
                        () -> service.query(access, correlationId, request)));
        assertEquals(List.of(auditFailure), List.of(primary.getSuppressed()));
    }

    @Test
    void failsClosedWhenTheSuccessAuditCannotBePersistedWithoutRetryingTheAudit() {
        AuditQuery request = query();
        when(queries.find(request)).thenReturn(new AuditPage(request, List.of(), false));
        IllegalStateException auditFailure = new IllegalStateException("audit sink unavailable");
        doThrow(auditFailure).when(recorder).record(any());

        assertSame(
                auditFailure,
                assertThrows(
                        IllegalStateException.class,
                        () -> service.query(access, correlationId, request)));
        verify(recorder).record(any());
    }

    private AuditAccessRecord capturedRecord() {
        ArgumentCaptor<AuditAccessRecord> captor = ArgumentCaptor.forClass(AuditAccessRecord.class);
        verify(recorder).record(captor.capture());
        return captor.getValue();
    }

    private AuditQuery query() {
        return AuditQuery.create(
                organizationId, teamId, AuditQueryFilter.ALL, Optional.empty(), 50);
    }

    private AuditExportRequest exportRequest() {
        AuditQueryFilter filter = new AuditQueryFilter(
                Optional.of(UtcTimestamp.parse("2026-08-01T00:00:00Z")),
                Optional.of(UtcTimestamp.parse("2026-08-27T00:00:00Z")),
                Set.of(AuditEventCategory.SECURITY),
                Set.of(AuditOutcome.SUCCEEDED),
                Set.of(),
                Set.of(),
                Set.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        return AuditExportRequest.create(organizationId, teamId, filter, 1_000);
    }
}
