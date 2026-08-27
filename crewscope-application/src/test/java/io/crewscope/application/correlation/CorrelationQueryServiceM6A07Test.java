package io.crewscope.application.correlation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Current-membership and current-member Inbox scoping proof for M6-A07. */
class CorrelationQueryServiceM6A07Test {

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final UUID correlationId = UUID.randomUUID();
    private final TeamMemberId memberId = TeamMemberId.generate();
    private WorkItemAccessPolicy accessPolicy;
    private CorrelationQueryPort queries;
    private TeamAccessContext access;
    private CorrelationQueryService service;

    @BeforeEach
    void setUp() {
        accessPolicy = mock(WorkItemAccessPolicy.class);
        queries = mock(CorrelationQueryPort.class);
        access = mock(TeamAccessContext.class);
        TeamMember member = mock(TeamMember.class);
        when(member.id()).thenReturn(memberId);
        when(accessPolicy.requireVisibleTeamMember(access, organizationId, teamId))
                .thenReturn(member);
        TransactionExecutor direct = new TransactionExecutor() {
            @Override
            public <T> T required(java.util.function.Supplier<T> operation) {
                return operation.get();
            }
        };
        service = new CorrelationQueryService(accessPolicy, queries, direct);
    }

    @Test
    void bindsEveryPageToTheCurrentlyAuthorizedMember() {
        CorrelationQuery expected = new CorrelationQuery(
                organizationId, teamId, memberId, correlationId, Optional.empty(), 25);
        CorrelationPage page = new CorrelationPage(
                correlationId, List.of(), List.of(), false, Optional.empty());
        when(queries.find(expected)).thenReturn(page);

        assertEquals(page, service.find(
                access, organizationId, teamId, correlationId, Optional.empty(), 25));
        verify(accessPolicy).requireVisibleTeamMember(access, organizationId, teamId);
        verify(queries).find(expected);
    }

    @Test
    void deniesAContinuationWhenMembershipWasRemoved() {
        when(accessPolicy.requireVisibleTeamMember(access, organizationId, teamId))
                .thenThrow(new PolicyDeniedException("access this Team's Correlations"));

        assertThrows(PolicyDeniedException.class, () -> service.find(
                access, organizationId, teamId, correlationId, Optional.empty(), 25));
    }
}
