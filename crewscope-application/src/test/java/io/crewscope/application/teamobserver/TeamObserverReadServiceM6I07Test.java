package io.crewscope.application.teamobserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.teamobserver.TeamSummaryDataScope;
import io.crewscope.domain.teamobserver.TeamSummaryEntry;
import io.crewscope.domain.teamobserver.TeamSummaryRequest;
import io.crewscope.domain.teamobserver.TeamSummarySection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** M6-I07 current-membership and projection disclosure boundary tests. */
class TeamObserverReadServiceM6I07Test {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-26T10:00:00Z");

    private TeamMemberRepository members;
    private TeamSummaryProjectionPort projections;
    private TeamInitialization team;
    private TeamSummaryRequest request;
    private TeamObserverReadService service;

    @BeforeEach
    void setUp() {
        Principal owner = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(ORGANIZATION_ID),
                PrincipalType.USER,
                Optional.empty(),
                "Owner",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        team = TeamInitialization.create(owner, "Platform", NOW);
        request = new TeamSummaryRequest(
                ORGANIZATION_ID, team.team().id(), team.ownerMember().id(), 5);
        members = mock(TeamMemberRepository.class);
        projections = mock(TeamSummaryProjectionPort.class);
        when(members.findById(ORGANIZATION_ID, team.ownerMember().id()))
                .thenReturn(Optional.of(team.ownerMember()));
        service = new TeamObserverReadService(members, projections);
    }

    @Test
    void reauthorizesAndPassesOnlyTheBoundedExactProjectionQuery() {
        TeamSummaryEntry entry = entry(TeamSummaryDataScope.TEAM_ACTIVITY, "/activity/1");
        when(projections.read(any())).thenReturn(List.of(entry));

        assertEquals(
                List.of(entry),
                service.read(request, TeamSummaryDataScope.TEAM_ACTIVITY));

        ArgumentCaptor<TeamSummaryProjectionQuery> query =
                ArgumentCaptor.forClass(TeamSummaryProjectionQuery.class);
        verify(projections).read(query.capture());
        assertEquals(request, query.getValue().request());
        assertEquals(5, query.getValue().limit());
    }

    @Test
    void rejectsSuspendedMembershipBeforeProjectionAccess() {
        TeamMember suspended = team.ownerMember().suspend(NOW);
        when(members.findById(ORGANIZATION_ID, team.ownerMember().id()))
                .thenReturn(Optional.of(suspended));

        assertThrows(
                DomainValidationException.class,
                () -> service.read(request, TeamSummaryDataScope.TEAM_ACTIVITY));
        verify(projections, never()).read(any());
    }

    @Test
    void rejectsCrossTeamAndPrivateMemberProjectionRows() {
        TeamSummaryEntry foreign = new TeamSummaryEntry(
                ORGANIZATION_ID,
                io.crewscope.domain.shared.id.TeamId.generate(),
                request.requestingMemberId(),
                TeamSummarySection.PROGRESS,
                TeamSummaryDataScope.TEAM_ACTIVITY,
                "Foreign progress",
                "/activity/foreign");
        when(projections.read(any())).thenReturn(List.of(foreign));

        assertThrows(
                DomainValidationException.class,
                () -> service.read(request, TeamSummaryDataScope.TEAM_ACTIVITY));
    }

    @Test
    void rejectsRowsFromAnotherProjectionScopeAndDuplicateEvidence() {
        TeamSummaryEntry wrongScope = entry(
                TeamSummaryDataScope.ARTIFACT_SUMMARY, "/artifacts/1");
        when(projections.read(any())).thenReturn(List.of(wrongScope));
        assertThrows(
                DomainValidationException.class,
                () -> service.read(request, TeamSummaryDataScope.TEAM_ACTIVITY));

        TeamSummaryEntry duplicate = entry(TeamSummaryDataScope.TEAM_ACTIVITY, "/activity/1");
        when(projections.read(any())).thenReturn(List.of(duplicate, duplicate));
        assertThrows(
                DomainValidationException.class,
                () -> service.read(request, TeamSummaryDataScope.TEAM_ACTIVITY));
    }

    @Test
    void rejectsAProjectionThatIgnoresTheAuthorizedLimit() {
        TeamSummaryRequest one = new TeamSummaryRequest(
                request.organizationId(), request.teamId(), request.requestingMemberId(), 1);
        when(projections.read(any())).thenReturn(List.of(
                entry(TeamSummaryDataScope.TEAM_ACTIVITY, "/activity/1"),
                entry(TeamSummaryDataScope.TEAM_ACTIVITY, "/activity/2")));

        assertThrows(
                DomainValidationException.class,
                () -> service.read(one, TeamSummaryDataScope.TEAM_ACTIVITY));
    }

    private TeamSummaryEntry entry(TeamSummaryDataScope scope, String path) {
        return new TeamSummaryEntry(
                request.organizationId(),
                request.teamId(),
                request.requestingMemberId(),
                TeamSummarySection.PROGRESS,
                scope,
                "Current progress is visible.",
                path);
    }
}
