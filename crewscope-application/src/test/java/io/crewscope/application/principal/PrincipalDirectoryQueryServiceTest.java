package io.crewscope.application.principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.audit.AuditAuthorization;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Membership still gates every purpose, an AUDIT purpose adds the Team's audit permission on every
 * request, and the full-set read itself is one repository call — no in-memory window anywhere.
 */
class PrincipalDirectoryQueryServiceTest {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final TeamId TEAM_ID = TeamId.generate();

    @Test
    void membershipGatesAssignmentReadsAndDelegatesTheFullSetRead() {
        PrincipalDirectoryAccessPolicy accessPolicy = mock(PrincipalDirectoryAccessPolicy.class);
        PrincipalDirectoryRepository directory = mock(PrincipalDirectoryRepository.class);
        AuditAuthorization auditAuthorization = mock(AuditAuthorization.class);
        TeamMember viewer = mock(TeamMember.class);
        when(viewer.id()).thenReturn(new TeamMemberId(java.util.UUID.randomUUID()));
        when(accessPolicy.requireMember(any(), any(), any())).thenReturn(viewer);
        PrincipalDirectoryQuery query = query(PrincipalDirectoryPurpose.ASSIGNMENT);
        PrincipalDirectoryPage page = new PrincipalDirectoryPage(
                List.of(), OptionalInt.empty(), Optional.empty());
        when(directory.search(any(), any())).thenReturn(page);
        PrincipalDirectoryQueryService service = new PrincipalDirectoryQueryService(
                accessPolicy, auditAuthorization, directory,
                transactionExecutor(), clock());

        assertEquals(page, service.search(mock(TeamAccessContext.class), query));

        verify(directory).search(query, viewer.id());
        verify(auditAuthorization, never()).requireRead(any(), any(), any(), any());
    }

    @Test
    void anAuditPurposeReevaluatesTheAuditPermissionOnEveryRequest() {
        PrincipalDirectoryAccessPolicy accessPolicy = mock(PrincipalDirectoryAccessPolicy.class);
        PrincipalDirectoryRepository directory = mock(PrincipalDirectoryRepository.class);
        AuditAuthorization auditAuthorization = mock(AuditAuthorization.class);
        TeamMember viewer = mock(TeamMember.class);
        when(viewer.id()).thenReturn(new TeamMemberId(java.util.UUID.randomUUID()));
        when(accessPolicy.requireMember(any(), any(), any())).thenReturn(viewer);
        doThrow(new PolicyDeniedException("read Team Audit events"))
                .when(auditAuthorization)
                .requireRead(any(), any(), any(), any());
        PrincipalDirectoryQueryService service = new PrincipalDirectoryQueryService(
                accessPolicy, auditAuthorization, directory,
                transactionExecutor(), clock());

        assertThrows(PolicyDeniedException.class,
                () -> service.search(mock(TeamAccessContext.class),
                        query(PrincipalDirectoryPurpose.AUDIT)));

        verify(directory, never()).search(any(), any());
    }

    private static PrincipalDirectoryQuery query(PrincipalDirectoryPurpose purpose) {
        return new PrincipalDirectoryQuery(
                ORGANIZATION_ID, TEAM_ID, Optional.empty(), Set.of(), Set.of(), purpose,
                Optional.empty(), 0, 20);
    }

  private static TransactionExecutor transactionExecutor() {
    return new TransactionExecutor() {
      @Override
      public <T> T required(java.util.function.Supplier<T> operation) {
        return operation.get();
      }
    };
  }

  private static io.crewscope.domain.shared.time.TimeProvider clock() {
    return () -> UtcTimestamp.parse("2026-09-25T00:00:00Z");
  }
}
