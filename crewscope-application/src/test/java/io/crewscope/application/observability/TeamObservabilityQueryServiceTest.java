package io.crewscope.application.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.observability.TeamObservabilityQueryService.CostSource;
import io.crewscope.application.observability.TeamObservabilityQueryService.ModelUsageMonthSummary;
import io.crewscope.application.observability.TeamObservabilityQueryService.TeamCostMonthsPage;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamJoinMethod;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamScope;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * M10-F03 query service: source folding (chat roles → EXECUTION), unpriced 'XXX' handling,
 * month paging with an exclusive cursor, the member guard with the platform administrator
 * bypass, and month validation against the reporting zone.
 */
class TeamObservabilityQueryServiceTest {

    private static final OrganizationId ORGANIZATION = OrganizationId.generate();
    private static final TeamId TEAM = TeamId.generate();
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final ModelUsageRollupQueryRepository rollups =
            mock(ModelUsageRollupQueryRepository.class);
    private final TaskQualityStatisticsRepository quality =
            mock(TaskQualityStatisticsRepository.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final TeamMembershipQuery memberships = mock(TeamMembershipQuery.class);

    private TeamObservabilityQueryService service;
    private Principal actor;
    private Team team;

    @BeforeEach
    void setUp() {
        actor = mock(Principal.class);
        when(actor.type()).thenReturn(PrincipalType.USER);
        when(actor.canAct()).thenReturn(true);
        when(actor.id()).thenReturn(PrincipalId.generate());
        PrincipalScope scope = mock(PrincipalScope.class);
        when(scope.organizationId()).thenReturn(ORGANIZATION);
        when(actor.scope()).thenReturn(scope);
        team = mock(Team.class);
        when(team.organizationId()).thenReturn(ORGANIZATION);
        when(team.id()).thenReturn(TEAM);
        when(team.isActive()).thenReturn(true);
        when(teams.findById(ORGANIZATION, TEAM)).thenReturn(Optional.of(team));
        // Build the member before opening the stubbing: nesting mock calls inside
        // thenReturn(...) leaves the outer when(...) unfinished.
        TeamMember activeMember = member(true);
        when(memberships.findByTeam(ORGANIZATION, TEAM)).thenReturn(List.of(activeMember));
        // Fixed authoritative time inside October 2026 in the reporting zone.
        service = new TeamObservabilityQueryService(
                rollups, quality, teams, memberships,
                () -> UtcTimestamp.parse("2026-10-05T12:00:00Z"), SHANGHAI);
    }

    @Test
    void costMonthsFoldChatRolesIntoExecutionAndTrackUnpricedSeparately() {
        when(rollups.findUsageMonths(eq(ORGANIZATION), eq(TEAM), any(), eq(3)))
                .thenReturn(List.of("2026-10"));
        when(rollups.findMonthAggregates(eq(ORGANIZATION), eq(TEAM), anyCollection()))
                .thenReturn(List.of(
                        new ModelUsageMonthRoleCurrencyRow(
                                "2026-10", "CHAT_PRIMARY", "USD",
                                100, 40, 50, bd("0.44"), bd("0.05"), bd("0.007"), 3, 0),
                        new ModelUsageMonthRoleCurrencyRow(
                                "2026-10", "CHAT_FALLBACK", "USD",
                                10, 4, 0, bd("0.04"), bd("0.005"), null, 1, 0),
                        new ModelUsageMonthRoleCurrencyRow(
                                "2026-10", "COMPACTION", "USD",
                                5, 1, 0, bd("0.02"), bd("0.001"), null, 1, 0),
                        new ModelUsageMonthRoleCurrencyRow(
                                "2026-10", "EMBEDDING", "CNY",
                                200, 0, 0, bd("0.10"), null, null, 2, 1),
                        new ModelUsageMonthRoleCurrencyRow(
                                "2026-10", "DISTILLATION", "XXX",
                                30, 10, 0, null, null, null, 1, 0)));

        TeamCostMonthsPage page = service.costMonths(
                new TeamAccessContext(actor, false), ORGANIZATION, TEAM, Optional.empty(), 2);

        assertEquals(1, page.months().size());
        assertEquals(Optional.empty(), page.nextAfter());
        ModelUsageMonthSummary summary = page.months().get(0);
        assertEquals(115, summary.roles().get(CostSource.EXECUTION).inputTokens());
        assertEquals(45, summary.roles().get(CostSource.EXECUTION).outputTokens());
        assertEquals(5, summary.roles().get(CostSource.EXECUTION).factCount());
        assertEquals(200, summary.roles().get(CostSource.EMBEDDING).inputTokens());
        assertEquals(2, summary.roles().get(CostSource.EMBEDDING).factCount());
        assertEquals(30, summary.roles().get(CostSource.DISTILLATION).inputTokens());
        // Only real currencies are listed; costs sum per currency across roles.
        assertEquals(2, summary.currencies().size());
        assertEquals(bd("0.50"), summary.currencies().get("USD").inputCost());
        assertEquals(bd("0.056"), summary.currencies().get("USD").outputCost());
        assertEquals(bd("0.10"), summary.currencies().get("CNY").inputCost());
        assertEquals(40, summary.unpricedTokens());
        assertEquals(8, summary.totalFactCount());
    }

    @Test
    void costMonthsPageCarriesNextCursorOnlyWhenOlderMonthsRemain() {
        when(rollups.findUsageMonths(ORGANIZATION, TEAM, null, 3))
                .thenReturn(List.of("2026-10", "2026-09", "2026-08"));
        when(rollups.findMonthAggregates(eq(ORGANIZATION), eq(TEAM), anyCollection()))
                .thenReturn(List.of());

        TeamCostMonthsPage page = service.costMonths(
                new TeamAccessContext(actor, false), ORGANIZATION, TEAM, Optional.empty(), 2);

        assertEquals(List.of("2026-10", "2026-09"), page.months().stream()
                .map(ModelUsageMonthSummary::usageMonth).toList());
        assertEquals(Optional.of("2026-09"), page.nextAfter());
    }

    @Test
    void platformAdministratorBypassesMembershipButNotTeamExistence() {
        when(rollups.findUsageMonths(eq(ORGANIZATION), eq(TEAM), any(), anyInt()))
                .thenReturn(List.of());
        when(rollups.findMonthAggregates(eq(ORGANIZATION), eq(TEAM), anyCollection()))
                .thenReturn(List.of());
        when(memberships.findByTeam(ORGANIZATION, TEAM)).thenReturn(List.of());

        TeamCostMonthsPage page = service.costMonths(
                new TeamAccessContext(actor, true), ORGANIZATION, TEAM, Optional.empty(), 12);
        assertEquals(0, page.months().size());

        when(teams.findById(ORGANIZATION, TEAM)).thenReturn(Optional.empty());
        assertThrows(AggregateNotFoundException.class, () -> service.costMonths(
                new TeamAccessContext(actor, true), ORGANIZATION, TEAM,
                Optional.empty(), 12));
    }

    @Test
    void inactiveMemberIsDeniedAndInactiveTeamStaysNotFound() {
        // Build the memberships before opening each stubbing — member(...) calls the
        // actor mock, which would leave the outer when(...) unfinished.
        TeamMember removed = member(false);
        when(memberships.findByTeam(ORGANIZATION, TEAM)).thenReturn(List.of(removed));

        assertThrows(PolicyDeniedException.class, () -> service.costMonths(
                new TeamAccessContext(actor, false), ORGANIZATION, TEAM,
                Optional.empty(), 12));

        when(team.isActive()).thenReturn(false);
        TeamMember active = member(true);
        when(memberships.findByTeam(ORGANIZATION, TEAM)).thenReturn(List.of(active));
        assertThrows(AggregateNotFoundException.class, () -> service.costMonths(
                new TeamAccessContext(actor, false), ORGANIZATION, TEAM,
                Optional.empty(), 12));
    }

    @Test
    void requireRequestableMonthRejectsMalformedAndFutureMonths() {
        assertEquals("2026-10", service.requireRequestableMonth("2026-10"));
        assertThrows(IllegalArgumentException.class,
                () -> service.requireRequestableMonth("2026-13"));
        assertThrows(IllegalArgumentException.class,
                () -> service.requireRequestableMonth("not-a-month"));
        // November 2026 is still the future in Shanghai at the fixed clock.
        assertThrows(IllegalArgumentException.class,
                () -> service.requireRequestableMonth("2026-11"));
    }

    @Test
    void qualityMonthWindowsFollowTheReportingZone() {
        when(quality.findForWindow(eq(ORGANIZATION), eq(TEAM), any(), any()))
                .thenReturn(new TaskQualityStatistics(4, 3, 1, 0, 2, 1));

        TaskQualityStatistics statistics = service.qualityMonth(
                new TeamAccessContext(actor, false), ORGANIZATION, TEAM, "2026-10");

        assertEquals(4, statistics.executionAttempts());
        assertEquals(2, statistics.enteredReview());
        // 2026-10 in Shanghai starts at 2026-09-30T16:00:00Z.
        verify(quality).findForWindow(
                eq(ORGANIZATION), eq(TEAM),
                eq(java.time.Instant.parse("2026-09-30T16:00:00Z")),
                eq(java.time.Instant.parse("2026-10-31T16:00:00Z")));
    }

    private TeamMember member(boolean participatable) {
        // A real membership, not a mock: TeamMember is a final class, and evaluating
        // mock calls inside thenReturn(...) leaves stubbings unfinished. REMOVED
        // members fail canParticipate() the same way a suspended member would.
        TeamMember joined = TeamMember.join(
                TeamMemberId.generate(),
                new TeamScope(ORGANIZATION, TEAM),
                actor,
                TeamJoinMethod.BOOTSTRAP,
                UtcTimestamp.parse("2026-09-01T08:00:00Z"));
        return participatable ? joined
                : joined.remove(UtcTimestamp.parse("2026-10-01T08:00:00Z"));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
