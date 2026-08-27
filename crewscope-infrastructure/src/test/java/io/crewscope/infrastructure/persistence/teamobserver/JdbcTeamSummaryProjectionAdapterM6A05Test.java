package io.crewscope.infrastructure.persistence.teamobserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.teamobserver.TeamSummaryProjectionQuery;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.teamobserver.TeamSummaryDataScope;
import io.crewscope.domain.teamobserver.TeamSummaryRequest;
import io.crewscope.domain.teamobserver.TeamSummarySection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Conservative audience SQL and sanitized status-to-section mapping proof for M6-A05. */
class JdbcTeamSummaryProjectionAdapterM6A05Test {

    @Test
    @SuppressWarnings("unchecked")
    void activityReadsOnlyCurrentTeamMemberAudienceAndNeverRawPayload() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        TeamSummaryRequest request = new TeamSummaryRequest(
                OrganizationId.generate(), TeamId.generate(), TeamMemberId.generate(), 10);

        new JdbcTeamSummaryProjectionAdapter(jdbc).read(new TeamSummaryProjectionQuery(
                request, TeamSummaryDataScope.TEAM_ACTIVITY, 10));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertTrue(sql.getValue().contains("event.visibility = 'TEAM_MEMBERS'"));
        assertTrue(sql.getValue().contains("pointer.active_generation"));
        assertFalse(sql.getValue().toLowerCase().contains("payload"));
        assertFalse(sql.getValue().contains("TEAM_ADMINS"));
        assertFalse(sql.getValue().contains("WORK_ITEM_PARTICIPANTS"));
    }

    @Test
    void mapsOnlyApprovedSectionsAndStripsControlText() {
        assertEquals(
                TeamSummarySection.BLOCKERS,
                JdbcTeamSummaryProjectionAdapter.workItemSection("BLOCKED"));
        assertEquals(
                TeamSummarySection.REVIEW_BACKLOG,
                JdbcTeamSummaryProjectionAdapter.inboxSection("REVIEW"));
        assertEquals(
                TeamSummarySection.ANOMALIES,
                JdbcTeamSummaryProjectionAdapter.taskSection("FAILED"));
        assertEquals(
                TeamSummarySection.PROGRESS,
                JdbcTeamSummaryProjectionAdapter.activitySection("WorkItemAdvanced", "WORK_ITEM"));
        assertEquals("private value", JdbcTeamSummaryProjectionAdapter.safe(
                "private\u0000\nvalue"));
    }
}
