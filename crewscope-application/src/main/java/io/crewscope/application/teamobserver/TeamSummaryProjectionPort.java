package io.crewscope.application.teamobserver;

import io.crewscope.domain.teamobserver.TeamSummaryEntry;
import java.util.List;

/** Read-only projection Port exposed to the fixed Team Observer Tool surface. */
public interface TeamSummaryProjectionPort {

    /** Returns already visibility-filtered summaries; implementations never expose raw private facts. */
    List<TeamSummaryEntry> read(TeamSummaryProjectionQuery query);
}
