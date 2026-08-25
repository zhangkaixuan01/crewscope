package io.crewscope.domain.teamobserver;

import java.util.Set;

/** Read-only projection families that may contribute to one Team summary entry. */
public enum TeamSummaryDataScope {
    TEAM_ACTIVITY,
    TEAM_INBOX_SUMMARY,
    WORK_ITEM_SUMMARY,
    TASK_SUMMARY,
    ARTIFACT_SUMMARY;

    public Set<TeamSummarySection> allowedSections() {
        return switch (this) {
            case TEAM_ACTIVITY -> Set.of(
                    TeamSummarySection.PROGRESS,
                    TeamSummarySection.BLOCKERS,
                    TeamSummarySection.ANOMALIES);
            case TEAM_INBOX_SUMMARY -> Set.of(
                    TeamSummarySection.REVIEW_BACKLOG,
                    TeamSummarySection.PENDING_CONFIRMATIONS,
                    TeamSummarySection.ANOMALIES);
            case WORK_ITEM_SUMMARY -> Set.of(
                    TeamSummarySection.PROGRESS,
                    TeamSummarySection.BLOCKERS,
                    TeamSummarySection.REVIEW_BACKLOG);
            case TASK_SUMMARY -> Set.of(
                    TeamSummarySection.PROGRESS,
                    TeamSummarySection.BLOCKERS,
                    TeamSummarySection.REVIEW_BACKLOG,
                    TeamSummarySection.ANOMALIES);
            case ARTIFACT_SUMMARY -> Set.of(TeamSummarySection.PROGRESS);
        };
    }
}
